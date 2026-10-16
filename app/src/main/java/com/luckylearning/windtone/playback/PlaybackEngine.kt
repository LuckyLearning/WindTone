package com.luckylearning.windtone.playback

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.HOUR_MS
import com.luckylearning.windtone.data.LyricsLoader
import com.luckylearning.windtone.data.MessageBus
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SongIndex
import com.luckylearning.windtone.data.ThemeColorExtractor
import com.luckylearning.windtone.data.db.ListenLogEntity
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.db.QueueSongEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.lyrics.OverlayContentResolver
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.overlay.LyricOverlayService
import com.luckylearning.windtone.state.PlayMode
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.QueueEntry
import com.luckylearning.windtone.state.playModeOf
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
    private val db: MusicDb,
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
            lyricsPaneVisible = prefs.lyricsPaneVisible,
            carMode = prefs.carMode,
            lyricOverlay = prefs.lyricOverlay,
            lyricOverlayLines = prefs.lyricOverlayLines,
            lyricOverlayTextScale = prefs.lyricOverlayTextScale,
            lyricOverlayBackdrop = prefs.lyricOverlayBackdrop,
            lyricOverlayPauseMode = prefs.lyricOverlayPauseMode,
            lyricOverlayAlign = prefs.lyricOverlayAlign,
            // v1.1.4 新增：悬浮窗宽度与文字颜色
            lyricOverlayWidthPercent = prefs.lyricOverlayWidthPercent,
            lyricOverlayTextColor = prefs.lyricOverlayTextColor,
            // 车载模式：启动直接进播放页（跳过库主页）
            playerExpanded = prefs.carMode,
            speed = prefs.playbackSpeed,
            pitchSemitones = prefs.playbackPitchSemitones,
            skipSilence = prefs.skipSilence,
        )
    )
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    /** 高频播放进度：独立于 [player]，避免每 200ms 触发整界面重组 */
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** 主题取色（当前封面主色；dynamicTheme 关闭时为 null） */
    private val _themeSeed = MutableStateFlow<Int?>(null)
    val themeSeed: StateFlow<Int?> = _themeSeed.asStateFlow()

    /** 桌面悬浮窗运行状态（设置页展示；失败时由 init 里的收集器做回退） */
    val overlayHealth: StateFlow<LyricOverlayBus.Health> = LyricOverlayBridge.health

    // ------------------------------------------------------------------ 内部状态

    private var controller: MediaController? = null

    /** 正在播放的队列（`playQueue` / 恢复时设定） */
    private var currentQueue: List<Song> = emptyList()

    /** 曲库当前列表：解析 mediaId → Song 时的兜底来源，由曲库域推过来 */
    private var librarySongs: List<Song> = emptyList()

    private var lyricsJob: Job? = null
    private var artworkJob: Job? = null
    private var positionJob: Job? = null
    private var previewJob: Job? = null
    private var currentLyricsSong: String? = null
    private var consecutiveErrors = 0

    private var queueRestored = false
    private var appForeground = true

    // 收听时长统计：仅在播放中累加，切歌 / 暂停时结算
    private var listenForKey: String? = null
    private var listenAccumMs = 0L
    private var lastTickAt = 0L

    // 播放计数去重
    private var lastCountedMediaId: String? = null
    private var lastCountedAt = 0L

    /** 上一次尝试把悬浮窗服务拉回来的时间，用于 30 秒节流（见 [reviveOverlayIfNeeded]） */
    private var lastOverlayReviveAt = 0L

    init {
        AudioFx.init(app)
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
        scope.launch {
            LyricOverlayBridge.exitEditingRequests.collect {
                if (_player.value.overlayEditing) {
                    setOverlayEditing(false)
                    messages.post("悬浮歌词已自动锁定位置，需要再调整可重新点「调整悬浮歌词位置」")
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
            consecutiveErrors = 0
            syncCurrentSong()
            mediaItem?.mediaId?.takeIf { shouldCountPlay(it) }?.let { recordPlay(it) }
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
            Log.w(TAG, "播放错误: ${error.errorCodeName}", error)
            val name = _player.value.current?.displayName ?: ""
            _player.update { it.copy(isPlaying = false) }
            consecutiveErrors++
            val c = controller
            val count = c?.mediaItemCount ?: 0
            if (count > 1 && consecutiveErrors < count) {
                messages.post("播放失败：$name，已跳到下一首")
                c?.seekToNextMediaItem()
                c?.play()
            } else {
                messages.post("播放失败：$name")
            }
        }
    }

    // ================================================================ 播放统计

    /**
     * 是否把这次曲目切换计为一次播放。
     *
     * `replaceMediaItem`（回填封面）同样会触发 onMediaItemTransition，
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
     * 必须先 ensure（INSERT OR IGNORE）再 bump——历史实现只有 UPDATE，
     * 没收藏过的歌永远写不进统计表。
     */
    private fun recordPlay(mediaId: String) {
        val songId = index.idOf(mediaId) ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching {
                    val dao = db.songStateDao()
                    dao.ensure(songId, now)
                    dao.bump(songId, now)
                }
            }
            index.bump(songId) { it.copy(playCount = it.playCount + 1, lastPlayedAt = now) }
        }
    }

    /** 把累计的收听时长写回数据库（切歌 / 暂停 / 落盘时调用） */
    private fun flushListen() {
        val key = listenForKey ?: return
        val ms = listenAccumMs
        listenAccumMs = 0L
        if (ms < 1000L) return
        val songId = index.idOf(key) ?: return
        // 收听报告的小时桶按**本次结算时刻**归属（一次累计最多 30 秒，
        // 恰好跨整点的情况误差可以忽略）
        val hour = System.currentTimeMillis() / HOUR_MS
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching {
                    val dao = db.songStateDao()
                    dao.ensure(songId, now)
                    dao.addPlayedMs(songId, ms, now)
                    // 收听日志：记进小时桶，并顺手裁掉保留期之外的桶（表大小恒定有界）
                    val log = db.listenLogDao()
                    log.add(hour, ms)
                    log.prune(hour - ListenLogEntity.RETENTION_DAYS * 24L)
                }
            }
            index.bump(songId) { it.copy(playedMs = it.playedMs + ms) }
        }
    }

    /** 丢弃尚未落库的累计收听时长（「清空统计」前必须调用，否则它会被写回去） */
    fun resetListenAccumulator() {
        listenAccumMs = 0L
    }

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
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.queueDao().replaceAll(rows)
                    db.playerStateDao().put(state)
                }
            }
        }
    }

    /** 只写播放态（队列没变、只是切了循环 / 随机模式时用） */
    private fun savePlayerState() {
        val c = controller ?: return
        val state = snapshotPlayerState(c)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { db.playerStateDao().put(state) } }
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

    /** 断点续播：恢复**整个队列**（含顺序、当前曲目、进度与播放模式），但不自动播放 */
    override fun restoreQueueIfNeeded() {
        if (queueRestored) return
        queueRestored = true
        scope.launch {
            val c = ensureController() ?: return@launch
            if (c.mediaItemCount > 0) return@launch
            val songs = librarySongs
            if (songs.isEmpty()) return@launch

            val state = withContext(Dispatchers.IO) { db.playerStateDao().get() }
            val position = (state?.positionMs ?: 0L).coerceAtLeast(0L)

            // 1) 优先整队列恢复
            val queueIds = withContext(Dispatchers.IO) { db.queueDao().songIds() }
            if (queueIds.isNotEmpty()) {
                val byId = songs.associateBy { it.dbId }
                val restored = queueIds.mapNotNull { byId[it] }
                if (restored.isNotEmpty()) {
                    val index = restored.indexOfFirst { it.dbId == state?.currentSongId }.takeIf { it >= 0 } ?: 0
                    currentQueue = restored
                    c.setMediaItems(restored.map { PlaybackConnection.toMediaItem(it) }, index, position)
                    applyPersistedPlayMode(c, state)
                    c.prepare()
                    _progress.value = PlaybackProgress(position, restored[index].durationMs, 0L)
                    syncCurrentSong(force = true)
                    return@launch
                }
            }

            // 2) 退化为单曲恢复（只记得当前曲）：整库当队列
            val currentId = state?.currentSongId ?: return@launch
            val idx = songs.indexOfFirst { it.dbId == currentId }
            if (idx < 0) return@launch
            currentQueue = songs
            c.setMediaItems(songs.map { PlaybackConnection.toMediaItem(it) }, idx, position)
            applyPersistedPlayMode(c, state)
            c.prepare()
            _progress.value = PlaybackProgress(position, songs[idx].durationMs, 0L)
            syncCurrentSong(force = true)
        }
    }

    /** 恢复上次的随机 / 循环模式（界面状态由 Player.Listener 回调自动跟随） */
    private fun applyPersistedPlayMode(c: MediaController, state: PlayerStateEntity?) {
        if (state == null) return
        c.repeatMode = state.repeatMode
        c.shuffleModeEnabled = state.shuffle
    }

    // ================================================================ 播放动作

    override fun playQueue(queue: List<Song>, startIndex: Int) {
        startQueue(queue, startIndex, shuffled = false)
    }

    /**
     * 随机播放一整批歌（桌面启动器快捷方式「随机播放」）。
     *
     * 与 [playQueue] 只差一件事：**先**把「随机 + 列表循环」两个开关落定，再起播。
     * 不能写成 `playQueue(...)` 之后另起一个协程去改模式——两个协程谁先跑完不确定，
     * 用户会听到"第一首按原顺序播、之后才乱序"的半随机状态。
     *
     * 起始下标交给 `setMediaItems` 的 shuffle 逻辑处理（Media3 会在乱序后
     * 记住当前项），这里仍传 0，避免我们自己再算一遍顺序反而和播放器不一致。
     */
    override fun playShuffled(songs: List<Song>) {
        startQueue(songs, 0, shuffled = true)
    }

    private fun startQueue(queue: List<Song>, startIndex: Int, shuffled: Boolean) {
        scope.launch {
            val c = ensureController() ?: return@launch
            if (queue.isEmpty()) return@launch
            if (shuffled) {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = true
            }
            val i = startIndex.coerceIn(0, queue.size - 1)
            currentQueue = queue
            c.setMediaItems(queue.map { PlaybackConnection.toMediaItem(it) }, i, 0L)
            c.prepare()
            c.play()
            applyPlaybackParams(c)
            _progress.value = PlaybackProgress(0L, queue[i].durationMs, 0L)
            _player.update {
                it.copy(
                    queueSize = queue.size,
                    // 显式写回：`onShuffleModeEnabledChanged` 只有真的变化时才回调，
                    // 「本来就是随机 + 用户又点了一次随机播放」时不会触发，界面上的
                    // 模式图标就会停在上一档。
                    repeatMode = c.repeatMode,
                    shuffle = c.shuffleModeEnabled,
                )
            }
            saveQueue()
            syncCurrentSong(force = true)
            startPositionTicker()
        }
    }

    override fun togglePlay() {
        scope.launch {
            val c = ensureController() ?: return@launch
            if (c.mediaItemCount == 0) {
                val list = librarySongs
                if (list.isNotEmpty()) playQueue(list, 0)
                return@launch
            }
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    override fun next() {
        scope.launch { ensureController()?.seekToNextMediaItem() }
    }

    override fun previous() {
        scope.launch {
            val c = ensureController() ?: return@launch
            if (c.currentPosition > 3000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
        }
    }

    override fun seekTo(ms: Long) {
        scope.launch {
            val c = ensureController() ?: return@launch
            val target = ms.coerceIn(0L, c.duration.coerceAtLeast(0L))
            c.seekTo(target)
            _progress.update { it.copy(positionMs = target) }
        }
    }

    /**
     * 播放模式轮换：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序。
     *
     * 合并到播放页的同一个按钮上，不再让「随机」和「循环」各占一个位置。
     */
    override fun cyclePlayMode() {
        scope.launch {
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
    }

    override fun playNext(song: Song) {
        scope.launch {
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
            messages.post("已加入播放队列：${song.title}")
        }
    }

    /**
     * 批量追加到播放队列（多选态的「加入队列」）。
     *
     * 已在队列里的歌**跳过**：批量操作最容易踩的坑就是连点两次之后队列里
     * 出现一堆重复条目，用户还得逐个删。队列为空时顺带把第一首变成当前曲目
     * （否则用户加了歌却按不动播放）。
     */
    override fun enqueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        scope.launch {
            val c = ensureController() ?: return@launch
            val existing = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val fresh = songs.filterNot { it.stableKey in existing }
            if (fresh.isEmpty()) {
                messages.post("所选歌曲都在播放队列里了")
                return@launch
            }
            c.addMediaItems(fresh.map { PlaybackConnection.toMediaItem(it) })
            _player.update { it.copy(queueSize = c.mediaItemCount) }
            saveQueue()
            refreshQueue()
        }
    }

    // ---------------------------------------------------------------- 队列抽屉

    override fun refreshQueue() {
        scope.launch {
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

    override fun jumpToQueue(index: Int) {
        scope.launch {
            val c = ensureController() ?: return@launch
            c.seekToDefaultPosition(index)
            c.play()
            saveQueue()
        }
    }

    override fun removeQueueItem(index: Int) {
        scope.launch {
            val c = ensureController() ?: return@launch
            if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
            saveQueue()
            refreshQueue()
        }
    }

    override fun moveQueueItem(from: Int, to: Int) {
        scope.launch {
            val c = ensureController() ?: return@launch
            if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
                c.moveMediaItem(from, to)
                saveQueue()
                refreshQueue()
            }
        }
    }

    // ---------------------------------------------------------------- 速度 / 定时 / 音效

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        prefs.playbackSpeed = s
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
        prefs.playbackPitchSemitones = n
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
        prefs.skipSilence = enabled
        _player.update { it.copy(skipSilence = enabled) }
        // 跳过静音是 ExoPlayer 专有开关，Player 接口上没有，走进程内句柄
        PlayerHandle.setSkipSilence(enabled)
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
                runCatching {
                    val dao = db.songStateDao()
                    dao.ensure(songId, now)
                    dao.setLyricOffset(songId, offsetMs, now)
                }
            }
        }
    }

    override fun setLyricScale(scale: Float) {
        val s = scale.coerceIn(0.7f, 1.6f)
        prefs.lyricScale = s
        _player.update { it.copy(lyricScale = s) }
    }

    /** 播放页展示歌词还是封面：落盘，下次进播放页保持这一状态 */
    override fun setLyricsPaneVisible(visible: Boolean) {
        prefs.lyricsPaneVisible = visible
        _player.update { it.copy(lyricsPaneVisible = visible) }
    }

    /** 车载模式：启动直进播放页 + 播放页 5 秒无操作进入沉浸态 */
    override fun setCarMode(enabled: Boolean) {
        prefs.carMode = enabled
        _player.update { it.copy(carMode = enabled) }
    }

    /**
     * 桌面悬浮歌词开关。
     *
     * 落盘 + 更新界面状态 + 同步起停悬浮窗服务。
     * 若用户开着开关但尚未授权「出现在其他应用上」，`sync` 内部会拒绝启动，
     * 设置页据此把开关弹回并提示去授权。
     *
     * 刚打开时会启动一段**前台预览**（见 [startOverlayPreview]）：
     * 按设计悬浮窗只在 App 退到后台时显示，而在设置页里打开的这一刻
     * App 恰恰在前台 —— 没有预览的话用户点完开关看不到任何变化，
     * 会直接把功能判定为「没有效果」。
     */
    override fun setLyricOverlay(enabled: Boolean) {
        prefs.lyricOverlay = enabled
        _player.update { it.copy(lyricOverlay = enabled) }
        LyricOverlayBridge.sync(app, enabled)
        if (enabled) {
            // 刚打开时立刻按当前进度填一行，不必等下一个 tick
            val c = controller
            if (c != null) publishLyricToOverlay(c.currentPosition.coerceAtLeast(0L), c.isPlaying)
            startOverlayPreview()
        }
    }

    /**
     * 桌面悬浮歌词一次显示几行：1 = 只显示当前行，3 = 当前行 + 上下各一行上下文。
     *
     * 只改配置，不重启服务：服务每次收到新歌词时自己读 Prefs 决定排版，
     * 所以下一次歌词刷新（最多几十毫秒）就会按新行数显示，
     * 顺便给一次前台预览让用户当场看见效果。
     */
    override fun setLyricOverlayLines(lines: Int) {
        val normalized = if (lines == 3) 3 else 1
        prefs.lyricOverlayLines = normalized
        _player.update { it.copy(lyricOverlayLines = normalized) }
        previewOverlayAfterConfigChange()
    }

    /** 悬浮歌词字号档位：0 小 / 1 中 / 2 大 */
    override fun setLyricOverlayTextScale(scale: Int) {
        val normalized = scale.coerceIn(0, 2)
        prefs.lyricOverlayTextScale = normalized
        _player.update { it.copy(lyricOverlayTextScale = normalized) }
        previewOverlayAfterConfigChange()
    }

    /** 悬浮歌词底板：0 无 / 1 半透明 / 2 深色实底 */
    override fun setLyricOverlayBackdrop(mode: Int) {
        val normalized = mode.coerceIn(0, 2)
        prefs.lyricOverlayBackdrop = normalized
        _player.update { it.copy(lyricOverlayBackdrop = normalized) }
        previewOverlayAfterConfigChange()
    }

    /** 暂停时的表现：0 淡出 / 1 隐藏 */
    override fun setLyricOverlayPauseMode(mode: Int) {
        val normalized = mode.coerceIn(0, 1)
        prefs.lyricOverlayPauseMode = normalized
        _player.update { it.copy(lyricOverlayPauseMode = normalized) }
        previewOverlayAfterConfigChange()
    }

    /**
     * 悬浮歌词水平对齐：0 居中 / 1 居左。
     *
     * 与其它外观项的区别：它同时改**文字对齐**与**窗口重心**，所以服务侧
     * 收到外观版本号后还会顺带重摆一次窗口位置（见 `applyAppearance`）。
     * 服务每帧自己读 Prefs，这里只负责通知 + 预览。
     */
    override fun setLyricOverlayAlign(align: Int) {
        val normalized = align.coerceIn(0, 1)
        prefs.lyricOverlayAlign = normalized
        _player.update { it.copy(lyricOverlayAlign = normalized) }
        previewOverlayAfterConfigChange()
    }

    /**
     * 悬浮窗宽度（屏幕宽度百分比，1..100）。
     *
     * 服务侧在 `applyAppearance` 里改 `LayoutParams.width` 并**重摆一次位置**：
     * 夹取上限是「屏宽 − 窗口宽」，窗口变宽后能移动的余量会变小。
     */
    override fun setLyricOverlayWidthPercent(percent: Int) {
        val normalized = percent.coerceIn(
            SettingsDefaults.OVERLAY_WIDTH_RANGE.first,
            SettingsDefaults.OVERLAY_WIDTH_RANGE.last,
        )
        prefs.lyricOverlayWidthPercent = normalized
        _player.update { it.copy(lyricOverlayWidthPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    /** 悬浮窗歌词文字颜色 */
    override fun setLyricOverlayTextColor(color: Int) {
        prefs.lyricOverlayTextColor = color
        _player.update { it.copy(lyricOverlayTextColor = color) }
        previewOverlayAfterConfigChange()
    }

    /**
     * 外观 / 排版配置改完的统一收尾。
     *
     * 服务是独立存活的，光写 Prefs 它不知道（没有新歌词就不会重绘），
     * 所以这里做两件事：通知重绘 + 给一次前台预览。
     * 用户改完字号 / 底板 / 行数能**当场**看到效果，不用退到桌面去验证。
     */
    private fun previewOverlayAfterConfigChange() {
        LyricOverlayBridge.bumpAppearance()
        if (!prefs.lyricOverlay) return
        val c = controller
        if (c != null) publishLyricToOverlay(c.currentPosition.coerceAtLeast(0L), c.isPlaying)
        startOverlayPreview()
    }

    /**
     * 前台预览：开启开关后让悬浮窗在前台也显示一小段时间。
     *
     * 与「调整位置」复用同一条可见性豁免，只是不进入可拖动状态。
     */
    private fun startOverlayPreview() {
        previewJob?.cancel()
        LyricOverlayBridge.setPreview(true)
        previewJob = scope.launch {
            delay(OVERLAY_PREVIEW_MS)
            LyricOverlayBridge.setPreview(false)
        }
    }

    /**
     * 重新触发一次前台预览（设置页自检对话框的「显示预览」按钮）。
     *
     * 用户报"没反应"时，这一个按钮就能把「窗口到底能不能挂上」与
     * 「是不是只是被前台隐藏」区分开——不必退到桌面、不必重装。
     */
    override fun previewOverlay() {
        if (!prefs.lyricOverlay) return
        LyricOverlayBridge.sync(app, true)
        startOverlayPreview()
    }

    /** 悬浮歌词是否已获得「出现在其他应用上」权限 */
    override fun canDrawOverlay(): Boolean = LyricOverlayBridge.canDraw(app)

    /**
     * 进入 / 退出悬浮歌词的「调整位置」模式。
     *
     * 进入后窗口临时可拖动（吃触摸），退出后位置落盘并恢复点击穿透。
     * 只更新会话级状态，不落盘——「是否正在调整」不是需要记住的偏好。
     */
    override fun setOverlayEditing(editing: Boolean) {
        LyricOverlayBridge.setEditing(editing)
        _player.update { it.copy(overlayEditing = editing) }
    }

    /**
     * App 前后台切换时同步给悬浮窗，并决定进度计时器是否该歇下来。
     *
     * 悬浮窗只在 App 退到后台时显示；回到前台时用户看的是播放页歌词，
     * 桌面字幕属于重复信息，直接隐藏。
     */
    override fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        LyricOverlayBridge.setAppForeground(foreground)
        if (foreground) startPositionTicker()
    }

    /**
     * App 启动时对齐一次悬浮窗服务状态。
     *
     * 场景：用户上次开着悬浮歌词，但中途在系统设置里撤了授权，
     * 或者车机重启后服务被系统清掉——启动时对一次账，避免开关与实际不符。
     */
    override fun restoreLyricOverlay() {
        val enabled = prefs.lyricOverlay && canDrawOverlay()
        if (prefs.lyricOverlay && !enabled) {
            // 授权已被撤销：开关回置为关，避免界面骗人
            setLyricOverlay(false)
        } else {
            LyricOverlayBridge.sync(app, enabled)
        }
    }

    override fun setDynamicTheme(enabled: Boolean) {
        prefs.dynamicTheme = enabled
        if (!enabled) _themeSeed.value = null
    }

    override fun dynamicThemeEnabled(): Boolean = prefs.dynamicTheme

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
            ?: currentQueue.firstOrNull {
                it.uri.toString() == c.currentMediaItem?.localConfiguration?.uri?.toString()
            }
            ?: librarySongs.firstOrNull { it.stableKey == mediaId }
            ?: return

        flushListen()
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
    }

    private fun loadLyrics(song: Song) {
        lyricsJob?.cancel()
        _player.update { it.copy(lyrics = Lyrics.EMPTY, lyricsLoading = true) }
        lyricsJob = scope.launch {
            val lyrics = runCatching { lyricsLoader.load(song) }.getOrDefault(Lyrics.EMPTY)
            _player.update { it.copy(lyrics = lyrics, lyricsLoading = false) }
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
                if (pos > 0 && now - lastSavedAt >= 5_000L) {
                    lastSavedAt = now
                    // 进度落库：只更新 player_state 唯一那一行（单行 UPDATE，开销极小）
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { db.playerStateDao().updatePosition(pos, System.currentTimeMillis()) }
                        }
                    }
                }

                accumulateListening(c.currentMediaItem?.mediaId, playing)
                // 睡眠定时淡出：与进度同频（播放中 200ms）把音量拉下来，比 500ms 一档的
                // 剩余时间流更细腻；定时未开启时只是一个常数比较。
                applySleepFade(SleepTimer.remainingMs.value)
                // 悬浮歌词：与进度同频驱动，内部按「行文字是否变化」去重，
                // 未开启开关时是纯字段比较，开销可忽略。
                publishLyricToOverlay(pos, playing)
                // 顺便看一眼服务还在不在（进程被 ROM 杀掉的情况）——内部带 30 秒节流，
                // 正常情况下就是读一个 volatile boolean。
                reviveOverlayIfNeeded()

                if (!playing && !appForeground) break
                // 播放中 200ms 刷新，暂停时降到 1s，避免空转耗电
                delay(if (c.isPlaying) 200L else 1_000L)
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
     * 「该显示哪一句」交给 [OverlayContentResolver]——前奏回落歌名、间奏回溯上一句、
     * 上下文行、双语副行都由它一次算清；这里只负责把播放位置和曲目信息喂进去。
     * 好处是那段最容易出错、也最需要测试的逻辑，终于能在 JVM 单测里跑。
     *
     * **每 tick 都推**，去重放在 [LyricOverlayBridge] 内部按内容比对完成。这样暂停 /
     * 恢复播放导致的透明度变化也能立刻反映到悬浮窗上，而纯文字不变时不会真的去动 View。
     *
     * 无歌词（纯音乐 / 未匹配到）时不会推送空内容——resolver 会回落成歌名，
     * 真的连歌名都没有时才算「没东西可显示」，窗口保持隐藏。
     */
    private fun publishLyricToOverlay(positionMs: Long, playing: Boolean) {
        if (!prefs.lyricOverlay) return
        val state = _player.value
        val resolved = OverlayContentResolver.resolve(
            lyrics = state.lyrics,
            positionMs = positionMs,
            offsetMs = state.lyricOffsetMs,
            title = state.current?.title.orEmpty(),
        )
        // 不再下发播放位置：它唯一的用途是逐字染色（v1.1.8 已移除）。位置每 200ms
        // 变一次，下发它会把「内容不变就不重组」的去重彻底废掉。
        LyricOverlayBridge.publish(resolved = resolved, playing = playing)
    }

    /**
     * 悬浮窗服务被系统杀掉之后把它拉回来。
     *
     * 为什么必须有：`START_STICKY` 在 Android 12+ 的后台限制下并不可靠，
     * 而 `sync()` 原先只在「App 启动 / 开关切换 / 自检 / 预览」四处被调用——
     * 于是长途导航途中服务一旦被杀，用户侧就是「开关明明开着，字幕再也没出现过」，
     * 且没有任何自救路径。
     *
     * 节流 30 秒是必须的：ROM 若持续拒绝挂窗，无节流会退化成「每切一首歌重启一次服务」
     * 的死循环，反而更费电。
     */
    private fun reviveOverlayIfNeeded() {
        if (!prefs.lyricOverlay) return
        if (LyricOverlayService.serviceAlive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayReviveAt < OVERLAY_REVIVE_INTERVAL_MS) return
        lastOverlayReviveAt = now
        LyricOverlayBridge.sync(app, true)
    }

    companion object {
        private const val TAG = "PlaybackEngine"

        /** 开启桌面悬浮歌词后的前台预览时长：够用户看清「确实开了」，又不至于碍事 */
        private const val OVERLAY_PREVIEW_MS = 6_000L

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

/**
 * 播放域对外的**动作面**。
 *
 * 抽成接口的唯一目的：让 `PlayerViewModel` 用 Kotlin 的委托（`by`）把它整块转出去，
 * 而不是手写四十个转发方法——转发代码既没有信息量，又会在加新动作时被漏掉。
 */
interface PlayerActions {
    fun restoreQueueIfNeeded()
    fun playQueue(queue: List<Song>, startIndex: Int)

    /** 随机播放一整批歌（先切成「随机 + 列表循环」再起播），见 [PlaybackEngine.playShuffled] */
    fun playShuffled(songs: List<Song>)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun cyclePlayMode()
    fun playNext(song: Song)
    fun enqueue(songs: List<Song>)
    fun refreshQueue()
    fun jumpToQueue(index: Int)
    fun removeQueueItem(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun setSpeed(speed: Float)

    /** 变调：半音（-12..+12，0 = 原调），见 `playback/Pitch` */
    fun setPitchSemitones(semitones: Int)
    fun sleepAfter(minutes: Int)
    fun sleepEndOfSong()

    /** 播完当前专辑（当前曲剩余 + 队列里后续同专辑曲目） */
    fun sleepEndOfAlbum()
    fun cancelSleep()
    fun setSkipSilence(enabled: Boolean)
    fun openPlayer()
    fun closePlayer()
    fun setLyricOffset(deltaMs: Int)
    fun resetLyricOffset()
    fun setLyricOffsetAbsolute(offsetMs: Int)
    fun setLyricScale(scale: Float)
    fun setLyricsPaneVisible(visible: Boolean)
    fun setCarMode(enabled: Boolean)
    fun setLyricOverlay(enabled: Boolean)
    fun setLyricOverlayLines(lines: Int)
    fun setLyricOverlayTextScale(scale: Int)
    fun setLyricOverlayBackdrop(mode: Int)
    fun setLyricOverlayPauseMode(mode: Int)
    fun setLyricOverlayAlign(align: Int)

    /** 悬浮窗宽度：屏幕宽度百分比（1..100） */
    fun setLyricOverlayWidthPercent(percent: Int)

    /** 悬浮窗歌词文字颜色（ARGB） */
    fun setLyricOverlayTextColor(color: Int)

    fun canDrawOverlay(): Boolean
    fun setOverlayEditing(editing: Boolean)
    fun setAppForeground(foreground: Boolean)
    fun restoreLyricOverlay()
    fun previewOverlay()
    fun setDynamicTheme(enabled: Boolean)
    fun dynamicThemeEnabled(): Boolean
    fun inspectAudio(song: Song)
    fun clearAudioInfo()
}
