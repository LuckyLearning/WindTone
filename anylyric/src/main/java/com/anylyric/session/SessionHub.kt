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
        refresh("notification")
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

        val sessions = querySessions()
        lastSessionCount = sessions.size
        lastPlayingCount = sessions.count {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
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
        detectJump(rawPosition)

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        lastTitle = title
        lastArtist = artist

        lastVerdict = timeline.record(
            SystemClock.elapsedRealtime(), title, duration, mediaId, artist
        )
        if (timeline.consumeIdentityChanged()) {
            titleStream.reset()
            titleOnlyOnPause = false
        }

        if (lastVerdict == TitleVerdict.PSEUDO && !title.isNullOrBlank()) {
            titleStream.onTitleLine(title)
            if (playState == PlayState.PAUSED) titleOnlyOnPause = true
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
                    "lines=${currentLines.size} " +
                    "attempts=" + outcome.attempts.joinToString(",") { it.sourceId }
            )
        }

        render()
    }

    private fun render() {
        if (currentLines.isEmpty()) {
            if (prefs.hideWhenNoLyrics) {
                overlay.hide()
            } else {
                overlay.show(appContext.getString(R.string.overlay_no_lyrics), null)
            }
            return
        }

        val timed = currentLines.all { it.timeMs != null }
        val index = if (timed) clock.locate(currentLines, clock.position()) else currentLines.lastIndex
        val safe = index.coerceIn(0, currentLines.lastIndex)
        overlay.show(
            currentLines[safe].text,
            currentLines.getOrNull(safe + 1)?.text
        )
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
    }

    private fun detachController() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** 位置明显倒退（>1.5s）计一次跳变，用于判因 CLOCK_UNSTABLE。 */
    private fun detectJump(position: Long) {
        if (position < 0) return
        if (lastPosition > 0 && position + 1_500 < lastPosition) {
            positionJumps++
            if (Probe.enabled) Probe.write("时钟", "position jump ${lastPosition}->$position")
        }
        lastPosition = position
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
        @Volatile
        private var instance: SessionHub? = null

        fun get(context: Context): SessionHub =
            instance ?: synchronized(this) {
                instance ?: SessionHub(context.applicationContext).also { instance = it }
            }
    }
}
