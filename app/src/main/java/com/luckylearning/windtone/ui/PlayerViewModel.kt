package com.luckylearning.windtone.ui

import android.app.Application
import android.net.Uri
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.data.AudioInfoCache
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.SongFacts
import com.luckylearning.windtone.data.ThemeMode
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.playback.PlayerActions
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放域 ViewModel：界面与 [com.luckylearning.windtone.playback.PlaybackEngine] 之间的那一层。
 *
 * 它自己不持有播放状态——状态在引擎里（应用级单例，生命周期长于界面）。
 * 动作面用 Kotlin 的 `by` 整块委托出去，所以这里没有四十个转发方法，
 * 只有「引擎之上还需要界面在场」的那点逻辑（目前只有歌词导出）。
 */
class PlayerViewModel(private val app: Application) :
    AndroidViewModel(app),
    PlayerActions by (app as MusicApp).container.playback {

    private val container = (app as MusicApp).container
    private val engine = container.playback
    private val messages = container.messages

    /** 音频技术参数缓存：三页横滑来回翻时不重复解析（含「解析失败」的结论） */
    private val audioInfoCache = AudioInfoCache()

    val player: StateFlow<PlayerUiState> = engine.player

    val progress: StateFlow<PlaybackProgress> = engine.progress

    /** 封面主色（动态主题的种子色）；未开启动态主题时为 null */
    val themeSeed: StateFlow<Int?> = engine.themeSeed

    /** 界面明暗模式（跟随系统 / 浅色 / 深色，v1.2.5）：最外层换肤订阅它 */
    val themeMode: StateFlow<ThemeMode> = engine.themeMode

    /** Material You 跟随壁纸取色（Android 12+）：最外层换肤订阅它 */
    val materialYouEnabled: StateFlow<Boolean> = engine.materialYouEnabled

    /** 桌面悬浮窗的运行状态：设置页据此显示「运行中 / 启动失败原因」 */
    val overlayHealth: StateFlow<LyricOverlayBus.Health> = container.playback.overlayHealth

    /**
     * 读取「歌曲信息」页需要的**轻量**派生数据（歌单归属 / 播放统计 / 标签）。
     *
     * 只在信息页可见时调用一次。刻意做成挂起函数而不是状态流：
     * 页内没有「数据实时变化」的诉求，进页拉一次、离页丢弃最简单，
     * 也不会为了一个弹页把三张表全订阅起来。
     *
     * ⚠️ v1.2.57 起**不再包含音频技术参数**：那一步要开 `MediaExtractor`，
     * 无损容器还要解码 3 秒 PCM 判假无损（云盘曲目会触发网络 Range），
     * 是本页最重的一环。现在它单独走 [loadSongAudioInfo]，由界面在轻量数据
     * 落地之后再补 —— 先让页面有内容，重活不许和首帧 / 翻页动画抢资源。
     */
    suspend fun loadSongFacts(song: Song): SongFacts = withContext(Dispatchers.IO) {
        val songId = song.dbId
        val playlists = if (songId > 0L) {
            container.db.playlistDao().playlistsContaining(songId).map { it.name }
        } else {
            emptyList()
        }
        val state = if (songId > 0L) container.db.songStateDao().bySongId(songId) else null
        // genre / year / albumArtist / discNumber 不入 Room，扫描时写在 Prefs 侧缓存里；
        // 从数据库直接构造的 Song 这几个字段是 null，这里盖回来一次，信息页才有完整标签。
        val tagged = container.discNumbers.overlay(
            container.albumArtists.overlay(container.genreYears.overlay(song))
        )
        SongFacts(
            playlistNames = playlists,
            playCount = state?.playCount ?: 0,
            lastPlayedAt = state?.lastPlayedAt ?: 0L,
            playedMs = state?.playedMs ?: 0L,
            genre = tagged.genre,
            year = tagged.year,
            albumArtist = tagged.albumArtist,
            discNumber = tagged.discNumber,
        )
    }

    /**
     * 读取「歌曲信息」页的**重量**派生数据：音频技术参数（容器 / 编码 / 采样率 /
     * 位深 / 码率 / 大小 / 假无损结论）。
     *
     * 两点刻意为之：
     *  1. **低优先级线程**：解析要解 3 秒 PCM 做 FFT，CPU 占用不小；不降优先级的话
     *     在车机（4 核 A55 一类）上会和界面抢核，翻页落位那一下直接掉帧。
     *     同一线程池的其它 IO 任务很多，所以跑完必须把原优先级还回去。
     *  2. **结果进内存缓存**（[AudioInfoCache]）：三页横滑很容易来回翻，
     *     同一首歌不重复解析；`null` 也缓存，避免反复重试注定失败的解析。
     */
    suspend fun loadSongAudioInfo(song: Song): AudioInspector.Info? {
        audioInfoCache.get(song.stableKey)?.let { return it.info }
        val info = withContext(Dispatchers.IO) {
            val tid = Process.myTid()
            val original = runCatching { Process.getThreadPriority(tid) }.getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                AudioInspector.inspect(app, song)
            } finally {
                // Dispatchers.IO 的线程是复用的：不还回去会连带拖慢后面的数据库 / 文件读写
                runCatching { Process.setThreadPriority(original) }
            }
        }
        audioInfoCache.put(song.stableKey, info)
        return info
    }

    /** 播放页展开 / 再次进入时的歌词兜底：只在当前曲没有歌词时重载一次 */
    fun ensureLyrics() = engine.ensureLyrics()

    /** 把当前歌词导出成 LRC 文件（带翻译行的双语歌词会同时导出） */
    fun exportLyrics(target: Uri) {
        viewModelScope.launch {
            val state = engine.player.value
            // 用原始歌词而不是 state.lyrics：后者可能已被「双语歌词」设置裁成单语，
            // 按那种结果导出会把译文永久丢掉（文件一旦写出去就找不回来了）。
            val lyrics = engine.lyricsForExport()
            if (lyrics.isEmpty) {
                messages.post("当前歌曲没有歌词")
                return@launch
            }
            val text = buildString {
                appendLine("[ti:${state.current?.title.orEmpty()}]")
                appendLine("[ar:${state.current?.artistOrUnknown.orEmpty()}]")
                if (!lyrics.synced) {
                    lyrics.lines.forEach { appendLine(it.text) }
                } else {
                    lyrics.lines.forEach { line ->
                        val t = line.timeMs.coerceAtLeast(0)
                        val mm = t / 60_000
                        val ss = (t % 60_000) / 1000
                        val xx = (t % 1000) / 10
                        appendLine("[%02d:%02d.%02d]%s".format(mm, ss, xx, line.text))
                        line.translation?.let { tr ->
                            appendLine("[%02d:%02d.%02d]%s".format(mm, ss, xx, tr))
                        }
                    }
                }
            }
            runCatching {
                app.contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                messages.post("歌词已导出为 LRC")
            }.onFailure { messages.post("导出失败：${it.message}") }
        }
    }
}
