package com.luckylearning.windtone.playback

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.LyricsLoader
import com.luckylearning.windtone.data.MessageBus
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SongIndex
import com.luckylearning.windtone.data.ThemeColorExtractor
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.db.QueueSongEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
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
            lyricWordByWord = prefs.lyricWordByWord,
            carMode = prefs.carMode,
            lyricOverlay = prefs.lyricOverlay,
            // 车载模式：启动直接进播放页（跳过库主页）
            playerExpanded = prefs.carMode,
            speed = prefs.playbackSpeed,
            notifyLyrics = prefs.notifyLyrics,
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

    // 通知栏歌词：记录已推送的行号，避免重复刷新媒体项
    private var lastPushedLyricIndex = Int.MIN_VALUE

    // 播放计数去重
    private var lastCountedMediaId: String? = null
    private var lastCountedAt = 0L

    init {
        AudioFx.init(app)
        scope.launch {
            SleepTimer.remainingMs.collect { ms ->
                _player.update { it.copy(sleepRemainingMs = ms) }
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
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching {
                    val dao = db.songStateDao()
                    dao.ensure(songId, now)
                    dao.addPlayedMs(songId, ms, now)
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
        scope.launch {
            val c = ensureController() ?: return@launch
            if (queue.isEmpty()) return@launch
            val i = startIndex.coerceIn(0, queue.size - 1)
            currentQueue = queue
            c.setMediaItems(queue.map { PlaybackConnection.toMediaItem(it) }, i, 0L)
            c.prepare()
            c.play()
            if (prefs.playbackSpeed != 1f) c.setPlaybackSpeed(prefs.playbackSpeed)
            _progress.value = PlaybackProgress(0L, queue[i].durationMs, 0L)
            _player.update { it.copy(queueSize = queue.size) }
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

    override fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo((c.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    /** 相对当前进度微调（歌词面板的快退/快进按钮用） */
    override fun seekRelative(deltaMs: Long) {
        seekTo((_progress.value.positionMs + deltaMs).coerceAtLeast(0L))
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
        scope.launch { ensureController()?.setPlaybackSpeed(s) }
    }

    override fun sleepAfter(minutes: Int) = SleepTimer.start(minutes * 60_000L)

    override fun sleepEndOfSong() {
        // 直接问控制器要真实的进度与总长，避免依赖已从 PlayerUiState 移除的字段
        val c = controller
        val position = c?.currentPosition ?: _progress.value.positionMs
        val duration = c?.duration?.takeIf { it > 0 } ?: _progress.value.durationMs
        SleepTimer.startEndOfSong(position, duration)
    }

    override fun cancelSleep() = SleepTimer.cancel()

    override fun setLoudness(gainMb: Int) = AudioFx.setLoudnessGain(gainMb)

    override fun setSkipSilence(enabled: Boolean) {
        prefs.skipSilence = enabled
        _player.update { it.copy(skipSilence = enabled) }
        // 跳过静音是 ExoPlayer 专有开关，Player 接口上没有，走进程内句柄
        PlayerHandle.setSkipSilence(enabled)
    }

    /** 通知栏 / 锁屏滚动显示当前歌词行（默认关闭） */
    override fun setNotifyLyrics(enabled: Boolean) {
        prefs.notifyLyrics = enabled
        _player.update { it.copy(notifyLyrics = enabled) }
        lastPushedLyricIndex = Int.MIN_VALUE
        if (!enabled) {
            // 关掉时把当前媒体项上残留的歌词行擦掉，否则通知会一直显示最后一句
            restoreNotificationMetadata()
        }
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

    /** 逐字歌词：当前行有逐字时间轴时按字染色（默认开启） */
    override fun setLyricWordByWord(enabled: Boolean) {
        prefs.lyricWordByWord = enabled
        _player.update { it.copy(lyricWordByWord = enabled) }
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
        lastPushedLyricIndex = Int.MIN_VALUE
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
                if (playing) maybePushLyricToNotification(pos)
                // 悬浮歌词：与进度同频驱动，内部按「行文字是否变化」去重，
                // 未开启开关时是纯字段比较，开销可忽略。
                publishLyricToOverlay(pos, playing)

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
            val i = lyrics.indexAt(positionMs + state.lyricOffsetMs)
            lyrics.lines.getOrNull(i)?.text.orEmpty()
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
        val i = lyrics.indexAt(positionMs + state.lyricOffsetMs)
        if (i < 0 || i == lastPushedLyricIndex) return
        val itemIndex = c.currentMediaItemIndex
        if (itemIndex < 0 || itemIndex >= c.mediaItemCount) return
        val line = lyrics.lines.getOrNull(i) ?: return
        lastPushedLyricIndex = i
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

    /**
     * 擦掉当前媒体项上的歌词行（关闭「通知栏显示歌词」时调用）。
     *
     * 歌词只写在 `MediaMetadata.subtitle` 上，`title`/`artist` 原样保留 ——
     * 队列抽屉和桌面小组件都读 `artist`，不能被歌词污染。
     */
    private fun restoreNotificationMetadata() {
        val c = controller ?: return
        val itemIndex = c.currentMediaItemIndex
        if (itemIndex < 0 || itemIndex >= c.mediaItemCount) return
        val item = c.getMediaItemAt(itemIndex)
        val md = item.mediaMetadata
        if (md.subtitle.isNullOrBlank()) return
        runCatching {
            c.replaceMediaItem(
                itemIndex,
                item.buildUpon()
                    .setMediaMetadata(md.buildUpon().setSubtitle(null).build())
                    .build(),
            )
        }
    }

    companion object {
        private const val TAG = "PlaybackEngine"

        /** 开启桌面悬浮歌词后的前台预览时长：够用户看清「确实开了」，又不至于碍事 */
        private const val OVERLAY_PREVIEW_MS = 6_000L
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
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun seekBy(deltaMs: Long)
    fun seekRelative(deltaMs: Long)
    fun cyclePlayMode()
    fun playNext(song: Song)
    fun refreshQueue()
    fun jumpToQueue(index: Int)
    fun removeQueueItem(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun setSpeed(speed: Float)
    fun sleepAfter(minutes: Int)
    fun sleepEndOfSong()
    fun cancelSleep()
    fun setLoudness(gainMb: Int)
    fun setSkipSilence(enabled: Boolean)
    fun setNotifyLyrics(enabled: Boolean)
    fun openPlayer()
    fun closePlayer()
    fun setLyricOffset(deltaMs: Int)
    fun resetLyricOffset()
    fun setLyricScale(scale: Float)
    fun setLyricWordByWord(enabled: Boolean)
    fun setCarMode(enabled: Boolean)
    fun setLyricOverlay(enabled: Boolean)
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
