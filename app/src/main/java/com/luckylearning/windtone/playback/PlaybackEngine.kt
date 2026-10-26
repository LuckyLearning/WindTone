package com.luckylearning.windtone.playback

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.BootStatus
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.LocalLyricsMatch
import com.luckylearning.windtone.data.LyricsLoader
import com.luckylearning.windtone.data.MessageBus
import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.SettingsRepository
import com.luckylearning.windtone.data.SongIndex
import com.luckylearning.windtone.data.ThemeColorExtractor
import com.luckylearning.windtone.data.ThemeMode
import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.db.QueueSongEntity
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunDrive
import com.luckylearning.windtone.data.aliyun.AliyunEnricher
import com.luckylearning.windtone.data.aliyun.AliyunEntry
import com.luckylearning.windtone.data.aliyun.AliyunPlaybackWindow
import com.luckylearning.windtone.data.aliyun.AliyunQueueItem
import com.luckylearning.windtone.data.aliyun.AliyunQueueSnapshot
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.TrackFragment
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.overlay.LyricOverlayService
import com.luckylearning.windtone.playback.parallel.ParallelDownloadRegistry
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.QueueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放域状态持有者（**不是** ViewModel）。
 *
 * 为什么独立于界面：播放这件事的生命周期比任何界面都长——
 * `MediaSessionService` 在后台一直放歌，界面退出重建、旋转、进车载主页都不该影响它。
 * 以前这些状态全塞在 `MusicViewModel` 里，于是「播放」和「曲库」两个毫不相干的域
 * 被绑在同一个 2600 行的类里，还被迫共用 `pushMessage`、`idByUri` 这类私有设施。
 *
 * 现在它住在 [com.luckylearning.windtone.AppContainer]（应用级单例）：
 *  - 对外只暴露 [player] / [progress] / [themeSeed] 三个流 + [PlayerActions] 上的动作；
 *  - 依赖只有偏好、数据库、歌词加载器、曲库索引、消息总线，**完全不认识界面**。
 *
 * 线程约定：所有公开动作都从主线程调用（Compose 事件回调），
 * 内部副作用统一投递到 [scope]（`Dispatchers.Main.immediate`），IO 再切 IO 线程。
 */
class PlaybackEngine(
    private val app: Application,
    private val prefs: Prefs,
    private val settings: SettingsRepository,
    private val store: PlaybackStore,
    private val lyricsLoader: LyricsLoader,
    private val index: SongIndex,
    private val messages: MessageBus,
) : PlayerActions {

    private val connection = PlaybackConnection(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------------ 对外状态

    private val _player = MutableStateFlow(
        PlayerUiState(
            lyricScale = prefs.lyricScale,
            overlayHandles = prefs.overlayHandles,
            overlayHandleCollapse = prefs.overlayHandleCollapse,
            overlayHandlePlayPause = prefs.overlayHandlePlayPause,
            overlayHandlePlayer = prefs.overlayHandlePlayer,
            lyricTapSeeks = prefs.lyricTapSeeks,
            showA11yAction = prefs.playerA11yAction,
            lyricsPaneVisible = prefs.lyricsPaneVisible,
            carMode = prefs.carMode,
            lyricOverlay = prefs.lyricOverlay,
            lyricOverlayLines = prefs.lyricOverlayLines,
            lyricOverlayFullHeight = prefs.lyricOverlayFullHeight,
            lyricOverlayFullWidthPercent = prefs.lyricOverlayFullWidthPercent,
            lyricOverlayFullOffsetPercent = prefs.lyricOverlayFullOffsetPercent,
            lyricOverlayShowTitle = prefs.lyricOverlayShowTitle,
            lyricOverlayTitlePercent = prefs.lyricOverlayTitlePercent,
            lyricOverlayTextSp = prefs.lyricOverlayTextSp,
            lyricOverlayPauseMode = prefs.lyricOverlayPauseMode,
            // v1.1.29：悬浮窗按应用隐藏 + 前台应用记录开关
            lyricOverlayHiddenApps = prefs.lyricOverlayHiddenApps,
            foregroundLogEnabled = prefs.foregroundLogEnabled,
            debugUnlocked = prefs.debugUnlocked,
            lyricOverlayAlign = prefs.lyricOverlayAlign,
            // v1.1.4 新增：悬浮窗宽度与文字颜色
            lyricOverlayWidthPercent = prefs.lyricOverlayWidthPercent,
            lyricOverlayTextColor = prefs.lyricOverlayTextColor,
            // v1.1.48：尺寸 / 位置 / 背景可自定义
            lyricOverlayBoxHeightPercent = prefs.lyricOverlayBoxHeightPercent,
            lyricOverlayFullHeightPercent = prefs.lyricOverlayFullHeightPercent,
            // -1 = 还没从旧像素坐标迁移过：界面先按默认值显示，服务挂窗后会写回真实值
            lyricOverlayXPercent = prefs.lyricOverlayXPercent
                .takeIf { it >= 0 } ?: SettingsDefaults.OVERLAY_X_PERCENT,
            lyricOverlayYPercent = prefs.lyricOverlayYPercent
                .takeIf { it >= 0 } ?: SettingsDefaults.OVERLAY_Y_PERCENT,
            lyricOverlayFullYPercent = prefs.lyricOverlayFullYPercent,
            lyricOverlayBgColor = prefs.lyricOverlayBgColor,
            lyricOverlayBgAlphaPercent = prefs.lyricOverlayBgAlphaPercent,
            lyricOverlayCornerRadiusDp = prefs.lyricOverlayCornerRadiusDp,
            lyricOverlayBorderWidthDp = prefs.lyricOverlayBorderWidthDp,
            lyricOverlayBorderColor = prefs.lyricOverlayBorderColor,
            lyricOverlayLineSpacingDp = prefs.lyricOverlayLineSpacingDp,
            lyricOverlayCurrentColor = prefs.lyricOverlayCurrentColor,
            lyricOverlayDimPercent = prefs.lyricOverlayDimPercent,
            lyricOverlayStrokeWidthDp = prefs.lyricOverlayStrokeWidthDp,
            lyricOverlayStrokeColor = prefs.lyricOverlayStrokeColor,
            lyricOverlayFadeMs = prefs.lyricOverlayFadeMs,
            lyricOverlayTitleMode = prefs.lyricOverlayTitleMode,
            // 车载模式：启动直接进播放页（跳过库主页）
            playerExpanded = prefs.carMode,
            speed = prefs.playbackSpeed,
            pitchSemitones = prefs.playbackPitchSemitones,
            skipSilence = prefs.skipSilence,
            autoResume = prefs.autoResume,
            autoPlayOnBoot = prefs.autoPlayOnBoot,
        )
    )
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    /** 高频播放进度：独立于 [player]，避免每 200ms 触发整界面重组 */
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** 主题取色（当前封面主色；dynamicTheme 关闭时为 null） */
    private val _themeSeed = MutableStateFlow<Int?>(null)
    val themeSeed: StateFlow<Int?> = _themeSeed.asStateFlow()

    /**
     * 界面明暗模式（v1.2.5）。
     *
     * 为什么要一条流而不是像其它偏好那样「用时再读 prefs」：换肤发生在**最外层**
     * 的组合（`MainActivity` 的 `MusicPlayerTheme`），设置页里点一下必须立刻让整棵树
     * 重组。一次性读快照的话，用户点完只看到开关变了、界面没变，只能重启应用。
     */
    private val _themeMode = MutableStateFlow(prefs.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * 悬浮歌词 / 外观设置：整簇动作委托给它，引擎只提供 [OverlaySettings.Host] 的窄接口。
     * 见 `playback/OverlaySettings.kt`。
     */
    private val overlaySettings = OverlaySettings(
        app = app,
        settings = settings,
        messages = messages,
        scope = scope,
        state = _player,
        themeSeed = _themeSeed,
        host = object : OverlaySettings.Host {
            override val hasController: Boolean get() = controller != null
            override val currentPositionMs: Long
                get() = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            override val isPlaying: Boolean get() = controller?.isPlaying ?: false

            override fun publishOverlay(positionMs: Long, playing: Boolean) =
                OverlayTickHelper.publishLyric(positionMs, playing, _player.value)

            override fun startTicker() = startPositionTicker()

            override fun setAppForegroundFlag(foreground: Boolean) {
                appForeground = foreground
            }
        },
    )

    /**
     * 播放队列的所有增删 / 跳转 / 乱序 / 模式操作。
     * 只认窄接口 [QueuePlayer]，状态同步 / 落库 / 提示通过 [QueueHostImpl] 交回引擎，
     * 自身不持有任何 Android / 引擎状态，可完全单测。见 `playback/QueueManager.kt`。
     */
    private val queueManager = QueueManager(
        scope = scope,
        connect = { ensureController()?.let { MediaControllerQueuePlayer(it) } },
        toMediaItem = { PlaybackConnection.toMediaItem(it) },
        host = QueueHostImpl(),
    )

    /** [QueueManager.QueueHost] 的引擎实现：把状态同步 / 落库 / 提示交回引擎自身。 */
    private inner class QueueHostImpl : QueueManager.QueueHost {
        override fun setCurrentQueue(queue: List<Song>) { currentQueue = queue }
        override fun notifyQueueSize(size: Int) = _player.update { it.copy(queueSize = size) }
        override fun notifyQueueEntries(entries: List<QueueEntry>) =
            _player.update { it.copy(queue = entries, queueSize = entries.size) }
        override fun updateProgress(positionMs: Long) = _progress.update { it.copy(positionMs = positionMs) }
        override fun postMessage(text: String) = messages.post(text)
        override fun persistQueue() = saveQueue()
        override fun applyPlaybackParams() { controller?.let { applyPlaybackParams(it) } }
        override fun onPlaybackStarted(currentIndex: Int, durationMs: Long, queueSize: Int) {
            val c = controller
            _progress.value = PlaybackProgress(0L, durationMs, 0L)
            _player.update {
                it.copy(
                    queueSize = queueSize,
                    // 显式写回：onShuffleModeEnabledChanged 只在真正变化时回调，
                    // 「本来就是随机又点一次随机」不会触发，图标会停在上一档。
                    repeatMode = c?.repeatMode ?: Player.REPEAT_MODE_OFF,
                    shuffle = c?.shuffleModeEnabled ?: false,
                )
            }
            syncCurrentSong(force = true)
            startPositionTicker()
        }
        override fun currentLibrary(): List<Song> = librarySongs
        override fun startLibraryPlayback() = this@PlaybackEngine.playQueue(librarySongs, 0)
    }

    /** 桌面悬浮窗运行状态（设置页展示；失败时由 init 里的收集器做回退） */
    val overlayHealth: StateFlow<LyricOverlayBus.Health> = LyricOverlayBridge.health

    // ------------------------------------------------------------------ 内部状态

    private var controller: MediaController? = null

    /** 正在播放的队列（`playQueue` / 恢复时设定） */
    private var currentQueue: List<Song> = emptyList()

    /** 曲库当前列表：解析 mediaId → Song 时的兜底来源，由曲库域推过来 */
    private var librarySongs: List<Song> = emptyList()

    private var lyricsJob: Job? = null

    /** 断点续播后的歌词自愈协程（见 [scheduleLyricsHeal]） */
    private var healLyricsJob: Job? = null
    private var artworkJob: Job? = null
    private var positionJob: Job? = null
    /** 本次恢复是否**强制**起播（开机后台播放用；不受「打开应用继续上次播放」开关限制） */
    private var forceAutoplayOnRestore = false

    /** `play()` 之后等多久再确认「真的播起来了」—— 车机音频链路起振慢，800ms 常不够 */
    private val BOOT_PLAY_CONFIRM_MS = 1_200L
    private var currentLyricsSong: String? = null

    /**
     * 最近一次解析出来的**原始**歌词（含双语副行，未经「双语歌词」过滤）。
     *
     * 存在的理由有两条：
     *  - 切换「双语歌词」模式时不必重新读文件，直接拿它重算一遍（读歌词要走
     *    文件系统 / SAF，慢且可能失败）；
     *  - 导出 LRC 必须用它 —— 用户选了「只显示中文」，导出时把译文丢掉并不是他想要的。
     */
    private var rawLyrics: Lyrics = Lyrics.EMPTY

    /** 歌词加载序号：晚到的旧请求不许覆盖新歌的歌词（取消会把 CancellationException 吞掉） */
    private var lyricsSeq = 0
    private var consecutiveErrors = 0

    /**
     * 这次切歌是「上一个文件播放失败」触发的（v1.1.45）。
     *
     * 之前 `onMediaItemTransition` 无条件把 [consecutiveErrors] 清零，而失败跳曲本身也会
     * 触发一次过渡 —— 于是计数永远回到 0，队列里每一首都被试一遍、循环往复，用户看到的是
     * 「一直自动切歌、完全播不了」。这个标记让「因失败而跳」不计入清零。
     */
    private var skippingAfterError = false

    private var queueRestored = false

    /** 恢复动作正在进行中：防止两次调用并发各恢复一遍（曲库缓存与扫描完成各调一次） */
    private var queueRestoreRunning = false
    private var appForeground = true

    /** 播放统计（次数 / 收听时长 / 去重）——已抽出，可独立单测 */
    private val aliyunTracks = AliyunTrackStore(app)
    private val stats = PlaybackStats(
        store,
        index,
        scope,
        object : OnlinePlayLedger {
            override fun recordPlay(mediaId: String, now: Long) = aliyunTracks.recordPlay(mediaId, now)
            override fun addListenTime(mediaId: String, ms: Long, now: Long) =
                aliyunTracks.addListenTime(mediaId, ms, now)
        },
    )
    private var aliyunUrlRetried = false
    private var aliyunWindowJob: Job? = null

    /** 上一次尝试把悬浮窗服务拉回来的时间，用于 30 秒节流（见 [OverlayTickHelper.shouldReviveOverlay]） */
    private var lastOverlayReviveAt = 0L

    /** 上一次规划后台预取的时间（ticker 每 200ms 走一次，靠它节流到 3 秒） */
    private var lastPrefetchPlanAt = 0L

    init {
        AudioFx.init(app)
        TrackVolume.init(app)
        scope.launch {
            SleepTimer.remainingMs.collect { ms ->
                _player.update { it.copy(sleepRemainingMs = ms) }
                // 到点前的淡出：兜住「进度计时器没在跑」的情况（暂停态 / 刚切后台），
                // 正常播放中则由 200ms 的 ticker 负责把曲线走得更细。
                applySleepFade(ms)
            }
        }
        // 悬浮窗起不来时（挂窗被 ROM 拒绝 / 前台服务被系统拦）：
        // 回置开关与状态，别让设置页显示「已开启」而桌面什么都没有，
        // 并用一次性提示把原因说出来（详见 LyricOverlayService 的失败上报）。
        scope.launch {
            LyricOverlayBridge.health.collect { h ->
                if (h.status == LyricOverlayBus.Status.FAILED) {
                    if (prefs.lyricOverlay) setLyricOverlay(false)
                    messages.post("桌面悬浮歌词启动失败：${h.detail}")
                }
            }
        }
        // 悬浮窗服务请求「退出调整态」（长时间无操作，见 LyricOverlayService.armEditingTimeout）。
        // 状态归播放域所有，所以由服务发请求、这里来收——顺手提示一句，
        // 否则用户会以为是自己点错了什么。
        // 悬浮窗上的播放 / 暂停键（v1.2.0）：服务只发意图，这里统一走 togglePlay，
        // 保证与界面按钮、耳机键的行为完全一致（含"队列空时什么都不做"）。
        scope.launch {
            LyricOverlayBridge.togglePlayRequests.collect {
                if (_player.value.current != null) togglePlay()
            }
        }
        scope.launch {
            LyricOverlayBridge.exitEditingRequests.collect {
                if (_player.value.overlayEditing) {
                    setOverlayEditing(false)
                    messages.post("悬浮歌词已自动锁定位置，需要再调整可重新点「调整悬浮歌词位置」")
                }
            }
        }
        // 桌面上左右拖动全屏歌词后，服务把新位置写进 Prefs —— 这里回读一次，
        // 否则设置页的「水平位置」滑块还停在拖动之前的值，看着像没保存。
        scope.launch {
            LyricOverlayBridge.positionSaves.collect {
                val s = _player.value
                val offset = prefs.lyricOverlayFullOffsetPercent
                val fullY = prefs.lyricOverlayFullYPercent
                // 单条形态的位置可能存在 -1（未迁移）；-1 时保留界面上的当前值
                val xPct = prefs.lyricOverlayXPercent.takeIf { it >= 0 } ?: s.lyricOverlayXPercent
                val yPct = prefs.lyricOverlayYPercent.takeIf { it >= 0 } ?: s.lyricOverlayYPercent
                if (s.lyricOverlayFullOffsetPercent != offset ||
                    s.lyricOverlayFullYPercent != fullY ||
                    s.lyricOverlayXPercent != xPct ||
                    s.lyricOverlayYPercent != yPct
                ) {
                    _player.update {
                        it.copy(
                            lyricOverlayFullOffsetPercent = offset,
                            lyricOverlayFullYPercent = fullY,
                            lyricOverlayXPercent = xPct,
                            lyricOverlayYPercent = yPct,
                        )
                    }
                }
            }
        }
    }

    // ================================================================ 曲库 → 播放

    /** 曲库刷新后推过来：用于解析 mediaId → Song（收藏标记、统计写入都靠它） */
    fun setLibrarySongs(songs: List<Song>) {
        librarySongs = songs
    }

    /** 歌曲被彻底删除（曲库侧已清库并级联清理）：把它们从队列与曲库副本里摘掉 */
    fun onSongsRemoved(songs: Collection<Song>) {
        val keys = songs.map { it.stableKey }.toSet()
        if (keys.isEmpty()) return
        currentQueue = currentQueue.filterNot { it.stableKey in keys }
        librarySongs = librarySongs.filterNot { it.stableKey in keys }
    }

    /**
     * 从**已连接的**播放队列里摘掉这些歌（删除文件 / 移除目录时调用）。
     *
     * 刻意不调 [ensureController]：队列都没连上，自然也没东西可摘，
     * 没必要为了删几行去把播放服务拉起来。删的是当前曲目时 ExoPlayer 会自行接续下一首。
     */
    fun removeFromQueue(songs: Collection<Song>) {
        val c = controller ?: return
        val keys = songs.map { it.stableKey }.toSet()
        if (keys.isEmpty()) return
        var i = c.mediaItemCount - 1
        while (i >= 0) {
            if (c.getMediaItemAt(i).mediaId in keys) c.removeMediaItem(i)
            i--
        }
        saveQueue()
    }

    /** 歌曲标签被本地覆盖后，同步正在播放的那一首（标题变了播放页要跟着变） */
    fun renameCurrentSong(stableKey: String, title: String) {
        _player.update { p ->
            val cur = p.current
            if (cur != null && cur.stableKey == stableKey) p.copy(current = cur.copy(title = title)) else p
        }
    }

    // ================================================================ Player.Listener

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _player.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPositionTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            _player.update { it.copy(durationMs = c.duration.coerceAtLeast(0L)) }
            _progress.update { it.copy(bufferedMs = c.bufferedPosition.coerceAtLeast(0L)) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
            // 因失败而跳曲时**不能**清零：否则「失败 → 跳下一首 → 清零 → 再失败」会无限循环
            if (skippingAfterError) {
                skippingAfterError = false
            } else {
                consecutiveErrors = 0
            }
            syncCurrentSong()
            mediaItem?.mediaId?.takeIf { stats.shouldCountPlay(it) }?.let { stats.recordPlay(it) }
            aliyunUrlRetried = false
            scheduleAliyunWindow()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _player.update { it.copy(repeatMode = repeatMode) }
            savePlayerState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _player.update { it.copy(shuffle = shuffleModeEnabled) }
            savePlayerState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // 占位地址（还没取到直链）加载失败是预期内的：等窗口补链换上真直链。
            // 除了看界面状态里的歌，还要看**控制器当前项**——上一曲 / 跳转刚落到
            // 未填项时，界面状态可能还停在上一首歌上，漏判会把占位错误当成真失败，
            // 触发「失败跳下一首」，表现就是「上一曲回不去、反而往前跳」（v1.1.72 真机）。
            val pendingNow = AliyunPlaybackWindow.isPending(_player.value.current?.uri?.scheme) ||
                AliyunPlaybackWindow.isPending(controller?.currentMediaItem?.localConfiguration?.uri?.scheme)
            if (pendingNow) {
                scheduleAliyunWindow()
                return
            }
            val code = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode ?: 0
            // 直链过期重试的身份也以**控制器当前项**为准（稳定键就是 mediaId），
            // 不能用界面状态里可能滞后的歌去解析，否则会刷错歌的地址
            val cloudId = controller?.currentMediaItem?.mediaId
                ?.let { TrackFragment.parse(it.removePrefix("aliyun:")) }
            if ((code == 401 || code == 403) && !aliyunUrlRetried && cloudId != null) {
                aliyunUrlRetried = true
                scope.launch { refreshAliyunUrl(cloudId.first, cloudId.second) }
                return
            }
            Log.w(TAG, "播放错误: ${error.errorCodeName}", error)
            logPlaybackError(error)
            val name = _player.value.current?.displayName ?: ""
            _player.update { it.copy(isPlaying = false) }
            consecutiveErrors++
            val reason = describePlaybackError(error)
            val c = controller
            val count = c?.mediaItemCount ?: 0
            // 连续失败到上限就停：一路静默跳完整张队列，用户只会看到「一直在切歌、播不了」，
            // 反而看不到真正的原因（v1.1.45）
            if (ErrorSkipPolicy.shouldSkip(count, consecutiveErrors)) {
                skippingAfterError = true
                messages.post("播放失败：$name（$reason），已跳到下一首")
                c?.seekToNextMediaItem()
                c?.play()
            } else {
                messages.post(
                    if (count > 1) ErrorSkipPolicy.stopMessage(consecutiveErrors, reason)
                    else "播放失败：$name（$reason）",
                )
            }
        }
    }

    /**
     * 把播放失败的**技术细节**写进崩溃日志（设置 → 关于与诊断 → 崩溃日志可见）。
     *
     * 为什么需要它：提示条只能给一句人话（「网络连接失败」/`ERROR_CODE_IO_UNSPECIFIED`），
     * 而排障真正需要的是「哪一层抛的、什么异常、打的哪个主机」。以前这些只进 logcat，
     * 用户在车机上根本取不到 —— v1.1.59 用户反馈「网盘歌单 3 首都 IO_UNSPECIFIED」时，
     * 手上除了这一个错误码什么都没有。
     *
     * 只记 host + path 前缀，**不记 query**：阿里直链的 query 是签名，等于临时凭据。
     */
    private fun logPlaybackError(error: androidx.media3.common.PlaybackException) {
        runCatching {
            val uri = controller?.currentMediaItem?.localConfiguration?.uri
                ?: _player.value.current?.uri
            val chain = generateSequence<Throwable>(error) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            val target = uri?.let { u ->
                buildString {
                    append(u.scheme).append("://").append(u.host)
                    u.path?.take(80)?.let { append(it) }
                    if (u.fragment?.startsWith("wt=") == true) append(" #wt(网盘直链)")
                    if (!u.query.isNullOrBlank()) append(" ?(query 已隐藏)")
                }
            } ?: "未知"
            CrashLog.record(
                app,
                "playback-error",
                buildString {
                    appendLine("code=${error.errorCodeName}")
                    appendLine("target=$target")
                    appendLine("chain=$chain")
                },
            )
        }
    }

    /**
     * 把 [androidx.media3.common.PlaybackException] 翻成一句能截图反馈的话。
     *
     * 规则在 [PlaybackErrorMessages]：HTTP 状态码优先；并行分片 / 缓存 / 本地 IO
     * 不再一律写成「网络连接失败」（歌词旁路成功、音频失败时尤其容易误导）。
     */
    private fun describePlaybackError(error: androidx.media3.common.PlaybackException): String =
        PlaybackErrorMessages.describe(error)

    // ================================================================ 播放统计

    /** 丢弃尚未落库的累计收听时长（「清空统计」前必须调用，否则它会被写回去） */
    fun resetListenAccumulator() = stats.resetListenAccumulator()

    // ================================================================ 队列持久化

    /**
     * 以 MediaController 的真实队列为准持久化。
     *
     * 队列与播放态分两张表：`queue_songs` 存顺序，`player_state` 存「当前曲 / 进度 / 循环模式」。
     * 队列整体重写（几百行的一个事务），换掉的是旧实现「把整个队列拼成一个字符串塞进
     * SharedPreferences」的做法。
     */
    private fun saveQueue() {
        val c = controller ?: return
        val rows = (0 until c.mediaItemCount).mapNotNull { i ->
            index.idOf(c.getMediaItemAt(i).mediaId)?.let { QueueSongEntity(position = i, songId = it) }
        }
        val state = snapshotPlayerState(c)
        val aliyunItems = currentQueue.mapNotNull { song ->
            val id = TrackFragment.parse(song.uri.fragment) ?: return@mapNotNull null
            val record = aliyunTracks.get(id.first, id.second)
            AliyunQueueItem(
                driveId = id.first,
                fileId = id.second,
                name = song.displayName.ifBlank { song.title },
                folderName = record?.folderName.orEmpty(),
                parentFileId = record?.parentFileId.orEmpty(),
            )
        }
        val aliyunIndex = currentQueue.indexOfFirst { TrackFragment.parse(it.uri.fragment) != null && it.stableKey == c.currentMediaItem?.mediaId }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { store.saveQueueAndState(rows, state) }
                runCatching {
                    if (aliyunItems.isEmpty()) aliyunTracks.saveQueue(null)
                    else aliyunTracks.saveQueue(
                        AliyunQueueSnapshot(
                            items = aliyunItems,
                            index = aliyunIndex.coerceAtLeast(0),
                            positionMs = c.currentPosition.coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun restoreAliyunQueue(c: MediaController): Boolean {
        val snapshot = withContext(Dispatchers.IO) { aliyunTracks.loadQueue() } ?: return false
        val auth = AliyunAuth(app)
        val drive = AliyunDrive(auth)
        val pending = snapshot.items.map { item ->
            val saved = aliyunTracks.get(item.driveId, item.fileId)
            drive.pendingSong(
                AliyunEntry(item.fileId, item.name, false, 0L),
                item.driveId,
                item.folderName,
                saved,
            )
        }
        if (pending.isEmpty()) return false
        val start = snapshot.index.coerceIn(0, pending.lastIndex)
        val songs = drive.fillWindow(pending, start)
        // 预填好的直链记进备忘录：setMediaItems 过 resolve 时直接取用，不再重取
        songs.filter { !AliyunPlaybackWindow.isPending(it.uri.scheme) }
            .forEach { CloudUrlMemo.remember(it.stableKey, it.uri.toString()) }
        val index = AliyunPlaybackWindow.firstReady(
            songs.map { AliyunPlaybackWindow.isPending(it.uri.scheme) },
            start,
        ) ?: return false
        val position = if (index == start) snapshot.positionMs else 0L
        currentQueue = songs
        c.setMediaItems(songs.map { PlaybackConnection.toMediaItem(it) }, index, position)
        c.prepare()
        queueRestored = true
        syncCurrentSong(force = true)
        Log.i(TAG, "断点续播：恢复阿里云盘队列 ${songs.size} 首，第 ${index + 1} 首")
        resumeIfWanted(c)
        return true
    }

    /** 切歌后把窗口边缘上还没有直链的歌补上。已经有地址的不重复取。 */
    private fun scheduleAliyunWindow() {
        if (currentQueue.none { TrackFragment.parse(it.uri.fragment) != null }) return
        aliyunWindowJob?.cancel()
        aliyunWindowJob = scope.launch { fillAliyunWindow() }
    }

    private suspend fun fillAliyunWindow() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index < 0 || c.mediaItemCount == 0) return
        val ordered = (0 until c.mediaItemCount).map { i ->
            val mediaId = c.getMediaItemAt(i).mediaId
            currentQueue.firstOrNull { it.stableKey == mediaId }
        }
        if (ordered.any { it == null }) return
        val songs = ordered.filterNotNull()
        if (AliyunPlaybackWindow.pendingIndices(songs.map { it.uri.scheme }, index).isEmpty()) return
        val filled = AliyunDrive(AliyunAuth(app)).fillWindow(songs, index)
        // ⚠️ 必须先把补到的直链记进备忘录，再 replaceMediaItem：
        // replace 也要跨 Binder 过服务端的 resolve，晚一步的话刚补的地址会被
        // resolve 重建回占位地址，补链等于白做（v1.1.72 的「上一曲回不去」）。
        filled.filter { !AliyunPlaybackWindow.isPending(it.uri.scheme) }
            .forEach { CloudUrlMemo.remember(it.stableKey, it.uri.toString()) }
        val c2 = controller ?: return
        for (song in filled) {
            val before = songs.firstOrNull { it.stableKey == song.stableKey } ?: continue
            if (before.uri == song.uri) continue
            val at = (0 until c2.mediaItemCount).firstOrNull { c2.getMediaItemAt(it).mediaId == song.stableKey }
                ?: continue
            val playingThis = at == c2.currentMediaItemIndex
            val pos = if (playingThis) c2.currentPosition.coerceAtLeast(0L) else 0L
            c2.replaceMediaItem(at, PlaybackConnection.toMediaItem(song))
            if (playingThis) {
                c2.seekTo(at, pos)
                c2.prepare()
                if (c2.playWhenReady) c2.play()
            }
        }
        val ready = filled.filter { !AliyunPlaybackWindow.isPending(it.uri.scheme) }
            .associateBy { it.stableKey }
        currentQueue = currentQueue.map { ready[it.stableKey] ?: it }
    }

    private suspend fun refreshAliyunUrl(driveId: String, fileId: String) {
        val drive = AliyunDrive(AliyunAuth(app))
        val url = drive.downloadUrl(driveId, fileId).getOrNull() ?: return
        val saved = aliyunTracks.get(driveId, fileId)
        val song = currentQueue.firstOrNull { it.stableKey == "aliyun:" + TrackFragment.of(driveId, fileId) }
            ?: return
        val refreshed = drive.toSong(
            com.luckylearning.windtone.data.aliyun.AliyunEntry(
                fileId,
                song.displayName.ifBlank { song.title },
                false,
                song.sizeBytes,
            ),
            url,
            driveId,
            saved?.folderName,
            saved,
        )
        CloudUrlMemo.remember(refreshed.stableKey, refreshed.uri.toString())
        val c = controller ?: return
        val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == song.stableKey } ?: return
        val position = c.currentPosition.coerceAtLeast(0L)
        c.replaceMediaItem(at, PlaybackConnection.toMediaItem(refreshed))
        c.seekTo(at, position)
        c.prepare()
        c.play()
        currentQueue = currentQueue.map { if (it.stableKey == song.stableKey) refreshed else it }
        _player.update { it.copy(current = refreshed) }
    }

    private fun enrichAliyun(song: Song) {
        if (TrackFragment.parse(song.uri.fragment) == null) return
        scope.launch {
            delay(600)
            val meta = withContext(Dispatchers.IO) { runCatching { AliyunEnricher.readHeader(song) }.getOrNull() }
                ?: return@launch
            if (_player.value.current?.stableKey != song.stableKey) return@launch
            withContext(Dispatchers.IO) { AliyunEnricher.applyHeader(app, song, meta) }
            val saved = withContext(Dispatchers.IO) {
                val id = TrackFragment.parse(song.uri.fragment) ?: return@withContext null
                aliyunTracks.get(id.first, id.second)
            }
            val updated = song.copy(
                title = saved?.title?.takeIf { it.isNotBlank() } ?: song.title,
                artist = saved?.artist ?: song.artist,
                album = saved?.album ?: song.album,
                durationMs = saved?.durationMs?.takeIf { it > 0L } ?: song.durationMs,
            )
            currentQueue = currentQueue.map { if (it.stableKey == song.stableKey) updated else it }
            _player.update { state ->
                if (state.current?.stableKey == song.stableKey) state.copy(current = updated) else state
            }
            meta.picture?.let { bytes ->
                CoverRepository.rememberRaw(updated, bytes)
                val c = controller ?: return@let
                val at = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == song.stableKey }
                    ?: return@let
                runCatching {
                    c.replaceMediaItem(at, PlaybackConnection.withArtwork(c.getMediaItemAt(at), bytes))
                }
            }
        }
    }

    /** 只写播放态（队列没变、只是切了循环 / 随机模式时用） */
    private fun savePlayerState() {
        val c = controller ?: return
        val state = snapshotPlayerState(c)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.saveState(state) } }
        }
    }

    private fun snapshotPlayerState(c: MediaController) = PlayerStateEntity(
        id = 1,
        currentSongId = c.currentMediaItem?.mediaId?.let { index.idOf(it) },
        positionMs = c.currentPosition.coerceAtLeast(0L),
        shuffle = c.shuffleModeEnabled,
        repeatMode = c.repeatMode,
        updatedAt = System.currentTimeMillis(),
    )

    // ================================================================ 连接与恢复

    private suspend fun ensureController(): MediaController? {
        controller?.let { return it }
        return runCatching { connection.connect() }.onFailure { Log.e(TAG, "连接播放服务失败", it) }
            .getOrNull()
            ?.also {
                controller = it
                it.addListener(playerListener)
                // 服务刚起来时套用一次持久化的开关
                PlayerHandle.setSkipSilence(prefs.skipSilence)
                applyPlaybackParams(it)
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

    /**
     * 断点续播：恢复**整个队列**（含顺序、当前曲目、进度与播放模式）。
     *
     * 是否顺手开始播由设置「打开应用继续上次播放」决定（v1.1.16，见 [resumeIfWanted]）；
     * 关掉时行为与老版本完全一致：摆好队列与进度，停在暂停态等用户点。
     *
     * 这个方法会被调用**多次**（曲库缓存装载后一次、扫描完成后再一次），语义是
     * 「尽力恢复，条件不具备就等下一次」—— 只有真的把队列摆上去了才置 [queueRestored]。
     * v1.1.16 之前它一进门就把标志置真，于是「曲库还没推给引擎」的那一次调用
     * 会让恢复永久失效：界面看起来只是「没恢复」，没有任何报错（详见 [restoreQueue]）。
     */
    override fun restoreQueueIfNeeded() {
        if (queueRestored || queueRestoreRunning) return
        queueRestoreRunning = true
        scope.launch {
            try {
                restoreQueue()
            } finally {
                queueRestoreRunning = false
            }
        }
    }

    /**
     * 开机后台起播（v1.1.24）：不经界面，把上次队列摆上并**直接开播**。
     *
     * 与 [restoreQueueIfNeeded] 的差别只有两点：
     *  - 曲库通常还没被界面推过来（开机时界面根本没建），所以先从数据库读一次；
     *  - 起播不看「打开应用继续上次播放」（那条开关的语义是「点开 App 时」），
     *    开机自启本身就是用户明确要求「通电就播」。用 [forceAutoplayOnRestore] 表达。
     *
     * 兜底再播一次：部分车机在 `setMediaItems` 后立刻 `play()` 会被忽略
     * （音频设备还没初始化完），等 800ms 再确认一遍能救回这一类。
     */
    override fun startPlaybackInBackground() {
        scope.launch {
            // 开机这一路有**三个竞速**，任何一个没赢都会静默失败：
            //  ① `MediaController` 还没连上（服务刚起，绑定是异步的）；
            //  ② 曲库还没从数据库读出来（冷启动时 IO 最慢）；
            //  ③ 音频设备还没就绪，`play()` 被 HAL 吞掉（车机最常见）。
            // 一次不成就放弃的话，用户看到的就是「开关打开了，开机没反应」。
            // 所以按 0s / 4s / 10s 三次重试 —— 三次的总时长仍在 20 秒内，
            // 不会让「开机没播」变成「开机十几秒后才突然出声」这种更糟的体验。
            // 重试时机抽到 BootRetryPolicy，便于单测覆盖与失败兜底判定。
            var lastReason = ""
            for ((attempt, waitMs) in BootRetryPolicy.DELAYS.withIndex()) {
                if (waitMs > 0) delay(waitMs)
                val result = runCatching { attemptBootPlayback() }
                    .getOrElse {
                        BootAttempt(it.javaClass.simpleName + ": " + (it.message ?: ""))
                    }
                if (result.failure == null) {
                    val note = listOf(
                        result.note,
                        if (attempt > 0) "第 ${attempt + 1} 次尝试成功" else "",
                    ).filter { it.isNotBlank() }.joinToString("；")
                    BootStatus.markResult(app, true, note)
                    return@launch
                }
                lastReason = result.failure
                Log.i(TAG, "开机后台播放：第 ${attempt + 1} 次未成功（${result.failure}）")
            }
            BootStatus.markResult(app, false, lastReason)
        }
    }

    /**
     * 一次完整的开机起播尝试。返回 `null` 表示成功，否则返回**人话原因**。
     *
     * 之所以把原因当返回值而不是抛异常：这些原因最终要出现在设置页的诊断卡上
     * （用户没法接 adb，只能靠界面告诉他断在哪一环），异常类型对他的价值是零。
     */
    private suspend fun attemptBootPlayback(): BootAttempt {
        val songs = withContext(Dispatchers.IO) {
            runCatching { store.loadVisibleSongs() }.getOrNull()
        } ?: return BootAttempt("读曲库失败（数据库还没就绪）")
        if (songs.isEmpty()) {
            return BootAttempt("曲库是空的（可能还没扫描完，或歌曲都在未挂载的存储上）")
        }
        if (librarySongs.isEmpty()) setLibrarySongs(songs)

        forceAutoplayOnRestore = true
        try {
            restoreQueue()
        } finally {
            forceAutoplayOnRestore = false
        }

        val c = controller ?: return BootAttempt("播放服务还没连上")
        // 从没播过任何东西的机器（刚装好就打开「开机后台播放」）：没有队列也没有
        // 「上次那一首」，恢复无从谈起。但用户要的是「通电就有歌」——此时退化成
        // 「从曲库第一首开始」比什么都不做合理；否则新装机的第一次开机永远是
        // 「未起播」，而用户根本不知道该先手动播一次。
        var note = ""
        if (c.mediaItemCount == 0) {
            currentQueue = songs
            c.setMediaItems(songs.map { PlaybackConnection.toMediaItem(it) }, 0, 0L)
            c.prepare()
            syncCurrentSong(force = true)
            note = "从曲库第一首开始（之前没有播放记录）"
        }

        applyPlaybackParams(c)
        PlayerHandle.setSkipSilence(prefs.skipSilence)
        if (!c.isPlaying) c.play()
        delay(BOOT_PLAY_CONFIRM_MS)

        val c2 = controller ?: return BootAttempt("播放会话中途断开")
        // IDLE 在 Media3 里表示「没有可播的内容 / 解码器没建起来」，
        // 与「正在缓冲」是两回事 —— 文件所在 U 盘还没挂载完就是这一档。
        if (c2.playbackState == Player.STATE_IDLE) {
            return BootAttempt("播放器没接受这首歌（存储可能还没挂载完）")
        }
        if (!c2.playWhenReady) return BootAttempt("起播被系统拒绝（音频焦点被别的应用占着？）")
        return BootAttempt(failure = null, note = note)
    }

    /** 一次开机起播的结果：[failure] 为 `null` 表示成功，[note] 是成功时的补充说明 */
    private data class BootAttempt(val failure: String?, val note: String = "")

    /** [restoreQueueIfNeeded] 的实际工作体；成功恢复后置 [queueRestored]。 */
    private suspend fun restoreQueue() {
        val c = ensureController() ?: return
        // 播放服务本来就活着（只是界面进程被系统回收，或用户从后台切回来）：
        // 队列与位置都是真的，不用重建，只按开关决定要不要把暂停态接着播下去。
        // ⚠️ 这条分支在老版本是直接 return —— 于是「开关打开但没续播」最容易
        // 出现在这里：服务活着，看起来什么都不缺，可它正暂停着。
        if (c.mediaItemCount > 0) {
            queueRestored = true
            Log.i(TAG, "断点续播：服务里已有 ${c.mediaItemCount} 首，不重建")
            // 状态不齐就补齐：引擎单例活着时 current/歌词都在，这里什么都不做；
            // 万一 current 缺失（状态被清过 / 未来重构换了归属），补一次同步。
            if (_player.value.current == null) syncCurrentSong(force = true)
            _player.value.current?.let { scheduleLyricsHeal(it.stableKey) }
            resumeIfWanted(c)
            return
        }
        // 曲库还没推过来（冷启动时曲库域的装载与这里是一条竞速链）。
        // **不置 queueRestored**：下一次调用（扫描完成后）还要再来一遍。
        if (librarySongs.isEmpty()) {
            if (restoreAliyunQueue(c)) return
            Log.i(TAG, "断点续播：曲库尚未就绪，等下一次调用")
            return
        }

        val state = withContext(Dispatchers.IO) { store.loadPlayerState() }
        val queueIds = withContext(Dispatchers.IO) { store.loadQueueSongIds() }

        // 决策交给纯函数（见 RestorePlan.kt），这里只负责把计划落到播放器上。
        when (val plan = planRestore(librarySongs, queueIds, state)) {
            RestorePlan.WaitForLibrary -> Log.i(TAG, "断点续播：曲库尚未就绪，等下一次调用")
            RestorePlan.NothingToRestore -> {
                if (!restoreAliyunQueue(c)) Log.i(TAG, "断点续播：没有可恢复的播放态")
            }
            RestorePlan.StaleState -> {
                if (queueIds.isEmpty() && restoreAliyunQueue(c)) return@restoreQueue
                queueRestored = true
                Log.i(TAG, "断点续播：上次的歌曲已不在曲库（id=${state?.currentSongId}）")
            }
            is RestorePlan.Restore -> {
                val song = plan.songs[plan.index]
                currentQueue = plan.songs
                c.setMediaItems(
                    plan.songs.map { PlaybackConnection.toMediaItem(it) },
                    plan.index,
                    plan.positionMs,
                )
                applyPersistedPlayMode(c, plan.state)
                c.prepare()
                _progress.value = PlaybackProgress(plan.positionMs, song.durationMs, 0L)
                syncCurrentSong(force = true)
                scheduleLyricsHeal(song.stableKey)
                queueRestored = true
                val how = if (plan.fromQueue) "恢复队列" else "单曲恢复（整库当队列）"
                Log.i(
                    TAG,
                    "断点续播：$how ${plan.songs.size} 首，第 ${plan.index + 1} 首，位置 ${plan.positionMs}ms",
                )
                resumeIfWanted(c)
            }
        }
    }

    /**
     * 恢复完队列后按设置决定要不要自动起播（v1.1.16）。
     *
     * 判定本身在 [shouldAutoResume]（纯函数、可单测）；这里只负责把「决定要播」
     * 落成起播前必须就绪的那几件事：套播放参数（速度 / 变调）、对齐跳过静音、再 `play()`。
     * 为什么不指望 `ensureController` 里那次套用：它发生在 `prepare()` 之前，
     * 而「自动续播」是唯一一条**没人按播放键**的起播路径 —— 参数只要有一次没跟上，
     * 用户听到的就是「1.0x 的这首歌在 1.5x 的偏好下播」，且没有任何界面动作能纠正它。
     */
    private fun resumeIfWanted(c: MediaController) {
        if (!forceAutoplayOnRestore &&
            !shouldAutoResume(prefs.autoResume, c.mediaItemCount, c.isPlaying)
        ) return
        applyPlaybackParams(c)
        PlayerHandle.setSkipSilence(prefs.skipSilence)
        c.play()
    }

    /** 恢复上次的随机 / 循环模式（界面状态由 Player.Listener 回调自动跟随） */
    private fun applyPersistedPlayMode(c: MediaController, state: PlayerStateEntity?) {
        if (state == null) return
        c.repeatMode = state.repeatMode
        c.shuffleModeEnabled = state.shuffle
    }

    // ================================================================ 播放动作

    override fun playQueue(queue: List<Song>, startIndex: Int) = queueManager.playQueue(queue, startIndex)
    override fun playShuffled(songs: List<Song>) = queueManager.playShuffled(songs)
    override fun togglePlay() = queueManager.togglePlay()
    override fun next() = queueManager.next()
    override fun previous() = queueManager.previous()
    override fun seekTo(ms: Long) = queueManager.seekTo(ms)
    override fun cyclePlayMode() = queueManager.cyclePlayMode()
    override fun playNext(song: Song) = queueManager.playNext(song)
    override fun enqueue(songs: List<Song>) = queueManager.enqueue(songs)
    override fun refreshQueue() = queueManager.refreshQueue()
    override fun jumpToQueue(index: Int) = queueManager.jumpToQueue(index)
    override fun removeQueueItem(index: Int) = queueManager.removeQueueItem(index)
    override fun moveQueueItem(from: Int, to: Int) = queueManager.moveQueueItem(from, to)

    // ---------------------------------------------------------------- 速度 / 定时 / 音效

    override fun setSpeed(speed: Float) {
        val s = settings.setSpeed(speed)
        _player.update { it.copy(speed = s) }
        scope.launch { ensureController()?.let { applyPlaybackParams(it) } }
    }

    /**
     * 变调：半音（-12..+12，0 = 原调）。
     *
     * 与变速共用同一条 `PlaybackParameters` 写入路径 —— 见 [applyPlaybackParams] 的注释：
     * 这两个参数在播放器里是**一个整体**，分别写会互相覆盖。
     */
    override fun setPitchSemitones(semitones: Int) {
        val n = semitones.coerceIn(Pitch.MIN_SEMITONES, Pitch.MAX_SEMITONES)
        settings.setPitchSemitones(n)
        _player.update { it.copy(pitchSemitones = n) }
        scope.launch { ensureController()?.let { applyPlaybackParams(it) } }
    }

    /**
     * 把「速度 + 变调」一次性写进播放器。
     *
     * ⚠️ 必须用 `setPlaybackParameters` 整体替换：Media3 的 `Player.setPlaybackSpeed()` 内部
     * 就是 `setPlaybackParameters(PlaybackParameters(speed, 1f))` —— **只调它会悄悄把变调打回原调**。
     * 表现是「调好的变调，一拖速度就弹回原调」，且不报错、不留日志。
     * 两者都为默认时直接返回，省掉一次跨进程调用。
     */
    private fun applyPlaybackParams(c: Player) {
        val speed = prefs.playbackSpeed
        val pitch = Pitch.factorOf(prefs.playbackPitchSemitones)
        if (speed == 1f && pitch == 1f) return
        c.setPlaybackParameters(PlaybackParameters(speed, pitch))
    }

    override fun sleepAfter(minutes: Int) = SleepTimer.start(minutes * 60_000L)

    override fun sleepEndOfSong() {
        // 直接问控制器要真实的进度与总长，避免依赖已从 PlayerUiState 移除的字段
        val c = controller
        val position = c?.currentPosition ?: _progress.value.positionMs
        val duration = c?.duration?.takeIf { it > 0 } ?: _progress.value.durationMs
        SleepTimer.startEndOfSong(position, duration)
    }

    /**
     * 「播完当前专辑」：把当前曲的剩余时长 + 队列里**后续同专辑**曲目的时长加起来。
     *
     * 为什么按 playlist 顺序「走到不同专辑为止」而不是全队列扫描：专辑内的曲目在队列里
     * 天然连排（用户从专辑详情页点「播放全部」时就是连续的），一旦遇到不同专辑说明这张
     * 专辑已经放完了。扫全队列会把"后面偶然又出现同一张专辑"也算进来，反而把定时拉长。
     *
     * 兜底：拿不到专辑信息（曲库里没有专辑标签的散装歌）时退化成「播完当前曲」——
     * 与其悄悄按一个错的时长定时，不如退回语义明确的那一档。
     */
    override fun sleepEndOfAlbum() {
        val c = controller
        val playlist = currentQueue
        val idx = c?.currentMediaItemIndex ?: -1
        val album = playlist.getOrNull(idx)?.album
        if (c == null || idx < 0 || album.isNullOrBlank()) {
            sleepEndOfSong()
            return
        }
        SleepTimer.start(
            SleepTimer.albumRemainingMs(
                playlist = playlist,
                index = idx,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = c.duration.takeIf { it > 0 } ?: _progress.value.durationMs,
            ),
        )
    }

    override fun cancelSleep() = SleepTimer.cancel()

    override fun setSkipSilence(enabled: Boolean) {
        settings.setSkipSilence(enabled)
        _player.update { it.copy(skipSilence = enabled) }
        // 跳过静音是 ExoPlayer 专有开关，Player 接口上没有，走进程内句柄
        PlayerHandle.setSkipSilence(enabled)
    }

    /**
     * 「打开应用继续上次播放」开关（v1.1.16）。
     *
     * 只落盘、不立即起播：这个开关描述的是**下次冷启动**的行为，
     * 用户此刻正在听的那首歌不该因为拨了一下开关就跳位置。
     */
    /** 开机后台起播（v1.1.24）：只落盘 + 更新界面状态，实际动作在开机广播里 */
    override fun setAutoPlayOnBoot(enabled: Boolean) {
        settings.setAutoPlayOnBoot(enabled)
        _player.update { it.copy(autoPlayOnBoot = enabled) }
    }

    override fun setAutoResume(enabled: Boolean) {
        settings.setAutoResume(enabled)
        _player.update { it.copy(autoResume = enabled) }
    }

    // ---------------------------------------------------------------- 播放界面

    override fun openPlayer() {
        _player.update { it.copy(playerExpanded = true) }
        refreshQueue()
    }

    override fun closePlayer() = _player.update { it.copy(playerExpanded = false) }

    // ---------------------------------------------------------------- 歌词偏移（逐曲）

    override fun setLyricOffset(deltaMs: Int) {
        val song = _player.value.current ?: return
        val songId = index.idOf(song) ?: return
        val next = (index.lyricOffsetOf(song) + deltaMs).coerceIn(-20_000, 20_000)
        persistLyricOffset(songId, next)
        _player.update { it.copy(lyricOffsetMs = next) }
    }

    override fun resetLyricOffset() {
        val song = _player.value.current ?: return
        val songId = index.idOf(song) ?: return
        persistLyricOffset(songId, 0)
        _player.update { it.copy(lyricOffsetMs = 0) }
    }

    /**
     * 把当前曲的歌词偏移直接设为某个值（「以本行为准」）。
     *
     * 与 [setLyricOffset]（增量）的区别：用户长按某一行说"我唱到这句了"时，
     * 依据是「该行时间戳」与「当前播放位置」两个绝对值，直接算出偏移比
     * 让他自己按十几次 ±100ms 靠谱得多。偏移定义见 `Lyrics.indexAt(position + offset)`。
     */
    override fun setLyricOffsetAbsolute(offsetMs: Int) {
        val song = _player.value.current ?: return
        val songId = index.idOf(song) ?: return
        val next = offsetMs.coerceIn(-20_000, 20_000)
        persistLyricOffset(songId, next)
        _player.update { it.copy(lyricOffsetMs = next) }
    }

    /** 逐曲歌词偏移：写进 `song_state`（旧实现是 Prefs 里按 hashCode 拼的散列键） */
    private fun persistLyricOffset(songId: Long, offsetMs: Int) {
        index.bump(songId) { it.copy(lyricOffsetMs = offsetMs) }
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { store.setLyricOffset(songId, offsetMs, now) }
            }
        }
    }

    override fun setLyricScale(scale: Float) {
        val s = scale.coerceIn(0.7f, 1.6f)
        prefs.lyricScale = s
        _player.update { it.copy(lyricScale = s) }
    }

    /**
     * 点歌词行是否跳转（v1.1.23，默认关）。
     *
     * 关掉后歌词区只认滚动与长按（长按校准偏移仍然可用），
     * 手指在歌词上滑一下不会再被当成「点这一句」而跳走。
     */
    /** 播放页左侧竖排是否显示「无障碍状态」按钮（v1.1.92） */
    override fun setPlayerA11yAction(enabled: Boolean) {
        prefs.playerA11yAction = enabled
        _player.update { it.copy(showA11yAction = enabled) }
    }

    override fun setLyricTapSeeks(enabled: Boolean) {
        prefs.lyricTapSeeks = enabled
        _player.update { it.copy(lyricTapSeeks = enabled) }
    }

    /** 导出用的原始歌词：带双语副行（v1.2.2 起显示也固定为中英都显示） */
    override fun lyricsForExport(): Lyrics = rawLyrics

    /** 播放页展示歌词还是封面：落盘，下次进播放页保持这一状态 */
    override fun setLyricsPaneVisible(visible: Boolean) {
        prefs.lyricsPaneVisible = visible
        _player.update { it.copy(lyricsPaneVisible = visible) }
    }

    /**
     * 车载模式：启动直进播放页 + 播放页 5 秒无操作进入沉浸态。
     *
     * v1.1.19：**打开时立刻展开播放页**。以前 `playerExpanded` 只在构造 `PlayerUiState`
     * 时按 `prefs.carMode` 读一次，用户拨完开关当次什么都不会发生（必须杀进程重开），
     * 是「车载模式开关感觉没用」的主要来源之一。关闭时不强行退出播放页 —— 正在听着歌
     * 被弹回列表页更突兀，只让「下次启动回库页」生效。
     */
    override fun setThemeMode(mode: ThemeMode) {
        // 先落盘再更新流：`MainActivity` 收流后立刻重画整棵树，
        // 流里必须是已经写进去的那个值，避免「画的是新主题、读回来是旧值」。
        settings.setThemeMode(mode)
        _themeMode.value = mode
    }

    override fun setCarMode(enabled: Boolean) {
        prefs.carMode = enabled
        // 车载音效档案（v1.1.52）：进车先快照手机侧音效值再套车载方案，出车原样还原。
        // 没选过车载方案（carSchemeId 空）时两边都不动 —— 方案在「均衡器与音效」弹层里选。
        if (enabled) AudioFx.enterCarProfile() else AudioFx.exitCarProfile()
        _player.update {
            it.copy(
                carMode = enabled,
                // 只在打开的那一下展开；关闭时保持用户当前所在页面
                playerExpanded = if (enabled) true else it.playerExpanded,
            )
        }
    }

    // ---------------------------------------------------------------- 悬浮窗设置（转发）
    //
    // 实现全部在 OverlaySettings；这里只保留接口形状。Kotlin 的接口委托表达式不能
    // 引用类体属性（构造期尚未初始化），所以用显式转发而不是 `by overlaySettings`。

    override fun setLyricOverlay(enabled: Boolean) = overlaySettings.setLyricOverlay(enabled)
    override fun setLyricOverlayLines(lines: Int) = overlaySettings.setLyricOverlayLines(lines)
    override fun setOverlayHandles(enabled: Boolean) = overlaySettings.setOverlayHandles(enabled)
    override fun setOverlayHandle(kind: Int, enabled: Boolean) =
        overlaySettings.setOverlayHandle(kind, enabled)
    override fun setLyricOverlayFullHeight(enabled: Boolean) =
        overlaySettings.setLyricOverlayFullHeight(enabled)

    override fun setLyricOverlayFullWidthPercent(percent: Int) =
        overlaySettings.setLyricOverlayFullWidthPercent(percent)

    override fun setLyricOverlayFullOffsetPercent(percent: Int) =
        overlaySettings.setLyricOverlayFullOffsetPercent(percent)

    override fun setLyricOverlayShowTitle(show: Boolean) =
        overlaySettings.setLyricOverlayShowTitle(show)

    override fun setLyricOverlayTitlePercent(percent: Int) =
        overlaySettings.setLyricOverlayTitlePercent(percent)

    override fun setLyricOverlayTextSp(textSp: Int) = overlaySettings.setLyricOverlayTextSp(textSp)

    override fun setLyricOverlayPauseMode(mode: Int) = overlaySettings.setLyricOverlayPauseMode(mode)
    override fun setLyricOverlayAlign(align: Int) = overlaySettings.setLyricOverlayAlign(align)

    override fun setLyricOverlayHiddenApps(apps: Set<String>) =
        overlaySettings.setLyricOverlayHiddenApps(apps)

    override fun setForegroundLogEnabled(enabled: Boolean) =
        overlaySettings.setForegroundLogEnabled(enabled)

    override fun setDebugUnlocked(enabled: Boolean) = overlaySettings.setDebugUnlocked(enabled)

    override fun setLyricOverlayWidthPercent(percent: Int) =
        overlaySettings.setLyricOverlayWidthPercent(percent)

    override fun setLyricOverlayTextColor(color: Int) = overlaySettings.setLyricOverlayTextColor(color)

    override fun applyOverlayPreset(preset: OverlayPreset) =
        overlaySettings.applyOverlayPreset(preset)

    // v1.1.48：自定义尺寸 / 位置 / 背景
    override fun setLyricOverlayBoxHeightPercent(percent: Int) =
        overlaySettings.setLyricOverlayBoxHeightPercent(percent)

    override fun setLyricOverlayFullHeightPercent(percent: Int) =
        overlaySettings.setLyricOverlayFullHeightPercent(percent)

    override fun setLyricOverlayXPercent(percent: Int) =
        overlaySettings.setLyricOverlayXPercent(percent)

    override fun setLyricOverlayYPercent(percent: Int) =
        overlaySettings.setLyricOverlayYPercent(percent)

    override fun setLyricOverlayFullYPercent(percent: Int) =
        overlaySettings.setLyricOverlayFullYPercent(percent)

    override fun setLyricOverlayBgColor(color: Int) = overlaySettings.setLyricOverlayBgColor(color)
    override fun setLyricOverlayBgAlphaPercent(percent: Int) =
        overlaySettings.setLyricOverlayBgAlphaPercent(percent)

    // v1.1.52：样式补全 10 项（圆角 / 边框 / 行距 / 当前行颜色 / 压暗 / 描边 / 动画 / 标题内容）
    override fun setLyricOverlayCornerRadiusDp(value: Int) =
        overlaySettings.setLyricOverlayCornerRadiusDp(value)
    override fun setLyricOverlayBorderWidthDp(value: Int) =
        overlaySettings.setLyricOverlayBorderWidthDp(value)
    override fun setLyricOverlayBorderColor(value: Int) =
        overlaySettings.setLyricOverlayBorderColor(value)
    override fun setLyricOverlayLineSpacingDp(value: Int) =
        overlaySettings.setLyricOverlayLineSpacingDp(value)
    override fun setLyricOverlayCurrentColor(value: Int) =
        overlaySettings.setLyricOverlayCurrentColor(value)
    override fun setLyricOverlayDimPercent(value: Int) =
        overlaySettings.setLyricOverlayDimPercent(value)
    override fun setLyricOverlayStrokeWidthDp(value: Int) =
        overlaySettings.setLyricOverlayStrokeWidthDp(value)
    override fun setLyricOverlayStrokeColor(value: Int) =
        overlaySettings.setLyricOverlayStrokeColor(value)
    override fun setLyricOverlayFadeMs(value: Int) =
        overlaySettings.setLyricOverlayFadeMs(value)
    override fun setLyricOverlayTitleMode(value: Int) =
        overlaySettings.setLyricOverlayTitleMode(value)

    override fun canDrawOverlay(): Boolean = overlaySettings.canDrawOverlay()
    override fun setOverlayEditing(editing: Boolean) = overlaySettings.setOverlayEditing(editing)
    override fun setAppForeground(foreground: Boolean) = overlaySettings.setAppForeground(foreground)
    override fun restoreLyricOverlay() = overlaySettings.restoreLyricOverlay()
    override fun previewOverlay() = overlaySettings.previewOverlay()
    override fun setDynamicTheme(enabled: Boolean) = overlaySettings.setDynamicTheme(enabled)
    override fun dynamicThemeEnabled(): Boolean = overlaySettings.dynamicThemeEnabled()

    // ---------------------------------------------------------------- 音频技术信息

    override fun inspectAudio(song: Song) {
        _player.update { it.copy(audioInfo = null, audioInfoLoading = true) }
        scope.launch {
            val info = withContext(Dispatchers.IO) { AudioInspector.inspect(app, song) }
            _player.update { it.copy(audioInfo = info, audioInfoLoading = false) }
        }
    }

    override fun clearAudioInfo() = _player.update { it.copy(audioInfo = null, audioInfoLoading = false) }

    // ================================================================ 内部

    /**
     * 按 MediaController 的真实队列取「接下来要播的远程歌」（v1.1.46）。
     *
     * 必须用 controller 的队列而不是 [currentQueue]：随机播放时两者顺序不同，按曲库顺序
     * 预取等于把没打算听的歌先下了一遍（费流量还挤占缓存）。
     */
    private fun upcomingSongs(c: MediaController): List<Song> {
        val count = c.mediaItemCount
        if (count <= 1) return emptyList()
        val from = c.currentMediaItemIndex + 1
        if (from >= count) return emptyList()
        // 只需要看最近几首：候选只取两首，没必要把三百首队列整个映射一遍
        val window = (from until minOf(count, from + 6)).mapNotNull { i ->
            songByMediaId(c.getMediaItemAt(i).mediaId)
        }
        return CachePrefetchPolicy.candidates(window.map { it.uri.scheme }).map { window[it] }
    }

    private fun songByMediaId(mediaId: String): Song? =
        currentQueue.firstOrNull { it.stableKey == mediaId }
            ?: librarySongs.firstOrNull { it.stableKey == mediaId }

    private fun syncCurrentSong(force: Boolean = false) {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId ?: run {
            stats.flushListen()
            _player.update { it.copy(current = null, lyrics = Lyrics.EMPTY) }
            LyricOverlayBridge.clear()
            return
        }
        if (!force && currentLyricsSong == mediaId) return
        val song = currentQueue.firstOrNull { it.stableKey == mediaId }
            ?: currentQueue.firstOrNull {
                it.uri.toString() == c.currentMediaItem?.localConfiguration?.uri?.toString()
            }
            ?: librarySongs.firstOrNull { it.stableKey == mediaId }
            ?: return

        // 并行分片只服务「当前这一首」（v1.1.47）：本地文件传 null，等于关掉这层
        ParallelDownloadRegistry.setCurrentKey(
            if (TrackFragment.parse(song.uri.fragment) == null && CachePrefetchPolicy.isRemote(song.uri.scheme)) {
                ParallelDownloadRegistry.keyOf(song.uri)
            } else {
                null
            },
        )

        stats.flushListen()
        saveQueue()
        currentLyricsSong = mediaId
        _player.update {
            it.copy(
                current = song,
                durationMs = song.durationMs,
                queueSize = c.mediaItemCount,
                lyricOffsetMs = index.lyricOffsetOf(song),
            )
        }
        startPositionTicker()
        loadLyrics(song)
        loadArtwork(song)
        enrichAliyun(song)
    }

    private fun loadLyrics(song: Song) {
        lyricsJob?.cancel()
        val seq = ++lyricsSeq
        rawLyrics = Lyrics.EMPTY
        _player.update { it.copy(lyrics = Lyrics.EMPTY, lyricsLoading = true) }
        lyricsJob = scope.launch {
            // ⚠️ 这里的 runCatching 会把 CancellationException 一并吞掉，于是「被取消的旧请求」
            //    仍会往下走一步。必须用序号挡住它，否则切歌瞬间会把旧歌（或空）的歌词写回去。
            var raw = runCatching { lyricsLoader.load(song) }.getOrDefault(Lyrics.EMPTY)
            // 网盘/在线曲：云侧车与远程内嵌都空时，尝试本机曲库同名曲（不联网搜词）
            if (raw.isEmpty) {
                val match = LocalLyricsMatch.find(librarySongs, song)
                if (match != null && match.stableKey != song.stableKey) {
                    raw = runCatching { lyricsLoader.load(match) }.getOrDefault(Lyrics.EMPTY)
                }
            }
            if (seq != lyricsSeq) return@launch
            rawLyrics = raw
            _player.update {
                it.copy(lyrics = raw, lyricsLoading = false)
            }
        }
    }

    /**
     * 断点续播后的**歌词自愈**（v1.1.18）。
     *
     * 真机上出现过：重开后当前曲与队列都恢复了，歌词却是空的，下一曲再切回来才有 ——
     * 模拟器上复现不了。恢复链路里 [syncCurrentSong] → [loadLyrics] 只跑一次，
     * 那一次读到空就永远停在空态（下一次换歌才会重新加载）。而冷启动那一刻
     * 文件系统 / MediaStore / 外置存储可能还没就绪（车机尤其明显，杀进程也狠），
     * 同一个文件隔几秒再读就正常了。
     *
     * 与其猜具体哪个环节慢，不如按**结果**自愈：恢复完成后分三个周期复查，
     * 当前曲的歌词仍是「空且不在加载中」就重载一次。任何一次换歌都会更新
     * [currentLyricsSong]，自愈随即失效 —— 它只盯着恢复时那一首。
     */
    private fun scheduleLyricsHeal(targetKey: String) {
        healLyricsJob?.cancel()
        healLyricsJob = scope.launch {
            for (delayMs in longArrayOf(2_000L, 5_000L, 10_000L)) {
                delay(delayMs)
                // 换过歌（用户主动切 / 自动接续）：自愈只管恢复时那一首，收手
                if (currentLyricsSong != targetKey) return@launch
                val st = _player.value
                if (st.lyricsLoading) continue // 上一次重载还没回来，下个周期再看
                if (st.lyrics.lines.isNotEmpty()) return@launch // 已经有了
                val cur = st.current ?: return@launch
                Log.i(TAG, "歌词自愈：当前曲歌词为空，重载一次（${cur.displayName}）")
                loadLyrics(cur)
            }
        }
    }

    /**
     * 睡眠定时的「渐弱收尾」。
     *
     * 文档一直承诺「淡出渐弱」，而历史实现是到点直接 `pause` —— 睡着前最后一秒
     * 声音被硬生生掐断，在安静环境里很突兀。这里在到点前 [SLEEP_FADE_MS]
     * 内把 [Player.volume] 线性压到 0，再交给 `SleepTimer.fireAction` 暂停，
     * 暂停后剩余时间归零 → 音量自动回到 1（否则下一次手动播放会**全程静音**）。
     *
     * 音量是纯播放域概念：全工程只有这里写 `volume`，不存在与用户设置的「系统音量」打架。
     */
    private fun applySleepFade(remainingMs: Long) {
        val c = controller ?: return
        val target = when {
            remainingMs <= 0L -> 1f
            remainingMs >= SLEEP_FADE_MS -> 1f
            else -> remainingMs.toFloat() / SLEEP_FADE_MS
        }
        if (c.volume != target) c.volume = target
    }

    /** 把封面字节塞进通知栏/锁屏的媒体元数据（只替换元数据，不打断播放），并做主题取色 */
    private fun loadArtwork(song: Song) {
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val bytes = CoverRepository.loadRaw(app, song) ?: run {
                _themeSeed.value = null
                return@launch
            }
            // 主题取色（与封面解码并行做，失败不影响播放）
            if (prefs.dynamicTheme) {
                val seed = withContext(Dispatchers.Default) { ThemeColorExtractor.extract(bytes) }
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
     * 关键设计：进度只写 [_progress]，不再写 [_player]。
     * 原先每 250ms 更新一次 PlayerUiState，而主界面顶层订阅了它，
     * 导致整个界面每秒被重组 4 次——这是 v2.1 最大的性能问题。
     * 同时把进度落盘从「每 250ms」降频到「每 5 秒」。
     *
     * 计时器**会在退到后台且没在播时自行结束**（应用级单例没有 `onCleared` 可依赖，
     * 不能像以前那样靠界面销毁来停表）；回到前台或重新播放时由
     * [setAppForeground] / [onIsPlayingChanged] 重新拉起。
     */
    private fun startPositionTicker() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            var lastSavedAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val c = controller ?: break
                val pos = c.currentPosition.coerceAtLeast(0L)
                val buffered = c.bufferedPosition.coerceAtLeast(0L)
                val rawDuration = c.duration
                val duration = if (rawDuration > 0) rawDuration else (_player.value.current?.durationMs ?: 0L)
                val playing = c.isPlaying

                _progress.value = PlaybackProgress(pos, duration, buffered)

                val now = SystemClock.elapsedRealtime()

                // 后台预取（v1.1.46）：缓冲领先 20 秒（或已暂停）才动手，每 3 秒最多规划一次。
                // 之所以放在这条 ticker 里：这里刚好有最新 position / buffered / isPlaying，
                // 不必再开一条计时器。预取失败一律静默，不影响播放。
                if (now - lastPrefetchPlanAt >= CachePrefetchPolicy.PLAN_INTERVAL_MS) {
                    lastPrefetchPlanAt = now
                    // 并行分片正在吃带宽时不再预取：两条路抢同一根管子，只会两边都慢
                    if (!ParallelDownloadRegistry.isActive() &&
                        CachePrefetchPolicy.shouldStart(
                            playing,
                            buffered - pos,
                            // 缺口指播放位置往前还不够 20 秒，不是「整首歌还没下完」。
                            // 缓冲窗口最多 120 秒，用文件结尾判断会让后面两首永远等不到预取。
                            currentTailOpen = duration > 0L &&
                                buffered + 1_000L < duration &&
                                buffered - pos < CachePrefetchPolicy.MIN_AHEAD_MS,
                        )
                    ) {
                        runCatching { CachePrefetcher.plan(app, upcomingSongs(c)) }
                    }
                }

                if (pos > 0 && now - lastSavedAt >= 5_000L) {
                    lastSavedAt = now
                    val mediaId = c.currentMediaItem?.mediaId
                    val id = mediaId?.let { TrackFragment.parse(it.removePrefix("aliyun:")) }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { store.updatePosition(pos, System.currentTimeMillis()) }
                            if (id != null && duration > 0L) {
                                runCatching { aliyunTracks.rememberDuration(id.first, id.second, duration) }
                            }
                        }
                    }
                }

                stats.accumulateListening(c.currentMediaItem?.mediaId, playing)
                // 睡眠定时淡出：与进度同频（播放中 200ms）把音量拉下来，比 500ms 一档的
                // 剩余时间流更细腻；定时未开启时只是一个常数比较。
                applySleepFade(SleepTimer.remainingMs.value)
                // 悬浮歌词：与进度同频驱动，去重在 LyricOverlayBridge 内部按内容比对完成。
                // 转发逻辑抽到 OverlayTickHelper，开关关闭时直接跳过。
                if (prefs.lyricOverlay) OverlayTickHelper.publishLyric(pos, playing, _player.value)
                // 顺便看一眼服务还在不在（进程被 ROM 杀掉的情况）——带 30 秒节流，
                // 正常情况下就是读一个 volatile boolean。决策抽到 OverlayTickHelper。
                if (OverlayTickHelper.shouldReviveOverlay(
                        prefs.lyricOverlay, LyricOverlayService.serviceAlive,
                        lastOverlayReviveAt, now, OVERLAY_REVIVE_INTERVAL_MS,
                    )
                ) {
                    lastOverlayReviveAt = now
                    LyricOverlayBridge.sync(app, true)
                }

                if (!playing && !appForeground) break
                // 播放中 200ms 刷新，暂停时降到 1s，避免空转耗电
                delay(if (c.isPlaying) 200L else 1_000L)
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackEngine"

        /**
         * 悬浮窗服务被系统杀掉后的重建节流间隔。
         *
         * 有节流才敢在 ticker 里无脑调用：ROM 若持续拒绝挂窗（比如授权被悄悄撤销），
         * 没有节流会退化成「每 200ms 尝试启动一次前台服务」的灾难。
         */
        private const val OVERLAY_REVIVE_INTERVAL_MS = 30_000L

        /** 睡眠定时到点前的渐弱时长：10 秒内线性降为 0（文档承诺的「淡出渐弱」） */
        private const val SLEEP_FADE_MS = 10_000L
    }
}
