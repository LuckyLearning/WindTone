package com.anylyric.session

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.anylyric.R
import com.anylyric.data.Prefs
import com.anylyric.diag.Probe
import com.anylyric.diag.ProbeSnapshot
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsSourceChain
import com.anylyric.lyrics.TitleTimeline
import com.anylyric.lyrics.TitleVerdict
import com.anylyric.lyrics.TrackContext
import com.anylyric.lyrics.sources.L0MetadataSource
import com.anylyric.lyrics.sources.L1TitleStreamSource
import com.anylyric.lyrics.sources.L2NotificationSource
import com.anylyric.overlay.OverlayHost
import com.anylyric.service.MediaWatchService

/**
 * 全应用唯一的会话协调器。
 *
 * 职责边界：只读旁听。不申请音频焦点、不调 TransportControls、不发媒体按键。
 * 所有回调都收敛到主线程，refresh 是全量重算（会话 → 状态 → 责任链 → 渲染），
 * 单一入口便于探测报告与判因使用同一份事实。
 */
class SessionHub private constructor(context: Context) {

    data class Status(
        val listenerConnected: Boolean,
        val overlayGranted: Boolean,
        val sessionCount: Int,
        val playingCount: Int,
        val title: String?,
        val artist: String?,
        val hitSource: String?,
        val verdict: TitleVerdict
    )

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clock = SessionClock { SystemClock.elapsedRealtime() }
    private val timeline = TitleTimeline()
    private val titleStream = L1TitleStreamSource()
    private val chain = LyricsSourceChain(
        listOf(L0MetadataSource(), titleStream, L2NotificationSource())
    )
    private val overlay = OverlayHost(appContext, prefs)

    /** packageName -> 通知文本字段。只在内存，不落盘。 */
    private val notificationTexts = HashMap<String, Map<String, String>>()

    private var controller: MediaController? = null
    private var listenerConnected = false

    private var currentLines: List<LyricLine> = emptyList()
    private var hitSource: String? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastPosition = 0L
    private var positionJumps = 0
    private var titleOnlyOnPause = false
    private var lastVerdict = TitleVerdict.UNKNOWN
    private var lastSessionCount = 0
    private var lastPlayingCount = 0
    private var lastMetadataKeys: List<String> = emptyList()

    /** 上次完整 `getActiveSessions()` 的时刻，用于节流 binder 查询。 */
    private var lastFullQueryAt = 0L

    /** 上一次通知字段的指纹，内容没变就不重算整条链。 */
    private var lastNotificationSignature: String? = null

    /** TITLE 最近一次变化的时刻，用于区分「还在等第一句」与「这播放器没有歌词通道」。 */
    private var lastTitleChangeAt = SystemClock.elapsedRealtime()

    /** 上一次真正写进悬浮层的文本，避免同一句反复重绘与重复记日志。 */
    private var lastRenderedSignature: String? = null

    init {
        Probe.setEnabled(prefs.debugEnabled)
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh("metadata")
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh("state")
        override fun onSessionDestroyed() {
            detachController()
            refresh("session_destroyed")
        }
    }

    fun prefs(): Prefs = prefs

    fun overlay(): OverlayHost = overlay

    fun currentStatus(): Status = Status(
        listenerConnected = listenerConnected,
        overlayGranted = Settings.canDrawOverlays(appContext),
        sessionCount = lastSessionCount,
        playingCount = lastPlayingCount,
        title = lastTitle,
        artist = lastArtist,
        hitSource = hitSource,
        verdict = lastVerdict
    )

    fun onListenerConnected() {
        listenerConnected = true
        if (Probe.enabled) Probe.write("权限", "listenerConnected=true")
        refresh("listener_connected")
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        if (Probe.enabled) Probe.write("权限", "listenerConnected=false")
        detachController()
        currentLines = emptyList()
        hitSource = null
        overlay.hide()
    }

    fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == appContext.packageName) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val fields = HashMap<String, String>(5)
        capture(extras, Notification.EXTRA_TITLE, fields)
        capture(extras, Notification.EXTRA_TEXT, fields)
        capture(extras, Notification.EXTRA_SUB_TEXT, fields)
        // 展开态正文：部分播放器的桌面歌词会写进这里，可能多行
        capture(extras, Notification.EXTRA_BIG_TEXT, fields)
        // tickerText 不是 extras 常量，是 Notification 上的字段
        notification.tickerText?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { fields["android.tickerText"] = it }

        if (fields.isEmpty()) notificationTexts.remove(sbn.packageName)
        else notificationTexts[sbn.packageName] = fields

        if (Probe.enabled) {
            Probe.write("通知", "pkg=${sbn.packageName} fields=${fields.keys.joinToString(",")}")
        }

        // 同一份文本重复推送（播放器每 250ms 刷一次通知）不必重跑责任链
        val signature = buildString(48) {
            append(sbn.packageName)
            for ((key, value) in fields) {
                append('\u0000').append(key).append('=').append(value)
            }
        }
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature

        val current = controller
        refresh(
            if (current == null || current.packageName == sbn.packageName) "notification"
            else "notification_other"
        )
    }

    fun attach() {
        refresh("attach")
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        detachController()
        overlay.hide()
    }

    fun applyTextSize() {
        overlay.refreshTextSize(prefs.fontSp)
    }

    @Synchronized
    private fun refresh(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refresh(reason) }
            return
        }

        if (!listenerConnected) {
            overlay.hide()
            return
        }

        // getActiveSessions 是跨进程调用：播放器每 250ms 推一次状态时若每次都查，
        // 每句歌词要白跑 3 次 binder。这里只对「可能换应用」的路径做完整查询。
        val now = SystemClock.elapsedRealtime()
        val fullQuery = when {
            controller == null -> true
            reason == "state" || reason == "metadata" -> now - lastFullQueryAt >= QUERY_MIN_INTERVAL_MS
            reason == "notification_other" -> now - lastFullQueryAt >= QUERY_FOREIGN_INTERVAL_MS
            else -> true
        }
        val sessions = if (fullQuery) {
            lastFullQueryAt = now
            querySessions().also {
                lastSessionCount = it.size
                lastPlayingCount = it.count { c ->
                    c.playbackState?.state == PlaybackState.STATE_PLAYING
                }
            }
        } else {
            listOfNotNull(controller)
        }

        val next = pick(sessions)
        if (next?.sessionToken != controller?.sessionToken) {
            bind(next)
            if (Probe.enabled) {
                Probe.write("会话", "bind=${next?.packageName ?: "none"} reason=$reason")
            }
        }

        val active = controller
        if (active == null) {
            currentLines = emptyList()
            hitSource = null
            lastTitle = null
            lastArtist = null
            overlay.hide()
            publishSnapshot(null, null, 0L)
            return
        }

        val playbackState = active.playbackState
        val metadata = active.metadata
        val playState = mapState(playbackState?.state ?: PlaybackState.STATE_NONE)
        val rawPosition = playbackState?.position ?: -1L

        clock.update(rawPosition, playbackState?.playbackSpeed ?: 1f, playState)
        observePosition(rawPosition, playState)

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        // duration 与 mediaId 都可能为空，补上包名／专辑／音轨作为换歌指纹的兜底。
        // 包名必须在内：两个应用的元数据同样稀疏时，否则会被当成同一首歌。
        val identityExtra = buildString(40) {
            append(active.packageName)
            append('|')
            append(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            append('|')
            append(metadata?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) ?: 0L)
        }

        if (title != lastTitle) lastTitleChangeAt = now
        lastTitle = title
        lastArtist = artist

        val previousVerdict = lastVerdict
        lastVerdict = timeline.record(now, title, duration, mediaId, artist, identityExtra)
        if (timeline.consumeIdentityChanged()) {
            titleStream.reset()
            titleOnlyOnPause = false
            lastTitleChangeAt = now
            // 换歌必然伴随 position 归零／重置，不能计成进度不稳
            lastPosition = 0L
            positionJumps = 0
        }

        if (!title.isNullOrBlank()) {
            // 关键：判定未成立时也要入缓冲。若等判定成立才入，判定前看到的几句
            // 会被永久丢掉，第一句还得再等一个歌词周期才出现。
            titleStream.onTitleLine(title)
            if (lastVerdict == TitleVerdict.PSEUDO && playState == PlayState.PAUSED) {
                titleOnlyOnPause = true
            }
        }

        if (Probe.enabled && lastVerdict != previousVerdict) {
            Probe.write(
                "歌词",
                "verdict=${previousVerdict.name}->${lastVerdict.name} " +
                    "changes=${timeline.snapshot().size} buf=${titleStream.bufferedCount()}"
            )
        }

        val metadataValues = textValues(metadata)
        lastMetadataKeys = metadataValues.keys.toList()

        val trackContext = TrackContext(
            packageName = active.packageName,
            title = title,
            artist = artist,
            durationMs = duration,
            mediaId = mediaId,
            metadata = metadataValues,
            notificationTexts = notificationTexts[active.packageName].orEmpty(),
            pseudoTitle = lastVerdict == TitleVerdict.PSEUDO,
            titleLine = title
        )

        val outcome = chain.resolve(trackContext)
        hitSource = outcome.hit?.sourceId
        currentLines = outcome.hit?.lines.orEmpty()

        Probe.attempts = outcome.attempts
        Probe.metadataPreview = metadataValues.mapValues { preview(it.value) }
        Probe.notificationFields = notificationTexts[active.packageName].orEmpty()
        Probe.titleEvents = timeline.snapshot()
        Probe.hitSource = hitSource

        publishSnapshot(active, title, duration)

        if (Probe.enabled) {
            Probe.write(
                "歌词",
                "reason=$reason verdict=$lastVerdict hit=${hitSource ?: "none"} " +
                    "lines=${currentLines.size} buf=${titleStream.bufferedCount()} " +
                    "attempts=" + outcome.attempts.joinToString(",") { attempt ->
                        when (attempt) {
                            is LyricResult.Hit -> "${attempt.sourceId}:hit"
                            is LyricResult.Miss -> "${attempt.sourceId}:${attempt.reason}"
                        }
                    }
            )
        }

        render()
    }

    private fun render() {
        if (currentLines.isEmpty()) {
            if (prefs.hideWhenNoLyrics) {
                overlay.hide()
                return
            }
            // 标题还在动、只是还没攒够两句 —— 那是「获取中」，不是「没有」。
            // 把两者分开，用户才不会以为这个播放器不被支持。
            val waiting = lastVerdict != TitleVerdict.STABLE &&
                SystemClock.elapsedRealtime() - lastTitleChangeAt <= TITLE_WAIT_TIMEOUT_MS
            push(
                appContext.getString(
                    if (waiting) R.string.overlay_fetching else R.string.overlay_no_lyrics
                ),
                if (waiting) lastTitle else null
            )
            return
        }

        val timed = currentLines.all { it.timeMs != null }
        val index = if (timed) clock.locate(currentLines, clock.position()) else currentLines.lastIndex
        val safe = index.coerceIn(0, currentLines.lastIndex)
        push(
            currentLines[safe].text,
            currentLines.getOrNull(safe + 1)?.text
        )
    }

    /**
     * 真正把文本推给悬浮层，并记录推了什么。
     *
     * `[渲染]` 这一行是为了回答「歌词已经取到了，为什么屏幕上没有」：它把
     * 交给 WindowManager 的原文与实际挂载状态一起写下来，取到行却没显示时
     * 一眼就能区分是「没渲染」还是「渲染了但窗口没挂上」。
     */
    private fun push(primary: String, secondary: String?) {
        overlay.show(primary, secondary)
        if (!Probe.enabled) return
        val signature = "$primary\u0000${secondary.orEmpty()}"
        if (signature == lastRenderedSignature) return
        lastRenderedSignature = signature
        val trace = "text=${primary.take(24)} next=${secondary?.take(12) ?: "none"} " +
            "attached=${overlay.isAttached()} measured=${overlay.isMeasured()}"
        Probe.lastRender = trace
        Probe.write("渲染", trace)
    }

    private fun publishSnapshot(active: MediaController?, title: String?, duration: Long) {
        Probe.snapshot = ProbeSnapshot(
            listenerConnected = listenerConnected,
            overlayGranted = Settings.canDrawOverlays(appContext),
            sessionCount = lastSessionCount,
            playingCount = lastPlayingCount,
            chosenPackage = active?.packageName,
            title = title,
            artist = lastArtist,
            durationMs = duration,
            metadataKeys = lastMetadataKeys,
            pseudoTitle = lastVerdict == TitleVerdict.PSEUDO,
            hitSource = hitSource,
            l1OnlyOnPause = titleOnlyOnPause,
            positionJumpCount = positionJumps,
            timedLyrics = currentLines.isNotEmpty() && currentLines.all { it.timeMs != null },
            viewAttached = overlay.isAttached(),
            viewMeasured = overlay.isMeasured(),
            hiddenByRule = false,
            displayCount = displayCount(),
            fontScale = appContext.resources.configuration.fontScale
        )
    }

    private fun displayCount(): Int = try {
        appContext.getSystemService(DisplayManager::class.java)?.displays?.size ?: 1
    } catch (_: Throwable) {
        1
    }

    /**
     * 只取文本类字段。故意不解码 Bitmap：封面位图跨进程传输是车机 OOM 的常见来源，
     * 而本应用根本不需要封面。键名仍然全量保留，供探测报告展示。
     */
    private fun textValues(metadata: MediaMetadata?): Map<String, Any?> {
        if (metadata == null) return emptyMap()
        val values = HashMap<String, Any?>(metadata.keySet().size)
        for (key in metadata.keySet()) {
            values[key] = metadata.getText(key)?.toString() ?: metadata.getString(key)
        }
        return values
    }

    private fun preview(value: Any?): String {
        val text = value?.toString() ?: return "null"
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= 60) flat else flat.take(60) + "…"
    }

    private fun querySessions(): List<MediaController> {
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
            ?: return emptyList()
        val component = ComponentName(appContext, MediaWatchService::class.java)
        return try {
            manager.getActiveSessions(component) ?: emptyList()
        } catch (t: Throwable) {
            if (Probe.enabled) {
                Probe.write("会话", "getActiveSessions failed: ${t.javaClass.simpleName}")
            }
            emptyList()
        }
    }

    private fun pick(sessions: List<MediaController>): MediaController? {
        if (sessions.isEmpty()) return null
        val playing = sessions.filter {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val pool = playing.ifEmpty { sessions }
        return pool.maxByOrNull {
            it.playbackState?.lastPositionUpdateTime ?: 0L
        }
    }

    private fun bind(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, mainHandler)
        if (next == null) return

        // 换会话＝换播放器，判定与行缓冲一律从零开始：
        // 否则两个元数据同样稀疏的应用会共用指纹，把上一个应用的歌词带过来。
        timeline.reset()
        titleStream.reset()
        lastVerdict = TitleVerdict.UNKNOWN
        lastTitle = null
        currentLines = emptyList()
        hitSource = null
        // 进度跳变也必须按会话重置：换应用时 position 从 89s 掉到 44s
        // 是正常的，不能计成 CLOCK_UNSTABLE。
        lastPosition = 0L
        positionJumps = 0
        titleOnlyOnPause = false
        lastTitleChangeAt = SystemClock.elapsedRealtime()
    }

    private fun detachController() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    /**
     * 进度回跳统计，只用于判因 CLOCK_UNSTABLE。
     *
     * 三类回跳都**不是**「播放器不稳」，必须排除，否则换歌、重播、拖进度会把报告
     * 刷成 CLOCK_UNSTABLE（真机第一版就出现了「有时提示不稳定」）：
     *  · 换歌 / 重播 —— position 落到 0 附近，且换歌时已在 identity 变化处清零；
     *  · 用户拖动进度 —— 回退幅度通常远超 5 秒；
     *  · 暂停 / 缓冲 —— 位置本来就不推进。
     * 剩下的小幅回跳才是发布端进度不自洽。
     */
    private fun observePosition(position: Long, playState: PlayState) {
        if (position < 0) return
        val previous = lastPosition
        lastPosition = position
        if (previous <= 0 || playState != PlayState.PLAYING) return
        val backwards = previous - position
        if (backwards <= 0 || backwards > POSITION_JITTER_MAX_MS) return
        if (position < RESTART_NEAR_ZERO_MS) return
        positionJumps++
        if (Probe.enabled) Probe.write("时钟", "backward ${previous}->$position")
    }

    private fun capture(
        extras: android.os.Bundle,
        key: String,
        out: MutableMap<String, String>
    ) {
        val text = extras.getCharSequence(key)?.toString()?.trim()
        if (!text.isNullOrEmpty()) out[key] = text
    }

    private fun mapState(state: Int): PlayState = when (state) {
        PlaybackState.STATE_PLAYING -> PlayState.PLAYING
        PlaybackState.STATE_PAUSED -> PlayState.PAUSED
        PlaybackState.STATE_STOPPED -> PlayState.STOPPED
        PlaybackState.STATE_BUFFERING -> PlayState.BUFFERING
        else -> PlayState.NONE
    }

    companion object {
        /** 状态／元数据回调里，两次完整会话查询的最小间隔。 */
        private const val QUERY_MIN_INTERVAL_MS = 1_500L

        /** 与本应用无关的通知（聊天、健康…）触发的查询间隔，防止刷屏设备空转。 */
        private const val QUERY_FOREIGN_INTERVAL_MS = 5_000L

        /** 标题停下多久还没歌词，就承认这个播放器没有歌词通道。 */
        private const val TITLE_WAIT_TIMEOUT_MS = 20_000L

        /** 回跳幅度超过这个值视为用户拖动进度，不计入「进度不稳」。 */
        private const val POSITION_JITTER_MAX_MS = 5_000L

        /** 位置落到这个值以下视为换歌／重播，不是回跳。 */
        private const val RESTART_NEAR_ZERO_MS = 2_000L

        @Volatile
        private var instance: SessionHub? = null

        fun get(context: Context): SessionHub =
            instance ?: synchronized(this) {
                instance ?: SessionHub(context.applicationContext).also { instance = it }
            }
    }
}
