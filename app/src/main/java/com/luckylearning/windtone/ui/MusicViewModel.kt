package com.luckylearning.windtone.ui

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.LyricsLoader
import com.luckylearning.windtone.data.MusicRepository
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.PinyinIndex
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SongDeleter
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.UpdateCheckResult
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.data.UpdateInfo
import com.luckylearning.windtone.data.webdav.WebDavClient
import com.luckylearning.windtone.data.webdav.WebDavScanner
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.db.SongCacheEntity
import com.luckylearning.windtone.data.db.SongOverrideEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.PlaybackConnection
import com.luckylearning.windtone.playback.PlayerHandle
import com.luckylearning.windtone.playback.SleepTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

// ---------------------------------------------------------------- 视图模型

/** 库主视图：歌曲 / 专辑 / 歌手 / 文件夹 / 歌单 */
enum class LibraryView(val label: String) {
    SONGS("歌曲"),
    ALBUM("专辑"),
    ARTIST("歌手"),
    FOLDER("文件夹"),
    PLAYLIST("歌单");

    companion object {
        /** 从持久化的名字还原，非法值回落到「歌曲」 */
        fun from(name: String): LibraryView = entries.firstOrNull { it.name == name } ?: SONGS
    }
}

/** 专辑 / 歌手 / 文件夹的分组条目 */
@Immutable
data class GroupInfo(
    val key: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val sample: Song?,
)

/** 歌单条目（含固定的虚拟歌单：最近播放 / 最常播放） */
@Immutable
data class PlaylistInfo(
    val id: Long,
    val name: String,
    val songCount: Int,
    val coverSong: Song?,
    val virtual: Boolean = false,
)

/**
 * 高频刷新的播放进度。
 *
 * 从 [PlayerUiState] 里拆出来的原因：进度每 250ms 就会变一次，
 * 混在 PlayerUiState 里会让整个主界面跟着每秒重组 4 次。
 * 拆开之后，只有真正读它的组件（进度条 / 迷你条 / 歌词面板）才重组。
 */
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
)

@Immutable
data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val visible: List<Song> = emptyList(),
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.TITLE,
    val scanning: Boolean = false,
    val statusText: String = "",
    val skippedUnsupported: Int = 0,
    val lyricsCount: Int = 0,
    val deepScan: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val scannedOnce: Boolean = false,
    /** 扫描范围：未选择（首次安装）时不自动扫描，等待用户指定全盘或目录 */
    val scanScope: ScanScope = ScanScope.UNSET,
    /** 已导入的目录数量 */
    val folderCount: Int = 0,
    /** 已导入的目录（SAF 树 URI），用于范围抽屉里的目录管理 */
    val folderUris: List<String> = emptyList(),
    val message: String? = null,
    // 批次 2
    val view: LibraryView = LibraryView.SONGS,
    val groups: List<GroupInfo> = emptyList(),
    val selectedGroup: GroupInfo? = null,
    val detailSongs: List<Song> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val selectedPlaylist: PlaylistInfo? = null,
    val playlistSongs: List<Song> = emptyList(),
    val favoriteUris: Set<String> = emptySet(),
    val extraRecent: List<Song> = emptyList(),
    val extraMostPlayed: List<Song> = emptyList(),
    // 批次 3
    val letterIndex: Map<Char, Int> = emptyMap(),
    val restored: Boolean = false,
    // 批次 5 / 6
    val searchHistory: List<String> = emptyList(),
    val playStats: PlayStats = PlayStats(),
    // WebDAV 云盘
    val webdavConfigured: Boolean = false,
    val webdavEnabled: Boolean = false,
    val webdavScanning: Boolean = false,
    val webdavSongCount: Int = 0,
    val webdavMessage: String? = null,
)

/** 播放统计概览（设置页展示） */
@Immutable
data class PlayStats(
    /** 播放过的歌曲数 */
    val listenedSongs: Int = 0,
    val totalPlays: Long = 0L,
    /** 累计收听时长（毫秒） */
    val totalListenedMs: Long = 0L,
    val favorites: Int = 0,
)

/**
 * 播放模式：把「随机」和「循环」两个开关归一成 4 个用户能理解的档位，
 * 共用播放页上的同一个按钮轮换，避免各占一个图标位。
 *
 * Media3 本身没有单一的“播放模式”，而是 `repeatMode`（关闭 / 列表 / 单曲）
 * 与 `shuffleModeEnabled` 两个独立开关；这里约定随机优先于循环，
 * 与主流播放器的表现一致。
 */
enum class PlayMode {
    /** 顺序播放：播完最后一首就停 */
    SEQUENTIAL,

    /** 列表循环 */
    REPEAT_ALL,

    /** 单曲循环 */
    REPEAT_ONE,

    /** 随机播放（乱序 + 列表循环） */
    SHUFFLE,
    ;

    /** 点击按钮后切换到下一档 */
    val next: PlayMode
        get() = when (this) {
            SEQUENTIAL -> REPEAT_ALL
            REPEAT_ALL -> REPEAT_ONE
            REPEAT_ONE -> SHUFFLE
            SHUFFLE -> SEQUENTIAL
        }

    /** 展示名，同时用作切换后的浮动提示文案 */
    val label: String
        get() = when (this) {
            SEQUENTIAL -> "顺序播放"
            REPEAT_ALL -> "列表循环"
            REPEAT_ONE -> "单曲循环"
            SHUFFLE -> "随机播放"
        }
}

/** 由 Media3 的 repeatMode + shuffle 两个开关推导当前档位（随机优先） */
fun playModeOf(repeatMode: Int, shuffle: Boolean): PlayMode = when {
    shuffle -> PlayMode.SHUFFLE
    repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
    repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
    else -> PlayMode.SEQUENTIAL
}

@Immutable
data class PlayerUiState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val lyrics: Lyrics = Lyrics.EMPTY,
    val lyricsLoading: Boolean = false,
    val lyricOffsetMs: Int = 0,
    val lyricScale: Float = 1f,
    val lyricWordByWord: Boolean = true,
    /** 车载模式：启动直进播放页 + 播放页 5 秒无操作进入沉浸态 */
    val carMode: Boolean = false,
    /** 桌面悬浮歌词：车机上退到桌面后仍在最上层显示当前歌词行 */
    val lyricOverlay: Boolean = false,
    /** 悬浮歌词是否处于「调整位置」模式（临时可拖动，会话级，不落盘） */
    val overlayEditing: Boolean = false,
    val playerExpanded: Boolean = false,
    val queueSize: Int = 0,
    // 批次 2 / 3
    val speed: Float = 1f,
    val sleepRemainingMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    // 批次 5 / 6
    val audioInfo: AudioInspector.Info? = null,
    val audioInfoLoading: Boolean = false,
    val notifyLyrics: Boolean = false,
    val skipSilence: Boolean = false,
)

/** 播放队列条目（队列抽屉用） */
@Immutable
data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val isCurrent: Boolean,
)

/**
 * 应用内更新的界面状态。
 *
 * 与 [PlayerUiState] 独立，因为更新流程的重组频率极低（只在用户操作时变），
 * 没必要混进播放状态里。
 */
@Immutable
data class UpdateUiState(
    /** 当前安装版本 */
    val currentVersion: String = "",
    /** 正在查询远端 */
    val checking: Boolean = false,
    /** 正在下载 APK */
    val downloading: Boolean = false,
    /** 下载进度 0..100；总长未知时为 -1 */
    val progress: Int = 0,
    /** 已下载 / 总字节，用于文案 */
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** 发现的新版本；为空表示暂无 */
    val info: com.luckylearning.windtone.data.UpdateInfo? = null,
    /** 是否弹出更新对话框（手动检查到新版本、或下载完成时置为 true） */
    val dialogVisible: Boolean = false,
    /** 一次性提示（成功 / 失败），展示后由界面调用 [MusicViewModel.consumeUpdateMessage] 清掉 */
    val message: String? = null,
    /** 下载完成、待安装的本地文件路径 */
    val readyApkPath: String? = null,
    /** 需要用户先授权「安装未知应用」 */
    val needInstallPermission: Boolean = false,
    /** 本次下载使用的线路（自动 / 直连 / 指定镜像），用于在弹窗里说明当前走哪条线 */
    val route: DownloadRoute = DownloadRoute.AUTO,
)

class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repository = MusicRepository(app, prefs)
    private val lyricsLoader = LyricsLoader(app)
    private val connection = PlaybackConnection(app)
    private val db = MusicDb.get(app)

    /** 当前正在跑的音乐库扫描任务；范围/目录变化时先取消它，避免过期结果回写 */
    private var scanJob: Job? = null

    private val _library = MutableStateFlow(
        LibraryUiState(
            sortOrder = prefs.sortOrder,
            view = LibraryView.from(prefs.libraryView),
            deepScan = repository.deepScanEnabled(),
            hasAudioPermission = false,
            searchHistory = prefs.searchHistory,
            scanScope = prefs.scanScope,
            folderCount = prefs.folderUris.size,
            folderUris = prefs.folderUris,
            webdavConfigured = prefs.webdavUrl.isNotEmpty(),
            webdavEnabled = prefs.webdavEnabled && prefs.webdavUrl.isNotEmpty(),
        )
    )
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _player = MutableStateFlow(
        PlayerUiState(
            lyricScale = prefs.lyricScale,
            lyricWordByWord = prefs.lyricWordByWord,
            carMode = prefs.carMode,
            lyricOverlay = prefs.lyricOverlay,
            // 车载模式：启动直接进播放页（跳过库主页）
            playerExpanded = prefs.carMode,
            shuffle = prefs.shuffle,
            speed = prefs.playbackSpeed,
            notifyLyrics = prefs.notifyLyrics,
            skipSilence = prefs.skipSilence,
        )
    )
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    /** 高频播放进度：独立于 [_player]，避免每 250ms 触发整界面重组 */
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** 主题取色（当前封面主色；dynamicTheme 关闭时为 null） */
    private val _themeSeed = MutableStateFlow<Int?>(null)
    val themeSeed: StateFlow<Int?> = _themeSeed.asStateFlow()

    /** 应用内更新状态 */
    private val _update = MutableStateFlow(
        UpdateUiState(
            currentVersion = UpdateChecker.currentVersion,
            route = prefs.updateDownloadRoute,
        )
    )
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    private var controller: MediaController? = null
    private var currentQueue: List<Song> = emptyList()
    private var lyricsJob: Job? = null
    private var artworkJob: Job? = null
    private var positionJob: Job? = null
    private var searchJob: Job? = null
    private var webdavScanJob: Job? = null
    private var currentLyricsSong: String? = null
    private var consecutiveErrors = 0

    /** WebDAV 云端歌曲（内存态，不入扫描缓存——冷启动靠重新扫描恢复） */
    private var webdavSongs: List<Song> = emptyList()

    // 收听时长统计：仅在播放中累加，切歌/暂停时结算
    private var listenForKey: String? = null
    private var listenAccumMs = 0L
    private var lastTickAt = 0L

    // 通知栏歌词：记录已推送的行号，避免重复刷新媒体项
    private var lastPushedLyricIndex = Int.MIN_VALUE

    // 播放计数去重
    private var lastCountedMediaId: String? = null
    private var lastCountedAt = 0L

    init {
        AudioFx.init(app)
        viewModelScope.launch {
            SleepTimer.remainingMs.collect { ms ->
                _player.update { it.copy(sleepRemainingMs = ms) }
            }
        }
        loadFromCacheThenScan()
        maybeAutoCheckUpdate()
    }

    // ================================================================ 音乐库

    /** 冷启动：先展示 Room 缓存（秒开），再按已选范围后台刷新；未选范围则完全不扫描 */
    private fun loadFromCacheThenScan() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                db.songCacheDao().all().map { it.toSong() }
            }
            // 老版本升级上来的用户：已有扫描缓存但没有范围记录，视作「全盘」，
            // 保持原有行为；只有全新安装（无缓存）才会停在「等待选择范围」。
            // 注意必须用 scanScopeChosen 判定：用户「主动清空目录」后范围也是 UNSET，
            // 那种情况绝不能被这条兼容逻辑拽回全盘扫描。
            if (prefs.scanScope == ScanScope.UNSET && !prefs.scanScopeChosen && cached.isNotEmpty()) {
                prefs.scanScope = ScanScope.FULL
                prefs.scanScopeChosen = true
                _library.update { it.copy(scanScope = ScanScope.FULL) }
            }
            if (cached.isNotEmpty()) {
                applyLibrary(cached, skipped = 0, fromCache = true)
                restoreQueueIfNeeded()
                // 缓存展示后延迟少许再扫描，避免抢启动帧
                delay(600)
            }
            if (prefs.scanScope == ScanScope.UNSET) {
                // 首次安装：不自动扫描，界面会提示用户选择范围
                _library.update { it.copy(scanning = false, statusText = "") }
                maybeScanWebDav(auto = true)
                return@launch
            }
            scan(auto = true)
            maybeScanWebDav(auto = true)
        }
    }

    /**
     * 选择「扫描全盘」。
     * 媒体权限的申请由界面层负责，授权成功后调用本方法。
     */
    fun startFullScan() {
        prefs.scanScope = ScanScope.FULL
        prefs.scanScopeChosen = true
        _library.update { it.copy(scanScope = ScanScope.FULL) }
        scan()
    }

    /**
     * 切换扫描范围。
     * [ScanScope.FOLDERS] 需要至少有一个已导入目录，否则界面应先去选目录。
     */
    fun setScanScope(scope: ScanScope) {
        if (scope == ScanScope.FOLDERS && prefs.folderUris.isEmpty()) return
        prefs.scanScope = scope
        prefs.scanScopeChosen = true
        cancelScan()
        _library.update {
            it.copy(scanScope = scope, folderCount = prefs.folderUris.size, folderUris = prefs.folderUris)
        }
        if (scope != ScanScope.UNSET) scan() else clearLibraryForScopeReset()
    }

    /** 是否已导入过音乐目录 */
    fun hasFolders(): Boolean = prefs.folderUris.isNotEmpty()

    /** 最短时长阈值（秒），0 表示不过滤 */
    fun minSongDurationSec(): Int = prefs.minSongDurationSec

    /**
     * 修改短音频过滤阈值并立即重新扫描生效。
     * 时长未知（0）的歌曲不会被过滤，播放拿到真实时长后由下次扫描接管。
     */
    fun setMinSongDurationSec(sec: Int) {
        prefs.minSongDurationSec = sec
        if (prefs.scanScope != ScanScope.UNSET) scan()
    }

    fun scan(auto: Boolean = false) {
        if (_library.value.scanning) return
        if (_library.value.scanScope == ScanScope.UNSET) return
        // 同一时刻只保留最后一次扫描：范围/目录被改动后，旧扫描的结果必须作废，
        // 否则它会把「已经清空的曲库」重新写回内存与 Room 缓存（见 removeFolder）。
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val scopeAtStart = prefs.scanScope
            _library.update {
                it.copy(
                    scanning = true,
                    statusText = "准备扫描…",
                    message = null,
                    folderCount = prefs.folderUris.size,
                    folderUris = prefs.folderUris,
                )
            }
            val result = try {
                repository.loadLibrary { text -> _library.update { it.copy(statusText = text) } }
            } catch (e: CancellationException) {
                // 扫描被新范围/移除目录打断：交给 cancelScan() 收尾，不报错
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "扫描失败", e)
                null
            }

            if (result == null) {
                _library.update {
                    it.copy(scanning = false, statusText = "", message = if (auto) "" else "扫描失败，请检查权限后重试")
                }
                return@launch
            }

            // 扫描期间范围/目录又变了（例如用户把最后一个目录也移除了）：结果作废
            val scopeNow = prefs.scanScope
            if (scopeNow == ScanScope.UNSET || scopeNow != scopeAtStart) {
                Log.i(TAG, "扫描结果已过期（$scopeAtStart → $scopeNow），丢弃")
                return@launch
            }

            applyLibrary(result.songs, result.skippedUnsupported, fromCache = false)
            withContext(Dispatchers.IO) {
                db.songCacheDao().clear()
                db.songCacheDao().insertAll(result.songs.map { SongCacheEntity.from(it) })
            }
            restoreQueueIfNeeded()
            if (!auto && result.songs.isEmpty()) {
                pushMessage(
                    when (scopeNow) {
                        ScanScope.FOLDERS -> "所选目录里没有找到音频文件"
                        else -> "没有找到可播放的音频，可授予「所有文件访问权限」后重试，或改为导入指定的音乐目录"
                    }
                )
            }
        }
    }

    /**
     * 取消正在进行的扫描并复位状态。
     * 目录列表、扫描范围发生任何变化时都要先调用，避免旧扫描把过期结果写回曲库/缓存。
     */
    private fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _library.update { if (!it.scanning) it else it.copy(scanning = false, statusText = "") }
    }

    /**
     * 回到「等待选择扫描范围」：清空曲库与扫描缓存。
     * 只清本地扫描结果，**不动** WebDAV 云端歌曲与用户数据（收藏/歌单/统计）。
     */
    private fun clearLibraryForScopeReset() {
        PinyinIndex.build(emptyList())
        currentQueue = emptyList()
        _library.update {
            it.copy(
                scanScope = ScanScope.UNSET,
                folderCount = prefs.folderUris.size,
                folderUris = prefs.folderUris,
                songs = emptyList(),
                visible = emptyList(),
                letterIndex = emptyMap(),
                groups = emptyList(),
                selectedGroup = null,
                detailSongs = emptyList(),
                skippedUnsupported = 0,
                lyricsCount = 0,
                scanning = false,
                statusText = "",
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.songCacheDao().clear() }
        }
        refreshGroups()
    }

    /** 应用歌曲列表：信息覆盖 → 排序 → 拼音索引 → 分组 → 状态落库 */
    private suspend fun applyLibrary(raw: List<Song>, skipped: Int, fromCache: Boolean) {
        // WebDAV 云端歌曲与本地歌曲合并进同一个曲库，统一排序 / 搜索 / 分组；
        // 云端曲目靠 Song.source 区分，文件夹视图里按其服务器目录自然成组。
        val rawAll = if (webdavSongs.isNotEmpty()) raw + webdavSongs else raw
        val overrides = withContext(Dispatchers.IO) { db.overrideDao().all() }
        val oMap = overrides.associateBy { it.songUri }
        val songs = rawAll.map { song ->
            val o = oMap[song.stableKey]
            if (o == null) song else song.copy(
                title = o.title?.takeIf { it.isNotBlank() } ?: song.title,
                artist = o.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                album = o.album?.takeIf { it.isNotBlank() } ?: song.album,
            )
        }
        val sorted = repository.sort(songs, prefs.sortOrder)

        withContext(Dispatchers.Default) { PinyinIndex.build(sorted) }

        val stats = withContext(Dispatchers.IO) { db.playStatDao().all() }
        val favs = stats.filter { it.favorite }.map { it.songUri }.toSet()

        // 重活全部在进入 update 之前算完：
        // update 的 lambda 走 CAS 重试，若在里面做全量搜索会被重复执行。
        val query = _library.value.query
        val visible = withContext(Dispatchers.Default) { searchSongs(sorted, query) }
        val letters = withContext(Dispatchers.Default) { buildLetterIndex(visible) }

        _library.update {
            it.copy(
                songs = sorted,
                visible = visible,
                letterIndex = letters,
                scanning = false,
                scannedOnce = true,
                skippedUnsupported = skipped,
                lyricsCount = sorted.count { s -> s.lrcUri != null },
                deepScan = repository.deepScanEnabled(),
                scanScope = prefs.scanScope,
                folderCount = prefs.folderUris.size,
                folderUris = prefs.folderUris,
                statusText = "",
                favoriteUris = favs,
                webdavSongCount = webdavSongs.size,
            )
        }
        currentQueue = sorted
        refreshGroups()
        refreshPlaylists()
    }

    private fun searchSongs(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            repository.matches(song, q) ||
                PinyinIndex.full(song.stableKey).contains(q) ||
                PinyinIndex.initials(song.stableKey).contains(q)
        }
    }

    /** 首字母 → 首个下标（A-Z 索引导航）；用 LinkedHashMap 保持字母顺序 */
    private fun buildLetterIndex(list: List<Song>): Map<Char, Int> {
        if (list.isEmpty()) return emptyMap()
        val map = LinkedHashMap<Char, Int>()
        list.forEachIndexed { i, song ->
            val ch = PinyinIndex.initialOf(song.title)
            if (!map.containsKey(ch)) map[ch] = i
        }
        return map
    }

    /**
     * 搜索输入。
     * 输入框即时回显，但列表过滤延迟 140ms —— 原先每按一个键就要对全库做一次
     * O(n) 过滤（外加每首两次拼音查表）并重建字母索引，长库输入明显发涩。
     */
    fun setQuery(q: String) {
        _library.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(140L)
            val songs = _library.value.songs
            val (visible, letters) = withContext(Dispatchers.Default) {
                val v = searchSongs(songs, q)
                v to buildLetterIndex(v)
            }
            _library.update { it.copy(visible = visible, letterIndex = letters) }
        }
    }

    fun setSort(order: SortOrder) {
        prefs.sortOrder = order
        val sorted = repository.sort(_library.value.songs, order)
        val visible = searchSongs(sorted, _library.value.query)
        _library.update {
            it.copy(
                sortOrder = order,
                songs = sorted,
                visible = visible,
                letterIndex = buildLetterIndex(visible),
            )
        }
        currentQueue = sorted
        refreshGroups()
    }

    // ---------------------------------------------------------- 视图切换

    fun setView(view: LibraryView) {
        prefs.libraryView = view.name
        _library.update {
            it.copy(view = view, selectedGroup = null, selectedPlaylist = null, detailSongs = emptyList())
        }
        if (view == LibraryView.ALBUM || view == LibraryView.ARTIST || view == LibraryView.FOLDER) {
            refreshGroups()
        }
        if (view == LibraryView.PLAYLIST) refreshPlaylists()
    }

    private fun refreshGroups() {
        val view = _library.value.view
        val songs = _library.value.songs
        if (songs.isEmpty()) {
            _library.update { if (it.groups.isEmpty()) it else it.copy(groups = emptyList()) }
            return
        }
        val groups: List<GroupInfo> = when (view) {
            LibraryView.ALBUM -> songs.groupBy { it.albumOrUnknown }.map { (k, list) ->
                GroupInfo(k, k, list.firstOrNull()?.artistOrUnknown.orEmpty(), list.size, list.first())
            }
            LibraryView.ARTIST -> songs.groupBy { it.artistOrUnknown }.map { (k, list) ->
                GroupInfo(k, k, "${list.size} 首", list.size, list.first())
            }
            // 文件夹视图只列真实存在的目录：拿不到目录路径的歌曲（媒体库没给 path）
            // 会被归到「未知目录」，这类分组没有可操作的对象，直接不展示。
            // 分组键用完整路径（两个同名目录不会被合并），标题只显示**最后一个文件夹名字**
            // ——全盘扫描下 path 是 /storage/emulated/0/Music/华语，页面上只显示「华语」。
            LibraryView.FOLDER -> songs.groupBy { it.folderLabel }
                .filterKeys { it != UNKNOWN_FOLDER_LABEL }
                .map { (k, list) ->
                    GroupInfo(k, folderDisplayName(k), "${list.size} 首", list.size, list.first())
                }
            else -> emptyList()
        }.sortedWith(compareBy({ PinyinIndex.initialOf(it.title) }, { it.title.lowercase() }))
        _library.update { if (it.groups == groups) it else it.copy(groups = groups) }
    }

    fun openGroup(group: GroupInfo) {
        val songs = when (_library.value.view) {
            LibraryView.ALBUM -> _library.value.songs
                .filter { it.albumOrUnknown == group.key }
                .sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
            LibraryView.ARTIST -> _library.value.songs
                .filter { it.artistOrUnknown == group.key }
                .sortedWith(compareBy({ it.albumOrUnknown.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
            else -> _library.value.songs
                .filter { it.folderLabel == group.key }
                .sortedWith(compareBy { it.displayName.lowercase() })
        }
        _library.update { it.copy(selectedGroup = group, detailSongs = songs) }
    }

    fun closeGroup() = _library.update { it.copy(selectedGroup = null, detailSongs = emptyList()) }

    // ---------------------------------------------------------- 歌单

    private fun refreshPlaylists() {
        viewModelScope.launch { refreshPlaylistsNow() }
    }

    /**
     * 重新计算歌单列表。
     *
     * 原实现对每个歌单单独查一次关联表（N+1），且重复构建了两次全库 Map。
     * 现在改成单条 SQL 取回全部关联行 + 内存分组，Map 只建一次。
     */
    private suspend fun refreshPlaylistsNow() {
        val entities = withContext(Dispatchers.IO) { db.playlistDao().all() }
        val links = withContext(Dispatchers.IO) { db.playlistDao().allSongs() }
        val songsByUri = _library.value.songs.associateBy { it.stableKey }
        val byPlaylist = links.groupBy { it.playlistId }

        val infos = entities.map { p ->
            val songs = byPlaylist[p.id].orEmpty().mapNotNull { songsByUri[it.songUri] }
            PlaylistInfo(p.id, p.name, songs.size, songs.firstOrNull())
        }
        val stats = withContext(Dispatchers.IO) { db.playStatDao().all() }
        val recent = stats.asSequence()
            .filter { it.lastPlayedAt > 0 }
            .sortedByDescending { it.lastPlayedAt }
            .take(100)
            .mapNotNull { songsByUri[it.songUri] }
            .toList()
        val most = stats.asSequence()
            .filter { it.playCount > 0 }
            .sortedByDescending { it.playCount }
            .take(100)
            .mapNotNull { songsByUri[it.songUri] }
            .toList()
        _library.update {
            it.copy(
                playlists = infos,
                extraRecent = recent,
                extraMostPlayed = most,
            )
        }
        refreshStats()
    }

    fun openPlaylist(playlist: PlaylistInfo) {
        viewModelScope.launch {
            val songs: List<Song> = when {
                playlist.virtual && playlist.id == VIRTUAL_RECENT -> _library.value.extraRecent
                playlist.virtual && playlist.id == VIRTUAL_MOST -> _library.value.extraMostPlayed
                else -> {
                    val uris = withContext(Dispatchers.IO) { db.playlistDao().songsOf(playlist.id) }
                    val byUri = _library.value.songs.associateBy { it.stableKey }
                    uris.mapNotNull { byUri[it.songUri] }
                }
            }
            _library.update { it.copy(selectedPlaylist = playlist, playlistSongs = songs) }
        }
    }

    fun closePlaylist() = _library.update { it.copy(selectedPlaylist = null, playlistSongs = emptyList()) }

    fun createPlaylist(name: String, initialSongs: List<Song> = emptyList(), onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                val pid = db.playlistDao().insert(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
                initialSongs.forEachIndexed { i, s ->
                    db.playlistDao().insertSong(PlaylistSongEntity(pid, s.stableKey, i, System.currentTimeMillis()))
                }
                pid
            }
            refreshPlaylists()
            pushMessage("已创建歌单「$name」")
            onCreated(id)
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().rename(id, name.trim()) }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == id }?.let {
                _library.update { s -> s.copy(selectedPlaylist = it.copy(name = name.trim())) }
            }
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.playlistDao().deleteSongsOf(id)
                db.playlistDao().delete(id)
            }
            closePlaylist()
            refreshPlaylists()
            pushMessage("已删除歌单")
        }
    }

    fun addToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val pos = (db.playlistDao().maxPosition(playlistId) ?: -1) + 1
                db.playlistDao().insertSong(PlaylistSongEntity(playlistId, song.stableKey, pos, System.currentTimeMillis()))
            }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == playlistId }?.let { openPlaylist(it) }
            pushMessage("已加入歌单")
        }
    }

    fun removeFromPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().removeSong(playlistId, song.stableKey) }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == playlistId }?.let { openPlaylist(it) }
        }
    }

    // ---------------------------------------------------------- 删除歌曲文件

    /** 等待系统删除确认（Android 11+ 媒体库文件）结果的挂起歌曲 */
    private var pendingDelete: Song? = null

    /**
     * 删除歌曲文件。
     *
     * Android 11+ 的媒体库文件会返回一个 [IntentSender]，需要 UI 层启动系统确认框，
     * 用户同意后系统代删，结果经 [onSystemDeleteResult] 回来；
     * 其余来源（SAF 文档 / 深度扫描路径 / 旧版媒体库）直接在 IO 线程删。
     */
    fun deleteSong(song: Song, launchSystemConfirm: (android.content.IntentSender) -> Unit) {
        // 云端文件不属于本机存储：不删服务器文件，也不从曲库清（下次扫描会回来）
        if (song.source == com.luckylearning.windtone.data.model.SongSource.WEBDAV) {
            pushMessage("云端歌曲不在这里删除；请在 WebDAV 服务器上管理文件")
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            val sender = withContext(Dispatchers.IO) {
                runCatching { SongDeleter.prepareSystemConfirm(app, song) }.getOrNull()
            }
            if (sender != null) {
                pendingDelete = song
                launchSystemConfirm(sender)
            } else {
                val ok = withContext(Dispatchers.IO) { SongDeleter.deleteNow(app, song) }
                afterDelete(song, ok)
            }
        }
    }

    /** 系统确认框关闭后的回调：resultOk 为 true 表示系统已删除文件 */
    fun onSystemDeleteResult(resultOk: Boolean) {
        val song = pendingDelete
        pendingDelete = null
        if (song != null) afterDelete(song, resultOk)
    }

    /** 文件已删（或删除失败）后：清播放队列、清缓存与统计联动、更新界面 */
    private fun afterDelete(song: Song, ok: Boolean) {
        if (!ok) {
            pushMessage("删除「${song.title}」失败：存储拒绝了该操作")
            return
        }
        viewModelScope.launch {
            val key = song.stableKey
            // 1) 从播放队列里移除；若删的是当前曲目，ExoPlayer 会自动跳到下一首
            val c = controller
            if (c != null) {
                var i = c.mediaItemCount - 1
                while (i >= 0) {
                    if (c.getMediaItemAt(i).mediaId == key) c.removeMediaItem(i)
                    i--
                }
                saveQueue()
            }
            // 2) 清缓存与关联数据（扫描缓存 / 歌单关联 / 显示覆盖 / 封面缓存）
            withContext(Dispatchers.IO) {
                db.songCacheDao().delete(key)
                db.playlistDao().removeSongEverywhere(key)
                db.overrideDao().delete(key)
                CoverRepository.evict(key)
            }
            // 3) 更新内存状态（songs 本身已排序，过滤后顺序不变，无需重排）
            val songs = _library.value.songs.filterNot { it.stableKey == key }
            currentQueue = currentQueue.filterNot { it.stableKey == key }
            val query = _library.value.query
            val visible = searchSongs(songs, query)
            _library.update {
                it.copy(
                    songs = songs,
                    visible = visible,
                    letterIndex = buildLetterIndex(visible),
                    detailSongs = it.detailSongs.filterNot { s -> s.stableKey == key },
                    playlistSongs = it.playlistSongs.filterNot { s -> s.stableKey == key },
                    extraRecent = it.extraRecent.filterNot { s -> s.stableKey == key },
                    extraMostPlayed = it.extraMostPlayed.filterNot { s -> s.stableKey == key },
                )
            }
            refreshGroups()
            refreshPlaylists()
            pushMessage("已删除「${song.title}」")
        }
    }

    /**
     * 移除文件夹视图中某个分组下的全部扫描结果。
     *
     * 只从应用内部状态与数据库中清除，**不会删除存储中的原始音频文件**。
     * 会从播放队列、歌单、收藏、播放统计、歌词/封面缓存中一并清理。
     */
    fun removeFolderScanResults(group: GroupInfo) {
        if (_library.value.view != LibraryView.FOLDER) return
        val key = group.key
        val removed = _library.value.songs.filter { it.folderLabel == key }
        if (removed.isEmpty()) return
        viewModelScope.launch {
            // 1) 从播放器队列移除
            val c = ensureController()
            if (c != null) {
                val removedKeys = removed.map { it.stableKey }.toSet()
                var i = c.mediaItemCount - 1
                while (i >= 0) {
                    if (c.getMediaItemAt(i).mediaId in removedKeys) c.removeMediaItem(i)
                    i--
                }
                saveQueue()
            }
            // 2) 清数据库与缓存
            withContext(Dispatchers.IO) {
                removed.forEach { song ->
                    db.songCacheDao().delete(song.stableKey)
                    db.playlistDao().removeSongEverywhere(song.stableKey)
                    db.overrideDao().delete(song.stableKey)
                    db.playStatDao().delete(song.stableKey)
                    CoverRepository.evict(song.stableKey)
                }
            }
            // 3) 更新内存状态
            val removedKeys = removed.map { it.stableKey }.toSet()
            val songs = _library.value.songs.filterNot { it.stableKey in removedKeys }
            currentQueue = currentQueue.filterNot { it.stableKey in removedKeys }
            val query = _library.value.query
            val visible = searchSongs(songs, query)
            _library.update {
                it.copy(
                    songs = songs,
                    visible = visible,
                    letterIndex = buildLetterIndex(visible),
                    selectedGroup = if (it.selectedGroup?.key == key) null else it.selectedGroup,
                    detailSongs = if (it.selectedGroup?.key == key) emptyList() else it.detailSongs.filterNot { s -> s.stableKey in removedKeys },
                    playlistSongs = it.playlistSongs.filterNot { s -> s.stableKey in removedKeys },
                    extraRecent = it.extraRecent.filterNot { s -> s.stableKey in removedKeys },
                    extraMostPlayed = it.extraMostPlayed.filterNot { s -> s.stableKey in removedKeys },
                )
            }
            refreshGroups()
            refreshPlaylists()
            refreshStats()
            pushMessage("已移除「${group.title}」的 ${removed.size} 首歌曲")
        }
    }

    // ---------------------------------------------------------- WebDAV 云盘

    /**
     * 连接 WebDAV 服务器并开始首次扫描。
     *
     * 先用 PROPFIND(Depth:0) 试连一次：地址打错、密码不对都在这一步拦住，
     * 不让用户等一轮全库扫描之后才发现连不上。
     */
    fun connectWebDav(url: String, user: String, password: String) {
        if (_library.value.webdavScanning) return
        viewModelScope.launch {
            val raw = url.trim()
            if (raw.isEmpty()) {
                _library.update { it.copy(webdavMessage = "请填写服务器地址") }
                return@launch
            }
            val normalized = when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw.trimEnd('/')
                else -> "https://$raw".trimEnd('/')
            }
            _library.update { it.copy(webdavScanning = true, webdavMessage = "正在连接…") }
            val test = withContext(Dispatchers.IO) {
                WebDavClient.testConnection(normalized, user.trim().ifBlank { null }, password.ifBlank { null })
            }
            test.onSuccess {
                prefs.webdavUrl = normalized
                prefs.webdavUser = user.trim()
                prefs.webdavPassword = password
                prefs.webdavRoot = ""
                prefs.webdavEnabled = true
                WebDavClient.config = WebDavClient.Config(normalized, user.trim().ifBlank { null }, password.ifBlank { null })
                _library.update {
                    it.copy(webdavConfigured = true, webdavEnabled = true, webdavScanning = false, webdavMessage = "已连接")
                }
                scanWebDav()
            }.onFailure { e ->
                _library.update {
                    it.copy(webdavScanning = false, webdavMessage = "连接失败：${e.message ?: "请检查地址与网络"}")
                }
            }
        }
    }

    /** 手动触发一次 WebDAV 扫描（设置页「重新扫描」） */
    fun scanWebDav() = maybeScanWebDav(auto = false)

    /**
     * 扫描 WebDAV 目录并合并进曲库。
     * [auto] 为 true 时仅在「已配置且启用」的情况下静默执行，失败不打扰用户。
     */
    private fun maybeScanWebDav(auto: Boolean) {
        if (webdavScanJob?.isActive == true) return
        val url = prefs.webdavUrl
        if (url.isEmpty() || !prefs.webdavEnabled) return
        val user = prefs.webdavUser.ifBlank { null }
        val pass = prefs.webdavPassword.ifBlank { null }
        WebDavClient.config = WebDavClient.Config(url, user, pass)
        webdavScanJob = viewModelScope.launch {
            _library.update { it.copy(webdavScanning = true, webdavMessage = null) }
            val result = runCatching {
                WebDavScanner.scan(url, prefs.webdavRoot) { text ->
                    _library.update { if (it.webdavMessage == text) it else it.copy(webdavMessage = text) }
                }
            }
            result.onSuccess { r ->
                // 与本地扫描同规则：剔除时长已知且低于最短时长阈值的云端曲目
                val minMs = prefs.minSongDurationSec * 1000L
                webdavSongs = if (minMs > 0L) r.songs.filterNot { it.durationMs in 1 until minMs } else r.songs
                val note = buildString {
                    append("已找到 ${webdavSongs.size} 首")
                    if (r.truncated) append("（已达扫描上限）")
                }
                _library.update { it.copy(webdavScanning = false, webdavMessage = note) }
                // 以「当前本地曲库 + 新云端曲目」重建曲库视图
                applyLibrary(localSongsOnly(), _library.value.skippedUnsupported, fromCache = false)
            }.onFailure { e ->
                Log.w(TAG, "WebDAV 扫描失败", e)
                webdavSongs = emptyList()
                _library.update {
                    it.copy(
                        webdavScanning = false,
                        webdavSongCount = 0,
                        webdavMessage = if (auto) null else "扫描失败：${e.message ?: "请检查网络"}",
                    )
                }
                if (!auto) applyLibrary(localSongsOnly(), _library.value.skippedUnsupported, fromCache = false)
            }
        }
    }

    /** 断开并清除 WebDAV 配置；云端歌曲立即从曲库移除（收藏/歌单记录保留在数据库里） */
    fun disconnectWebDav() {
        webdavScanJob?.cancel()
        webdavScanJob = null
        webdavSongs = emptyList()
        WebDavClient.config = null
        prefs.webdavUrl = ""
        prefs.webdavUser = ""
        prefs.webdavPassword = ""
        prefs.webdavRoot = ""
        prefs.webdavEnabled = false
        _library.update {
            it.copy(webdavConfigured = false, webdavEnabled = false, webdavScanning = false, webdavSongCount = 0, webdavMessage = null)
        }
        viewModelScope.launch {
            applyLibrary(localSongsOnly(), _library.value.skippedUnsupported, fromCache = false)
        }
    }

    /** 当前曲库中的本地曲目（去掉 WebDAV 源），用于云端扫描后重建合并视图 */
    private fun localSongsOnly(): List<Song> =
        _library.value.songs.filter { it.source != com.luckylearning.windtone.data.model.SongSource.WEBDAV }

    // ---------------------------------------------------------- 收藏

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val stat = withContext(Dispatchers.IO) { db.playStatDao().byUri(song.stableKey) }
            val newFav = !(stat?.favorite ?: false)
            withContext(Dispatchers.IO) {
                db.playStatDao().insert(
                    (stat ?: com.luckylearning.windtone.data.db.PlayStatEntity(songUri = song.stableKey))
                        .copy(favorite = newFav)
                )
            }
            // 一次 update 完成增删，避免先加后减造成两次重组
            _library.update {
                val next = if (newFav) it.favoriteUris + song.stableKey else it.favoriteUris - song.stableKey
                it.copy(favoriteUris = next)
            }
            pushMessage(if (newFav) "已收藏「${song.title}」" else "已取消收藏")
        }
    }

    // ---------------------------------------------------------- 文件夹 / 歌词 / 权限

    fun addFolder(uri: Uri, folderScopeOnly: Boolean = false) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "持久化文件夹权限失败", it) }
        // 写权限是「文件夹视图删除歌曲文件」的前提，拿不到也不影响读
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        val list = prefs.folderUris.toMutableList()
        if (!list.contains(uri.toString())) list.add(uri.toString())
        prefs.folderUris = list
        // 首次导入目录即视为选定「指定目录」范围；用户明确选择「只看指定目录」时也强制切换
        if (folderScopeOnly || prefs.scanScope == ScanScope.UNSET) {
            prefs.scanScope = ScanScope.FOLDERS
            prefs.scanScopeChosen = true
        }
        _library.update {
            it.copy(
                scanScope = prefs.scanScope,
                folderCount = prefs.folderUris.size,
                folderUris = prefs.folderUris,
            )
        }
        scan()
    }

    /**
     * 移除一个已导入目录。
     *
     * 两条硬规则：
     *  1. **绝不因为移除目录而自动扫全盘**。以往在「全盘扫描」范围下移除目录会走
     *     `scan()`，也就是把整块存储重扫一遍，又慢又没道理；现在只更新配置。
     *  2. 移除最后一个目录时，如果当前范围是「指定目录」，范围回退到「未选择」，
     *     清空曲库与扫描缓存并提示重新选择（同样不扫描任何位置）。
     */
    fun removeFolder(uriString: String) {
        val list = prefs.folderUris.toMutableList()
        if (!list.remove(uriString)) return
        prefs.folderUris = list
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        // 目录配置变了：先作废正在跑的扫描，避免它把过期结果写回曲库/缓存
        cancelScan()

        // 立刻把「这个目录」带来的歌从曲库摘掉（只动应用内数据，不碰存储里的文件）
        val stale = songsFromTree(uriString)
        if (stale.isNotEmpty()) dropSongs(stale)

        _library.update { it.copy(folderCount = list.size, folderUris = list) }

        if (list.isEmpty()) {
            if (prefs.scanScope == ScanScope.FOLDERS) {
                prefs.scanScope = ScanScope.UNSET
                prefs.scanScopeChosen = true
                clearLibraryForScopeReset()
                pushMessage("已移除全部目录，请重新选择扫描范围")
            } else {
                // 全盘扫描范围：曲库由系统媒体库决定，导入的目录只是附加来源，无需重扫
                pushMessage("已移除全部目录")
            }
            return
        }

        when (prefs.scanScope) {
            // 「指定目录」范围里目录就是唯一来源，剩下的目录要重扫（只扫这几个，很快）
            ScanScope.FOLDERS -> scan()
            // 「全盘扫描」范围：为了一个目录重扫整块盘不值得，等用户手动重扫
            else -> pushMessage("已移除目录「${folderDisplayName(uriString)}」")
        }
    }

    /**
     * 找出曲库里来自某个 SAF 树目录的歌曲。
     * 用文档 ID 前缀比对（`primary:Music/a.mp3` 是否在 `primary:Music` 下），
     * 这样能精确到「这一棵导入的目录树」，不会误伤同名的其他目录。
     */
    private fun songsFromTree(treeUri: String): List<Song> {
        val treeDocId = docIdOf(treeUri) ?: return emptyList()
        return _library.value.songs.filter { song ->
            val docId = docIdOf(song.uri.toString())
            docId != null && (docId == treeDocId || docId.startsWith("$treeDocId/"))
        }
    }

    /** 从内存曲库与扫描缓存中摘掉指定歌曲（不删除存储中的文件、不碰收藏/歌单/统计） */
    private fun dropSongs(removed: List<Song>) {
        val keys = removed.map { it.stableKey }.toSet()
        currentQueue = currentQueue.filterNot { it.stableKey in keys }
        val songs = _library.value.songs.filterNot { it.stableKey in keys }
        val query = _library.value.query
        val visible = searchSongs(songs, query)
        _library.update {
            it.copy(
                songs = songs,
                visible = visible,
                letterIndex = buildLetterIndex(visible),
                lyricsCount = songs.count { s -> s.lrcUri != null },
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { keys.forEach { db.songCacheDao().delete(it) } }
        }
        refreshGroups()
    }

    fun folderUris(): List<String> = prefs.folderUris

    /** 重新为缺少歌词文件的歌曲匹配同目录 .lrc */
    fun refreshLyrics() {
        viewModelScope.launch {
            _library.update { it.copy(statusText = "正在重新匹配歌词…") }
            val updated = repository.refreshSidecarLyrics(_library.value.songs)
            currentQueue = updated
            val query = _library.value.query
            val visible = searchSongs(updated, query)
            _library.update {
                it.copy(
                    songs = updated,
                    visible = visible,
                    letterIndex = buildLetterIndex(visible),
                    lyricsCount = updated.count { s -> s.lrcUri != null },
                    statusText = "",
                )
            }
            pushMessage("已重新匹配歌词文件：${updated.count { it.lrcUri != null }} 首")
        }
    }

    fun deepScanSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", getApplication<Application>().packageName, null)
            }
        } else null

    fun hasAllFilesAccess(): Boolean = repository.deepScanEnabled()

    fun setAudioPermission(granted: Boolean) {
        _library.update { it.copy(hasAudioPermission = granted) }
    }

    /** 用户拒绝音频读取权限时的提示（不影响「指定目录」方式） */
    fun notifyScanPermissionDenied() {
        pushMessage("没有读取音频的权限。可以改为「选择音乐目录」，用文件选择器授权指定文件夹。")
    }

    fun updateDeepScanFlag() {
        _library.update { it.copy(deepScan = repository.deepScanEnabled()) }
    }

    fun clearMessage() = _library.update { it.copy(message = null) }

    private fun pushMessage(text: String) {
        if (text.isNotEmpty()) _library.update { it.copy(message = text) }
    }

    // ================================================================ 播放

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _player.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            _player.update { it.copy(durationMs = c.duration.coerceAtLeast(0L)) }
            _progress.update { it.copy(bufferedMs = c.bufferedPosition.coerceAtLeast(0L)) }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val c = controller ?: return
            _player.update {
                it.copy(
                    durationMs = c.duration.coerceAtLeast(0L),
                    queueSize = c.mediaItemCount,
                )
            }
            _progress.update {
                it.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = c.duration.coerceAtLeast(0L),
                )
            }
            consecutiveErrors = 0
            syncCurrentSong()
            mediaItem?.mediaId?.takeIf { shouldCountPlay(it) }?.let { recordPlay(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _player.update { it.copy(repeatMode = repeatMode) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            prefs.shuffle = shuffleModeEnabled
            _player.update { it.copy(shuffle = shuffleModeEnabled) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "播放错误: ${error.errorCodeName}", error)
            val name = _player.value.current?.displayName ?: ""
            _player.update { it.copy(isPlaying = false) }
            consecutiveErrors++
            val c = controller
            val count = c?.mediaItemCount ?: 0
            if (count > 1 && consecutiveErrors < count) {
                pushMessage("播放失败：$name，已跳到下一首")
                c?.seekToNextMediaItem()
                c?.play()
            } else {
                pushMessage("播放失败：$name")
            }
        }
    }

    /**
     * 是否把这次曲目切换计为一次播放。
     *
     * `replaceMediaItem`（回填封面 / 下发通知栏歌词）同样会触发 onMediaItemTransition，
     * 若不加判别，每首歌会被多计若干次播放。同一首歌在 15 秒内的重复切换视为非用户行为。
     */
    private fun shouldCountPlay(mediaId: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (mediaId == lastCountedMediaId && now - lastCountedAt < 15_000L) return false
        lastCountedMediaId = mediaId
        lastCountedAt = now
        return true
    }

    /**
     * 记录一次播放。
     * 必须先 ensureRow 再 bump——历史实现只有 UPDATE，没收藏过的歌永远写不进统计表。
     */
    private fun recordPlay(mediaId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.playStatDao().ensureRow(mediaId)
                db.playStatDao().bump(mediaId, System.currentTimeMillis(), 0L)
            }
        }
    }

    /** 把累计的收听时长写回数据库（切歌 / 暂停 / 定时落盘时调用） */
    private fun flushListen() {
        val key = listenForKey ?: return
        val ms = listenAccumMs
        listenAccumMs = 0L
        if (ms < 1000L) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.playStatDao().ensureRow(key)
                db.playStatDao().addPlayedMs(key, ms)
            }
        }
    }

    /** 以 MediaController 的真实队列为准，持久化队列顺序与当前下标 */
    private fun saveQueue() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val keys = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        prefs.queueKeys = keys
        prefs.queueIndex = c.currentMediaItemIndex
    }

    private suspend fun ensureController(): MediaController? {
        controller?.let { return it }
        return runCatching { connection.connect() }.onFailure { Log.e(TAG, "连接播放服务失败", it) }
            .getOrNull()
            ?.also {
                controller = it
                it.addListener(playerListener)
                // 服务刚起来时套用一次持久化的开关
                PlayerHandle.setSkipSilence(prefs.skipSilence)
                _player.update { s ->
                    s.copy(
                        isPlaying = it.isPlaying,
                        repeatMode = it.repeatMode,
                        shuffle = it.shuffleModeEnabled,
                        durationMs = it.duration.coerceAtLeast(0L),
                        queueSize = it.mediaItemCount,
                        speed = it.playbackParameters.speed,
                    )
                }
            }
    }

    /** 断点续播：恢复**整个队列**（含顺序与当前曲目），但不自动播放 */
    fun restoreQueueIfNeeded() {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            if (c.mediaItemCount > 0) return@launch
            if (_library.value.restored) return@launch
            _library.update { it.copy(restored = true) }
            val songs = _library.value.songs
            if (songs.isEmpty()) return@launch

            val position = prefs.lastPositionMs.coerceAtLeast(0L)

            // 1) 优先整队列恢复
            val savedKeys = prefs.queueKeys
            if (savedKeys.isNotEmpty()) {
                val byKey = songs.associateBy { it.stableKey }
                val restored = savedKeys.mapNotNull { byKey[it] }
                if (restored.isNotEmpty()) {
                    val index = prefs.queueIndex.coerceIn(0, restored.size - 1)
                    currentQueue = restored
                    c.setMediaItems(
                        restored.map { PlaybackConnection.toMediaItem(it) },
                        index,
                        position,
                    )
                    c.prepare()
                    _progress.value = PlaybackProgress(position, restored[index].durationMs, 0L)
                    syncCurrentSong(force = true)
                    return@launch
                }
            }

            // 2) 退化为单曲恢复
            val lastUri = prefs.lastSongUri ?: return@launch
            val idx = songs.indexOfFirst { it.stableKey == lastUri }
            if (idx < 0) return@launch
            currentQueue = songs
            c.setMediaItems(
                songs.map { PlaybackConnection.toMediaItem(it) },
                idx,
                position,
            )
            c.prepare()
            _progress.value = PlaybackProgress(position, songs[idx].durationMs, 0L)
            syncCurrentSong(force = true)
        }
    }

    fun playQueue(queue: List<Song>, startIndex: Int) {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            if (queue.isEmpty()) return@launch
            val index = startIndex.coerceIn(0, queue.size - 1)
            currentQueue = queue
            c.setMediaItems(queue.map { PlaybackConnection.toMediaItem(it) }, index, 0L)
            c.prepare()
            c.play()
            if (prefs.playbackSpeed != 1f) c.setPlaybackSpeed(prefs.playbackSpeed)
            _progress.value = PlaybackProgress(0L, queue[index].durationMs, 0L)
            _player.update { it.copy(queueSize = queue.size) }
            saveQueue()
            syncCurrentSong(force = true)
            startPositionTicker()
        }
    }

    fun togglePlay() {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            if (c.mediaItemCount == 0) {
                val list = _library.value.songs
                if (list.isNotEmpty()) playQueue(list, 0)
                return@launch
            }
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    fun next() = viewModelScope.launch { ensureController()?.seekToNextMediaItem() }

    fun previous() = viewModelScope.launch {
        val c = ensureController() ?: return@launch
        if (c.currentPosition > 3000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = viewModelScope.launch {
        val c = ensureController() ?: return@launch
        val target = ms.coerceIn(0L, c.duration.coerceAtLeast(0L))
        c.seekTo(target)
        _progress.update { it.copy(positionMs = target) }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo((c.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    /** 相对当前进度微调（歌词面板的快退/快进按钮用） */
    fun seekRelative(deltaMs: Long) {
        val base = _progress.value.positionMs
        seekTo((base + deltaMs).coerceAtLeast(0L))
    }

    /**
     * 播放模式轮换：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序。
     *
     * 合并到播放页的同一个按钮上，不再让「随机」和「循环」各占一个位置。
     */
    fun cyclePlayMode() = viewModelScope.launch {
        val c = ensureController() ?: return@launch
        when (playModeOf(c.repeatMode, c.shuffleModeEnabled).next) {
            // 先关随机再设循环，避免中间态变成「随机 + 循环」
            PlayMode.SEQUENTIAL -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
            PlayMode.REPEAT_ALL -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlayMode.REPEAT_ONE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
            // 随机播放 = 乱序 + 列表循环；同理先设好 repeatMode 再开随机
            PlayMode.SHUFFLE -> {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = true
            }
        }
    }

    fun playNext(song: Song) {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            val idx = c.currentMediaItemIndex
            val insertAt = if (c.mediaItemCount == 0) 0 else (idx + 1).coerceAtMost(c.mediaItemCount)
            val exists = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == song.stableKey }
            if (exists != null) {
                c.moveMediaItem(exists, if (exists < insertAt) insertAt - 1 else insertAt)
            } else {
                c.addMediaItem(insertAt, PlaybackConnection.toMediaItem(song))
            }
            _player.update { it.copy(queueSize = c.mediaItemCount) }
            saveQueue()
            pushMessage("已加入播放队列：${song.title}")
        }
    }

    // ---------------------------------------------------------- 队列抽屉

    fun refreshQueue() {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            val count = c.mediaItemCount
            val entries = ArrayList<QueueEntry>(count)
            for (i in 0 until count) {
                val item = c.getMediaItemAt(i)
                entries.add(
                    QueueEntry(
                        index = i,
                        mediaId = item.mediaId,
                        title = item.mediaMetadata.title?.toString() ?: "",
                        artist = item.mediaMetadata.artist?.toString() ?: "",
                        isCurrent = i == c.currentMediaItemIndex,
                    )
                )
            }
            _player.update { it.copy(queue = entries, queueSize = entries.size) }
        }
    }

    fun jumpToQueue(index: Int) {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            c.seekToDefaultPosition(index)
            c.play()
            saveQueue()
        }
    }

    fun removeQueueItem(index: Int) {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
            saveQueue()
            refreshQueue()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        viewModelScope.launch {
            val c = ensureController() ?: return@launch
            if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
                c.moveMediaItem(from, to)
                saveQueue()
                refreshQueue()
            }
        }
    }

    // ---------------------------------------------------------- 速度 / 定时 / 均衡器

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        prefs.playbackSpeed = s
        _player.update { it.copy(speed = s) }
        viewModelScope.launch { ensureController()?.setPlaybackSpeed(s) }
    }

    fun sleepAfter(minutes: Int) = SleepTimer.start(minutes * 60_000L)

    fun sleepEndOfSong() {
        // 直接问控制器要真实的进度与总长，避免依赖已从 PlayerUiState 移除的字段
        val c = controller
        val position = c?.currentPosition ?: _progress.value.positionMs
        val duration = c?.duration?.takeIf { it > 0 } ?: _progress.value.durationMs
        SleepTimer.startEndOfSong(position, duration)
    }

    fun cancelSleep() = SleepTimer.cancel()

    // ---------------------------------------------------------- 播放界面

    fun openPlayer() {
        _player.update { it.copy(playerExpanded = true) }
        refreshQueue()
    }

    fun closePlayer() = _player.update { it.copy(playerExpanded = false) }

    // ---------------------------------------------------------- 歌词偏移（逐曲）

    fun setLyricOffset(deltaMs: Int) {
        val key = _player.value.current?.stableKey ?: return
        val next = (prefs.lyricOffsetFor(key) + deltaMs).coerceIn(-20_000, 20_000)
        prefs.setLyricOffsetFor(key, next)
        _player.update { it.copy(lyricOffsetMs = next) }
    }

    fun resetLyricOffset() {
        val key = _player.value.current?.stableKey ?: return
        prefs.setLyricOffsetFor(key, 0)
        _player.update { it.copy(lyricOffsetMs = 0) }
    }

    fun setLyricScale(scale: Float) {
        val s = scale.coerceIn(0.7f, 1.6f)
        prefs.lyricScale = s
        _player.update { it.copy(lyricScale = s) }
    }

    /** 逐字歌词：当前行有逐字时间轴时按字染色（默认开启） */
    fun setLyricWordByWord(enabled: Boolean) {
        prefs.lyricWordByWord = enabled
        _player.update { it.copy(lyricWordByWord = enabled) }
    }

    /** 车载模式：启动直进播放页 + 播放页 5 秒无操作进入沉浸态 */
    fun setCarMode(enabled: Boolean) {
        prefs.carMode = enabled
        _player.update { it.copy(carMode = enabled) }
    }

    /**
     * 桌面悬浮歌词开关。
     *
     * 落盘 + 更新界面状态 + 同步起停悬浮窗服务。
     * 若用户开着开关但尚未授权「出现在其他应用上」，`sync` 内部会拒绝启动，
     * 设置页据此把开关弹回并提示去授权（见 [SettingsScreen]）。
     */
    fun setLyricOverlay(enabled: Boolean) {
        prefs.lyricOverlay = enabled
        _player.update { it.copy(lyricOverlay = enabled) }
        LyricOverlayBridge.sync(getApplication(), enabled)
        if (enabled) {
            // 刚打开时立刻按当前进度填一行，不必等下一个 tick
            val c = controller
            if (c != null) publishLyricToOverlay(c.currentPosition.coerceAtLeast(0L), c.isPlaying)
        }
    }

    /** 悬浮歌词是否已获得「出现在其他应用上」权限 */
    fun canDrawOverlay(): Boolean = LyricOverlayBridge.canDraw(getApplication())

    /**
     * 进入 / 退出悬浮歌词的「调整位置」模式。
     *
     * 进入后窗口临时可拖动（吃触摸），退出后位置落盘并恢复点击穿透。
     * 只更新会话级状态，不落盘——「是否正在调整」不是需要记住的偏好。
     */
    fun setOverlayEditing(editing: Boolean) {
        LyricOverlayBridge.setEditing(editing)
        _player.update { it.copy(overlayEditing = editing) }
    }

    /**
     * App 前后台切换时同步给悬浮窗。
     *
     * 悬浮窗只在 App 退到后台时显示；回到前台时用户看的是播放页歌词，
     * 桌面字幕属于重复信息，直接隐藏。
     */
    fun setAppForeground(foreground: Boolean) {
        LyricOverlayBridge.setAppForeground(foreground)
    }

    /**
     * App 启动时对齐一次悬浮窗服务状态。
     *
     * 场景：用户上次开着悬浮歌词，但中途在系统设置里撤了授权，
     * 或者车机重启后服务被系统清掉——启动时对一次账，避免开关与实际不符。
     */
    fun restoreLyricOverlay() {
        val enabled = prefs.lyricOverlay && canDrawOverlay()
        if (prefs.lyricOverlay && !enabled) {
            // 授权已被撤销：开关回置为关，避免界面骗人
            setLyricOverlay(false)
        } else {
            LyricOverlayBridge.sync(getApplication(), enabled)
        }
    }

    fun setDynamicTheme(enabled: Boolean) {
        prefs.dynamicTheme = enabled
        if (!enabled) _themeSeed.value = null
    }

    fun dynamicThemeEnabled(): Boolean = prefs.dynamicTheme

    // ---------------------------------------------------------- 设置页

    /** 刷新播放统计概览 */
    private fun refreshStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                PlayStats(
                    listenedSongs = runCatching { db.playStatDao().playedSongCount() }.getOrDefault(0),
                    totalPlays = runCatching { db.playStatDao().totalPlayCount() }.getOrNull() ?: 0L,
                    totalListenedMs = runCatching { db.playStatDao().totalPlayedMs() }.getOrNull() ?: 0L,
                    favorites = runCatching { db.playStatDao().favoriteCount() }.getOrDefault(0),
                )
            }
            _library.update { if (it.playStats == stats) it else it.copy(playStats = stats) }
        }
    }

    /** 清空播放次数与收听时长，但保留收藏 */
    fun clearPlayStats() {
        viewModelScope.launch {
            flushListen()
            withContext(Dispatchers.IO) { db.playStatDao().resetStats() }
            refreshPlaylists()
            pushMessage("播放统计已清空（收藏保留）")
        }
    }

    // ---------------------------------------------------------- 搜索历史

    fun submitSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = (listOf(q) + prefs.searchHistory).distinct().take(10)
        prefs.searchHistory = list
        _library.update { it.copy(searchHistory = list) }
    }

    fun clearSearchHistory() {
        prefs.searchHistory = emptyList()
        _library.update { it.copy(searchHistory = emptyList()) }
    }

    // ---------------------------------------------------------- 音频技术信息

    fun inspectAudio(song: Song) {
        _player.update { it.copy(audioInfo = null, audioInfoLoading = true) }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { AudioInspector.inspect(getApplication(), song) }
            _player.update { it.copy(audioInfo = info, audioInfoLoading = false) }
        }
    }

    fun clearAudioInfo() = _player.update { it.copy(audioInfo = null, audioInfoLoading = false) }

    // ---------------------------------------------------------- 播放行为开关

    fun setLoudness(gainMb: Int) = AudioFx.setLoudnessGain(gainMb)

    fun setSkipSilence(enabled: Boolean) {
        prefs.skipSilence = enabled
        _player.update { it.copy(skipSilence = enabled) }
        // 跳过静音是 ExoPlayer 专有开关，Player 接口上没有，走进程内句柄
        PlayerHandle.setSkipSilence(enabled)
    }

    /** 通知栏 / 锁屏滚动显示当前歌词行（默认关闭） */
    fun setNotifyLyrics(enabled: Boolean) {
        prefs.notifyLyrics = enabled
        _player.update { it.copy(notifyLyrics = enabled) }
        lastPushedLyricIndex = Int.MIN_VALUE
    }

    // ---------------------------------------------------------- 崩溃日志

    fun crashLogCount(): Int = CrashLog.count(getApplication())

    fun crashLogText(): String = CrashLog.allText(getApplication())

    fun clearCrashLog() {
        CrashLog.clear(getApplication())
        pushMessage("崩溃日志已清空")
    }

    fun exportCrashLog(target: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { CrashLog.allText(getApplication()) }
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                pushMessage("崩溃日志已导出")
            }.onFailure { pushMessage("导出失败：${it.message}") }
        }
    }

    // ---------------------------------------------------------- 歌曲信息编辑（本地覆盖）

    fun updateSongInfo(song: Song, title: String, artist: String, album: String) {
        viewModelScope.launch {
            val uri = song.stableKey
            val t = title.trim()
            val a = artist.trim()
            val al = album.trim()
            withContext(Dispatchers.IO) {
                if (t == song.title && a == song.artistOrUnknown && al == song.albumOrUnknown) {
                    db.overrideDao().delete(uri)
                } else {
                    db.overrideDao().upsert(
                        SongOverrideEntity(
                            songUri = uri,
                            title = t.takeIf { it != song.title },
                            artist = a.takeIf { it != song.artistOrUnknown },
                            album = al.takeIf { it != song.albumOrUnknown },
                        )
                    )
                }
            }
            // 应用到当前列表
            val mapped = _library.value.songs.map {
                if (it.stableKey == uri) it.copy(
                    title = t.ifBlank { it.title },
                    artist = a.ifBlank { null },
                    album = al.ifBlank { null },
                ) else it
            }
            val sorted = repository.sort(mapped, _library.value.sortOrder)
            val visible = searchSongs(sorted, _library.value.query)
            _library.update {
                it.copy(songs = sorted, visible = visible, letterIndex = buildLetterIndex(visible))
            }
            currentQueue = sorted
            refreshGroups()
            _player.update { p ->
                if (p.current?.stableKey == uri) p.copy(
                    current = p.current?.copy(title = t.ifBlank { p.current!!.title }),
                ) else p
            }
            pushMessage("已更新歌曲信息（仅本应用内显示，不修改原文件）")
        }
    }

    // ---------------------------------------------------------- M3U 导入导出

    fun exportPlaylistM3U(playlist: PlaylistInfo, target: Uri) {
        viewModelScope.launch {
            val songs: List<Song> = when {
                playlist.virtual && playlist.id == VIRTUAL_RECENT -> _library.value.extraRecent
                playlist.virtual && playlist.id == VIRTUAL_MOST -> _library.value.extraMostPlayed
                else -> {
                    val uris = withContext(Dispatchers.IO) { db.playlistDao().songsOf(playlist.id) }
                    val byUri = _library.value.songs.associateBy { it.stableKey }
                    uris.mapNotNull { byUri[it.songUri] }
                }
            }
            val text = buildM3U(playlist.name, songs)
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                pushMessage("已导出「${playlist.name}」（${songs.size} 首）")
            }.onFailure { pushMessage("导出失败：${it.message}") }
        }
    }

    fun importM3U(target: Uri) {
        viewModelScope.launch {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(target)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                pushMessage("无法读取 M3U 文件")
                return@launch
            }
            val library = _library.value.songs
            val byPath = library.filter { it.path != null }.associateBy { it.path!! }
            val byUri = library.associateBy { it.stableKey.substringAfterLast('/') }
            val matched = ArrayList<Song>()
            var pendingTitle: String? = null
            for (line in text.lines()) {
                val l = line.trim()
                if (l.isEmpty()) continue
                if (l.startsWith("#EXTINF")) {
                    pendingTitle = l.substringAfter(':', "").trim()
                    continue
                }
                if (l.startsWith("#")) continue
                val clean = l.substringAfterLast("file://")
                val song = byPath[clean]
                    ?: library.firstOrNull { it.path != null && it.path.endsWith(clean, ignoreCase = true) }
                    ?: byUri[clean.substringAfterLast('/')]
                    ?: pendingTitle?.let { t ->
                        val name = t.substringAfter(" - ")
                        library.firstOrNull { it.title.equals(name, ignoreCase = true) }
                    }
                if (song != null) matched.add(song)
                pendingTitle = null
            }
            if (matched.isEmpty()) {
                pushMessage("M3U 中没有匹配到本地歌曲")
                return@launch
            }
            val name = "导入 " + (target.lastPathSegment?.substringBeforeLast('.')?.takeLast(20) ?: "歌单")
            createPlaylist(name, matched)
        }
    }

    private fun buildM3U(name: String, songs: List<Song>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:$name")
        for (s in songs) {
            appendLine("#EXTINF:${s.durationMs / 1000},${s.artistOrUnknown} - ${s.title}")
            appendLine(s.path ?: s.uri.toString())
        }
    }

    // ---------------------------------------------------------- 歌词导出

    fun exportLyrics(target: Uri) {
        viewModelScope.launch {
            val lyrics = _player.value.lyrics
            if (lyrics.isEmpty) {
                pushMessage("当前歌曲没有歌词")
                return@launch
            }
            val text = buildString {
                appendLine("[ti:${_player.value.current?.title.orEmpty()}]")
                appendLine("[ar:${_player.value.current?.artistOrUnknown.orEmpty()}]")
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
                getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                pushMessage("歌词已导出为 LRC")
            }.onFailure { pushMessage("导出失败：${it.message}") }
        }
    }

    // ================================================================ 内部

    private fun syncCurrentSong(force: Boolean = false) {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId ?: run {
            flushListen()
            _player.update { it.copy(current = null, lyrics = Lyrics.EMPTY) }
            LyricOverlayBridge.clear()
            return
        }
        if (!force && currentLyricsSong == mediaId) return
        val song = currentQueue.firstOrNull { it.stableKey == mediaId }
            ?: currentQueue.firstOrNull { it.uri.toString() == c.currentMediaItem?.localConfiguration?.uri?.toString() }
            ?: return

        flushListen()
        saveQueue()
        currentLyricsSong = mediaId
        lastPushedLyricIndex = Int.MIN_VALUE
        prefs.lastSongUri = mediaId
        _player.update {
            it.copy(
                current = song,
                durationMs = song.durationMs,
                queueSize = c.mediaItemCount,
                lyricOffsetMs = prefs.lyricOffsetFor(song.stableKey),
            )
        }
        startPositionTicker()
        loadLyrics(song)
        loadArtwork(song)
    }

    private fun loadLyrics(song: Song) {
        lyricsJob?.cancel()
        _player.update { it.copy(lyrics = Lyrics.EMPTY, lyricsLoading = true) }
        lyricsJob = viewModelScope.launch {
            val lyrics = runCatching { lyricsLoader.load(song) }.getOrDefault(Lyrics.EMPTY)
            _player.update { it.copy(lyrics = lyrics, lyricsLoading = false) }
        }
    }

    /** 把封面字节塞进通知栏/锁屏的媒体元数据（只替换元数据，不打断播放），并做主题取色 */
    private fun loadArtwork(song: Song) {
        artworkJob?.cancel()
        artworkJob = viewModelScope.launch {
            val bytes = CoverRepository.loadRaw(getApplication(), song) ?: run {
                _themeSeed.value = null
                return@launch
            }
            // 主题取色（与封面解码并行做，失败不影响播放）
            if (prefs.dynamicTheme) {
                val seed = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    ThemeColorExtractor.extract(bytes)
                }
                _themeSeed.value = seed
            }
            val c = controller ?: return@launch
            val index = c.currentMediaItemIndex
            if (index < 0 || index >= c.mediaItemCount) return@launch
            val item = c.getMediaItemAt(index)
            if (item.mediaId != song.stableKey) return@launch
            if (item.mediaMetadata.artworkData != null) return@launch
            runCatching { c.replaceMediaItem(index, PlaybackConnection.withArtwork(item, bytes)) }
        }
    }

    /**
     * 播放进度计时器。
     *
     * 关键改动：进度只写 [_progress]，不再写 [_player]。
     * 原先每 250ms 更新一次 PlayerUiState，而 MainScreen 顶层订阅了它，
     * 导致整个主界面每秒被重组 4 次——这是 v2.1 最大的性能问题。
     * 同时把进度落盘从「每 250ms」降频到「每 5 秒」。
     */
    private fun startPositionTicker() {
        if (positionJob?.isActive == true) return
        positionJob = viewModelScope.launch {
            var lastSavedAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val c = controller
                if (c != null) {
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    val buffered = c.bufferedPosition.coerceAtLeast(0L)
                    val rawDuration = c.duration
                    val duration = if (rawDuration > 0) rawDuration
                    else (_player.value.current?.durationMs ?: 0L)
                    val playing = c.isPlaying

                    _progress.value = PlaybackProgress(pos, duration, buffered)

                    val now = SystemClock.elapsedRealtime()
                    if (pos > 0 && now - lastSavedAt >= 5_000L) {
                        lastSavedAt = now
                        prefs.lastPositionMs = pos
                    }

                    accumulateListening(c.currentMediaItem?.mediaId, playing)
                    if (playing) maybePushLyricToNotification(pos)
                    // 悬浮歌词：与进度同频驱动，内部按「行文字是否变化」去重，
                    // 未开启开关时是纯字段比较，开销可忽略。
                    publishLyricToOverlay(pos, playing)
                }
                // 播放中 200ms 刷新，暂停时降到 1s，避免空转耗电
                delay(if (controller?.isPlaying == true) 200L else 1_000L)
            }
        }
    }

    /** 累计收听时长：仅播放中累加，切歌 / 每 30 秒落库一次 */
    private fun accumulateListening(mediaId: String?, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastTickAt > 0L) (now - lastTickAt).coerceIn(0L, 2_000L) else 0L
        lastTickAt = now

        if (mediaId == null) {
            flushListen()
            listenForKey = null
            return
        }
        if (listenForKey != null && listenForKey != mediaId) flushListen()
        if (listenForKey != mediaId) {
            listenForKey = mediaId
            listenAccumMs = 0L
        }
        if (playing) {
            listenAccumMs += delta
            if (listenAccumMs >= 30_000L) flushListen()
        }
    }

    /**
     * 把当前歌词行送到桌面悬浮窗。
     *
     * 与 [maybePushLyricToNotification] 的区别：
     *  - 后者只在行号变化时才 `replaceMediaItem`，因为改媒体项是有代价的；
     *  - 这里**每 tick 都推**，去重放在 [LyricOverlayBridge] 内部按文字比对完成。
     *    这样暂停 / 恢复播放导致的透明度变化也能立刻反映到悬浮窗上，
     *    而纯文字不变时不会真的去动 View。
     *
     * 无歌词（纯音乐 / 未匹配到）时不推送空行，悬浮窗会保持上次内容或隐藏。
     */
    private fun publishLyricToOverlay(positionMs: Long, playing: Boolean) {
        if (!prefs.lyricOverlay) return
        val state = _player.value
        val lyrics = state.lyrics
        val text = if (!lyrics.isEmpty && lyrics.synced) {
            val index = lyrics.indexAt(positionMs + state.lyricOffsetMs)
            lyrics.lines.getOrNull(index)?.text.orEmpty()
        } else {
            // 无时间轴歌词（纯文本）没法跟着走，显示标题更有用
            state.current?.title.orEmpty()
        }
        LyricOverlayBridge.publish(text, playing)
    }

    /** 把当前歌词行下发到通知栏 / 锁屏（默认关闭，见设置页） */
    private fun maybePushLyricToNotification(positionMs: Long) {
        if (!prefs.notifyLyrics) return
        val c = controller ?: return
        val state = _player.value
        val lyrics = state.lyrics
        if (lyrics.isEmpty || !lyrics.synced) return
        val index = lyrics.indexAt(positionMs + state.lyricOffsetMs)
        if (index < 0 || index == lastPushedLyricIndex) return
        val itemIndex = c.currentMediaItemIndex
        if (itemIndex < 0 || itemIndex >= c.mediaItemCount) return
        val line = lyrics.lines.getOrNull(index) ?: return
        lastPushedLyricIndex = index
        val item = c.getMediaItemAt(itemIndex)
        runCatching {
            c.replaceMediaItem(
                itemIndex,
                item.buildUpon()
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setSubtitle(line.text.ifBlank { " " })
                            .build()
                    )
                    .build(),
            )
        }
    }

    override fun onCleared() {
        // 队列与进度同步落盘（同步写，不依赖已将要被取消的 scope）
        saveQueue()
        lyricsJob?.cancel()
        artworkJob?.cancel()
        positionJob?.cancel()
        searchJob?.cancel()
        controller?.removeListener(playerListener)
        connection.release()
        super.onCleared()
    }

    // ================================================================ 应用内更新

    /**
     * 启动时自动检查更新。
     * 默认开启（可在设置页关闭），24 小时内最多查一次，且失败完全静默——不打扰听歌。
     */
    private fun maybeAutoCheckUpdate() {
        if (!prefs.autoCheckUpdate) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheckAt < AUTO_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            // 让启动帧与首次扫描先跑完，再发网络请求
            delay(4_000)
            if (!prefs.autoCheckUpdate) return@launch
            prefs.lastUpdateCheckAt = System.currentTimeMillis()
            when (val r = UpdateChecker.checkLatest()) {
                is UpdateCheckResult.Available -> {
                    _update.update { it.copy(info = r.info) }
                    pushMessage("发现新版本 v${r.info.version}，可在「设置 → 检查更新」中升级")
                }
                // 已是最新 / 检查失败：自动模式下都不提示
                else -> Unit
            }
        }
    }

    /** 手动检查更新（设置页按钮） */
    fun checkUpdate() {
        if (_update.value.checking) return
        viewModelScope.launch {
            _update.update { it.copy(checking = true, message = null) }
            prefs.lastUpdateCheckAt = System.currentTimeMillis()
            when (val r = UpdateChecker.checkLatest()) {
                is UpdateCheckResult.Available ->
                    _update.update { it.copy(checking = false, info = r.info, dialogVisible = true, message = null) }

                is UpdateCheckResult.UpToDate ->
                    _update.update {
                        it.copy(checking = false, info = null, message = "已是最新版本（v${r.latestVersion}）")
                    }

                is UpdateCheckResult.Failed ->
                    _update.update { it.copy(checking = false, message = r.reason) }
            }
        }
    }

    /** 下载新版本 APK（下载完成后停留在待安装状态） */
    fun downloadUpdate() {
        val info = _update.value.info ?: return
        if (_update.value.downloading) return
        // 线路在点「下载」这一刻定下来，避免下载中途被设置页的改动影响
        val route = prefs.updateDownloadRoute
        viewModelScope.launch {
            _update.update {
                it.copy(
                    downloading = true,
                    progress = 0,
                    downloadedBytes = 0L,
                    totalBytes = info.apkSizeBytes,
                    message = null,
                    readyApkPath = null,
                    needInstallPermission = false,
                    route = route,
                )
            }
            val app = getApplication<Application>()
            runCatching {
                UpdateChecker.download(
                    app,
                    info,
                    route = route,
                    onProgress = { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        _update.update { it.copy(progress = pct, downloadedBytes = done, totalBytes = total) }
                    },
                    onLineFallback = { attempt, count ->
                        // 只有「自动」模式才有多条线路可切
                        _update.update {
                            it.copy(progress = 0, downloadedBytes = 0L, message = "直连太慢，切换镜像线路 $attempt/$count…")
                        }
                    },
                )
            }.onSuccess { file ->
                _update.update {
                    it.copy(
                        downloading = false,
                        progress = 100,
                        readyApkPath = file.absolutePath,
                        dialogVisible = true,
                        message = null, // 清掉「切换镜像」之类的下载过程提示
                        needInstallPermission = !UpdateChecker.canInstall(getApplication()),
                    )
                }
            }.onFailure { t ->
                _update.update {
                    it.copy(
                        downloading = false,
                        progress = 0,
                        message = UpdateChecker.describeDownloadFailure(route, t),
                    )
                }
            }
        }
    }

    /** 下载完成后拉起系统安装器 */
    fun installUpdate() {
        val path = _update.value.readyApkPath ?: return
        val app = getApplication<Application>()
        if (!UpdateChecker.canInstall(app)) {
            _update.update { it.copy(needInstallPermission = true) }
            return
        }
        UpdateChecker.install(app, java.io.File(path))
            .onSuccess { _update.update { it.copy(dialogVisible = false) } }
            .onFailure { t ->
                _update.update {
                    it.copy(message = "无法启动安装器：${t.message ?: "请到 Releases 页面手动下载"}")
                }
            }
    }

    /** 跳转系统「安装未知应用」授权页 */
    fun grantInstallPermission() {
        val app = getApplication<Application>()
        val intent = UpdateChecker.unknownSourcesIntent(app)
        if (intent == null) {
            _update.update { it.copy(needInstallPermission = false, message = "当前系统无需额外授权，请直接点击安装") }
            return
        }
        runCatching { app.startActivity(intent) }.onFailure {
            _update.update { it.copy(message = "无法打开授权页，请到「设置 → 应用 → 安装未知应用」中手动允许") }
        }
    }

    /** 关掉更新弹窗（保留已发现的新版本信息，方便稍后再升） */
    fun dismissUpdateDialog() = _update.update { it.copy(dialogVisible = false) }

    /** 关闭更新提示文案 */
    fun consumeUpdateMessage() = _update.update { it.copy(message = null) }

    /** 已发现新版本但用户暂时不升级：连同信息一起清掉 */
    fun ignoreUpdate() = _update.update {
        it.copy(
            info = null,
            dialogVisible = false,
            readyApkPath = null,
            needInstallPermission = false,
            progress = 0,
        )
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        prefs.autoCheckUpdate = enabled
        // 重新打开时允许立即检查一次
        if (enabled) prefs.lastUpdateCheckAt = 0L
    }

    fun autoCheckUpdateEnabled(): Boolean = prefs.autoCheckUpdate

    /** 下载线路（设置页读到当前值） */
    fun downloadRoute(): DownloadRoute = prefs.updateDownloadRoute

    /** 切换下载线路（只影响 APK 下载；检查更新永远走 GitHub 官方地址） */
    fun setDownloadRoute(route: DownloadRoute) {
        prefs.updateDownloadRoute = route
        _update.update { it.copy(route = route) }
    }

    /** 浏览器打开 Releases 页面（下载失败或想手动下载时的兜底） */
    fun openReleasePage() {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.releasePageUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------------------------------------------------------------- 常量

    companion object {
        private const val TAG = "MusicViewModel"
        /** 自动检查更新的最小间隔：24 小时 */
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val VIRTUAL_RECENT = -1L
        const val VIRTUAL_MOST = -2L
    }
}

/** 文件夹维度缺失时的兜底标签，只用于内部分组，不在 UI 中展示 */
private const val UNKNOWN_FOLDER_LABEL = "未知目录"

/**
 * 从 SAF 音频文档 URI 反推出所在的目录路径。
 * 示例：content://.../document/primary%3AMusic%2FTaylor%20Swift%2Fsong.mp3
 *       → primary:Music/Taylor Swift/song.mp3 → Music/Taylor Swift
 */
private fun android.net.Uri.derivedFolderPath(): String? {
    val docId = lastPathSegment ?: return null
    val decoded = android.net.Uri.decode(docId)
    val pathOnly = decoded.substringAfter(':', "")
    return pathOnly.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
}

/** 取 content URI 的文档 ID（已解码）；拿不到返回 null */
private fun docIdOf(uriString: String): String? = runCatching {
    android.net.Uri.decode(android.net.Uri.parse(uriString).lastPathSegment.orEmpty())
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * 目录的展示名：只取路径里**最后一个文件夹的名字**。
 * 「/storage/emulated/0/Music/华语」→「华语」；SAF / WebDAV 传进来的本来就是目录名，原样返回。
 */
private fun folderDisplayName(path: String): String =
    path.trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { path }

/** 文件夹维度标签（分组用） */
private val Song.folderLabel: String
    get() = when {
        folderPath != null -> folderPath
        path != null -> java.io.File(path).parent
        source == com.luckylearning.windtone.data.model.SongSource.FOLDER -> uri.derivedFolderPath()
        else -> null
    } ?: UNKNOWN_FOLDER_LABEL
