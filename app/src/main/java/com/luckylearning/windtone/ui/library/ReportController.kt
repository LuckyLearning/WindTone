package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.HOUR_MS
import com.luckylearning.windtone.data.ListenBucket
import com.luckylearning.windtone.data.buildListeningReport
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.ReportUiState
import com.luckylearning.windtone.state.TopSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收听报告域的**实现**（批次 10.1）。
 *
 * 依赖与共享状态从 [LibrarySession] 取。承载「曲线 + 时段分布 + 两个榜单」，
 * 全部是本地查询 + 纯函数聚合，没有网络请求。
 */
internal class ReportController(private val session: LibrarySession) : ReportActions {

    // ---------------------------------------------------------- 收听报告（10.1）

    private val db = session.db
    private val index = session.index
    private val _library = session._library

    private val scope get() = session.scope

    override fun setReportDays(days: Int) = loadReport(days)

    /** 当前报告天数（省略 [loadReport] 的 days 时沿用） */
    internal fun currentDays(): Int = _library.value.report?.days ?: DEFAULT_REPORT_DAYS

    /**
     * 载入收听报告。
     *
     * 曲线与时段分布来自 `listen_log` 的小时桶；两个榜单来自 `song_state` 的累计统计。
     * 全部是本地查询 + 纯函数聚合，没有网络请求。
     *
     * 注意：本方法**刻意不在 [ReportActions] 接口上**，而是由 `LibraryViewModel`
     * 直接声明——它的默认值 `_library.value.report?.days ?: DEFAULT_REPORT_DAYS`
     * 依赖实例状态，接口默认值无法表达。
     */
    internal fun loadReport(days: Int = currentDays()) {
        val span = days.coerceIn(7, 365)
        _library.update {
            it.copy(
                report = (it.report ?: ReportUiState(days = span)).copy(days = span, loading = true),
            )
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val fromHour = now / HOUR_MS - span * 24L
            val buckets = withContext(Dispatchers.IO) {
                runCatching {
                    db.listenLogDao().since(fromHour).map { ListenBucket(it.hourEpoch, it.ms) }
                }.getOrDefault(emptyList())
            }
            val byId = _library.value.songs.associateBy { it.dbId }
            val states = index.allStates()
            val topPlayed = states.asSequence()
                .filter { it.playCount > 0 }
                .sortedByDescending { it.playCount }
                .take(TOP_LIMIT)
                .mapNotNull { st -> byId[st.songId]?.let { topSong(st.songId, it, st.playCount, st.playedMs) } }
                .toList()
            val topListened = states.asSequence()
                .filter { it.playedMs > 0 }
                .sortedByDescending { it.playedMs }
                .take(TOP_LIMIT)
                .mapNotNull { st -> byId[st.songId]?.let { topSong(st.songId, it, st.playCount, st.playedMs) } }
                .toList()
            val report = withContext(Dispatchers.Default) {
                buildListeningReport(buckets, now, span)
            }
            _library.update {
                it.copy(
                    report = ReportUiState(
                        days = span,
                        report = report,
                        topPlayed = topPlayed,
                        topListened = topListened,
                        loading = false,
                    )
                )
            }
        }
    }

    private fun topSong(songId: Long, song: Song, plays: Int, ms: Long) = TopSong(
        songId = songId,
        stableKey = song.stableKey,
        title = song.title,
        artist = song.artistOrUnknown,
        playCount = plays,
        listenedMs = ms,
        cover = song,
    )

    companion object {
        /** 收听报告默认看多少天 */
        const val DEFAULT_REPORT_DAYS = 30
        /** 收听报告的榜单长度（播放次数榜 / 收听时长榜） */
        private const val TOP_LIMIT = 10
    }
}
