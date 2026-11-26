package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.HOUR_MS
import com.luckylearning.windtone.data.ListenBucket
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.ReportFilter
import com.luckylearning.windtone.ui.AppStrings
import com.luckylearning.windtone.data.aliyun.AliyunDrive
import com.luckylearning.windtone.data.aliyun.AliyunTrackRecord
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.buildListenCalendarMonth
import com.luckylearning.windtone.data.buildListeningReport
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import com.luckylearning.windtone.state.CalendarUiState
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

    override fun setReportFilter(filter: ReportFilter) = loadReport(filter = filter)

    override fun loadCalendar(year: Int, month: Int) {
        val y = year
        val m = month.coerceIn(1, 12)
        _library.update {
            val base = it.report ?: ReportUiState()
            it.copy(
                report = base.copy(
                    calendar = CalendarUiState(year = y, month = m, loading = true),
                ),
            )
        }
        scope.launch {
            val zone = java.util.TimeZone.getDefault()
            val cal = java.util.Calendar.getInstance(zone)
            cal.clear()
            cal.set(java.util.Calendar.YEAR, y)
            cal.set(java.util.Calendar.MONTH, m - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            // 向前多取一周，覆盖月初补齐的邻月格
            cal.add(java.util.Calendar.DAY_OF_MONTH, -7)
            val fromHour = cal.timeInMillis / HOUR_MS
            val buckets = withContext(Dispatchers.IO) { reportBuckets(fromHour, currentFilter()) }
            val monthData = withContext(Dispatchers.Default) {
                buildListenCalendarMonth(buckets, y, m, zone)
            }
            _library.update {
                val base = it.report ?: ReportUiState()
                it.copy(
                    report = base.copy(
                        calendar = CalendarUiState(
                            year = y,
                            month = m,
                            monthData = monthData,
                            loading = false,
                        ),
                    ),
                )
            }
        }
    }

    /** 当前报告天数（省略 [loadReport] 的 days 时沿用） */
    internal fun currentDays(): Int = _library.value.report?.days ?: DEFAULT_REPORT_DAYS

    /** 当前音源筛选（省略 [loadReport] 的 filter 时沿用） */
    internal fun currentFilter(): ReportFilter = _library.value.report?.filter ?: ReportFilter.ALL

    /**
     * 按当前筛选取小时桶。
     *
     * ALL 取 `listen_log` 全部来源；单来源只取对应行。三种音源从 v6 起分桶写入，
     * 所以每小时每来源各一行，直接交给纯函数聚合即可（同小时同来源只有一行）。
     */
    private suspend fun reportBuckets(fromHour: Long, filter: ReportFilter): List<ListenBucket> {
        val dao = db.listenLogDao()
        val rows = runCatching {
            val src = filter.sourceName
            if (src == null) dao.since(fromHour) else dao.sinceBySource(fromHour, src)
        }.getOrDefault(emptyList())
        return rows.map { ListenBucket(it.hourEpoch, it.ms) }
    }

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
    internal fun loadReport(days: Int = currentDays(), filter: ReportFilter = currentFilter()) {
        val span = days.coerceIn(7, 365)
        _library.update {
            it.copy(
                report = (it.report ?: ReportUiState(days = span, filter = filter))
                    .copy(days = span, filter = filter, loading = true),
            )
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val fromHour = now / HOUR_MS - span * 24L
            // 云盘统计在独立 SP 里（榜单字段），小时桶已随 v6 并入 listen_log。
            val (localTopPlayed, localTopListened, cloudTopPlayed, cloudTopListened) =
                withContext(Dispatchers.IO) {
                    val byId = _library.value.songs.associateBy { it.dbId }
                    val states = index.allStates()
                    val inScope = when (filter) {
                        ReportFilter.ALL -> states
                        ReportFilter.LOCAL -> states.filter { byId[it.songId]?.source != SongSource.ONLINE }
                        ReportFilter.LX -> states.filter { byId[it.songId]?.source == SongSource.ONLINE }
                        ReportFilter.CLOUD -> emptyList()
                    }
                    val played = inScope.asSequence()
                        .filter { it.playCount > 0 }
                        .sortedByDescending { it.playCount }
                        .take(TOP_LIMIT)
                        .mapNotNull { st -> byId[st.songId]?.let { topSong(st.songId, it, st.playCount, st.playedMs) } }
                        .toList()
                    val listened = inScope.asSequence()
                        .filter { it.playedMs > 0 }
                        .sortedByDescending { it.playedMs }
                        .take(TOP_LIMIT)
                        .mapNotNull { st -> byId[st.songId]?.let { topSong(st.songId, it, st.playCount, st.playedMs) } }
                        .toList()
                    val cloud = if (filter == ReportFilter.CLOUD || filter == ReportFilter.ALL) {
                        val records = AliyunTrackStore(session.app).records()
                        records.top(byPlay = true) to records.top(byPlay = false)
                    } else null to null
                    Quad(played, listened, cloud.first.orEmpty(), cloud.second.orEmpty())
                }
            val buckets = withContext(Dispatchers.IO) { reportBuckets(fromHour, filter) }
            val report = withContext(Dispatchers.Default) {
                buildListeningReport(buckets, now, span)
            }
            _library.update {
                it.copy(
                    report = ReportUiState(
                        days = span,
                        filter = filter,
                        report = report,
                        topPlayed = mergeTops(localTopPlayed, cloudTopPlayed, byPlay = true),
                        topListened = mergeTops(localTopListened, cloudTopListened, byPlay = false),
                        loading = false,
                        // 换筛选口径后旧日历数据作废，下次打开时按当前筛选重算
                        calendar = null,
                    )
                )
            }
        }
    }

    /** 单个榜单按数值合并排序；ALL 时本地与云盘各出前 10，再统一取前 10 */
    private fun mergeTops(local: List<TopSong>, cloud: List<TopSong>, byPlay: Boolean): List<TopSong> =
        if (cloud.isEmpty()) local
        else (local + cloud)
            .sortedByDescending { item: TopSong -> if (byPlay) item.playCount.toLong() else item.listenedMs }
            .take(TOP_LIMIT)

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun List<AliyunTrackRecord>.top(byPlay: Boolean): List<TopSong> {
        val ranked = if (byPlay) {
            filter { it.playCount > 0 }.sortedByDescending { it.playCount }
        } else {
            filter { it.playedMs > 0 }.sortedByDescending { it.playedMs }
        }
        return ranked.take(TOP_LIMIT).map { record ->
            val song = AliyunDrive(tokenProvider = { null }).pendingSong(record)
            TopSong(
                songId = song.id,
                stableKey = song.stableKey,
                title = song.title.ifBlank { AppStrings.s(R.string.untitled) },
                artist = song.artistOrUnknown,
                playCount = record.playCount,
                listenedMs = record.playedMs,
                cover = song,
            )
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
