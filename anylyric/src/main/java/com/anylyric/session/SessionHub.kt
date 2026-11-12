package com.anylyric.session

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import com.anylyric.data.ListenerState
import com.anylyric.data.Permissions
import com.anylyric.data.Prefs
import com.anylyric.diag.Perf
import com.anylyric.diag.Probe
import com.anylyric.diag.ProbeSnapshot
import com.anylyric.diag.ResourceSampler
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricTick
import com.anylyric.lyrics.LyricsSourceChain
import com.anylyric.lyrics.TitleTimeline
import com.anylyric.lyrics.TitleVerdict
import com.anylyric.lyrics.TrackContext
import com.anylyric.lyrics.sources.L0MetadataSource
import com.anylyric.lyrics.sources.L1TitleStreamSource
import com.anylyric.lyrics.sources.L2NotificationSource
import com.anylyric.overlay.OverlayHost
import com.anylyric.overlay.Secondary
import com.anylyric.service.MediaWatchService
import com.anylyric.ui.SettingsActivity

/**
 * 全应用唯一的会话协调器。
 *
 * 职责边界：只读旁听。不申请音频焦点、不调 TransportControls、不发媒体按键。
 * 所有回调都收敛到主线程，refresh 是全量重算（会话 → 状态 → 责任链 → 渲染），
 * 单一入口便于探测报告与判因使用同一份事实。
 */
class SessionHub private constructor(context: Context) {

    data class Status(
        val listenerState: ListenerState,
        val overlayGranted: Boolean,
        val sessionCount: Int,
        val playingCount: Int,
        val title: String?,
        val artist: String?,
        val hitSource: String?,
        val verdict: TitleVerdict
    ) {
        /** 服务真的连上了才算可用；「已授权未绑定」不能当作可用。 */
        val listenerConnected: Boolean get() = listenerState == ListenerState.GRANTED
    }

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

    /** 上次向系统请求重绑的时刻，用于节流（系统自身对 requestRebind 有频率限制）。 */
    private var lastRebindAt = 0L

    private var currentLines: List<LyricLine> = emptyList()
    private var hitSource: String? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastPosition = 0L
    private var positionJumps = 0
    private var titleOnlyOnPause = false
    private var lastVerdict = TitleVerdict.UNKNOWN
    private var lastPlayState = PlayState.NONE
    private var lastSessionCount = 0
    private var lastPlayingCount = 0
    private var lastMetadataKeys: List<String> = emptyList()

    /** 上次完整 `getActiveSessions()` 的时刻，用于节流 binder 查询。 */
    private var lastFullQueryAt = 0L

    /** 上一次通知字段的指纹，内容没变就不重算整条链。 */
    private var lastNotificationSignature: String? = null

    /**
     * 上一次责任链的输入指纹。
     *
     * 责任链的产物只取决于「哪个会话 + 元数据对象 + 标题/歌手/时长/媒体号 + 判定 + 通知文本」。
     * 这些东西都没变时，重跑一遍解析不可能得到不同结果 —— 而真机上元数据与状态回调
     * 一秒能来好几个，全量重跑就是拿轮询冒充事件驱动。
     */
    private var lastInputKey: String? = null

    /** 刷新合并：同一帧内的多个回调只跑一次全量刷新。 */
    private var refreshScheduled = false
    private var pendingReason: String? = null

    /** 上一次为「外来通知」重挑会话的时刻，与播放状态相关（见 [foreignQueryInterval]）。 */
    private var lastForeignPickAt = 0L

    /** 定时歌词的自驱动 tick 是否已排上。 */
    private var tickScheduled = false

    /** TITLE 最近一次变化的时刻，用于区分「还在等第一句」与「这播放器没有歌词通道」。 */
    private var lastTitleChangeAt = SystemClock.elapsedRealtime()

    /** 上一次真正写进悬浮层的文本，避免同一句反复重绘与重复记日志。 */
    private var lastRenderedSignature: String? = null

    init {
        Probe.setEnabled(prefs.debugEnabled)
        if (prefs.debugEnabled) startResourceTicker()
        // 菜单关闭、隐藏到期、换了输出屏幕：都需要上层重新算一遍内容再决定是否重建窗口
        overlay.onRequestRender = { requestRefresh(REASON_UI) }
        overlay.onOpenSettings = {
            try {
                appContext.startActivity(
                    Intent(appContext, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                // 车机上少数 ROM 会拦 NEW_TASK 启动：忽略即可，用户仍可从桌面进入
            }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = requestRefresh(REASON_METADATA)
        override fun onPlaybackStateChanged(state: PlaybackState?) = requestRefresh(REASON_STATE)
        override fun onSessionDestroyed() {
            detachController()
            requestRefresh("session_destroyed")
        }
    }

    fun prefs(): Prefs = prefs

    fun overlay(): OverlayHost = overlay

    /**
     * 调试开关的唯一切换点。
     *
     * 打开时立刻排一次资源采样，之后每分钟一条 —— 这组数字回答的是
     * 「歌词卡顿 / 车机变热，是不是 AnyLyric 在涨内存或空转 CPU」。
     */
    fun setDebugEnabled(value: Boolean) {
        prefs.debugEnabled = value
        Probe.setEnabled(value)
        mainHandler.removeCallbacks(resourceTicker)
        // 计数器跟着一起归零：否则「开启后的第一分钟增量」里混着开启前的事件
        if (value) Perf.reset()
        if (value) mainHandler.postDelayed(resourceTicker, RESOURCE_FIRST_DELAY_MS)
    }

    private val resourceTicker = object : Runnable {
        override fun run() {
            if (!Probe.enabled) return
            // 资源行后面挂工作量增量：单看 cpu=% 分不清是「事件太多」还是「某段代码太贵」，
            // 两个数字并排才好判 —— 一起涨是事件量，只有 CPU 涨是单点开销。
            Probe.write("资源", ResourceSampler.sample().oneLine() + " | " + Perf.sinceLast())
            mainHandler.postDelayed(this, RESOURCE_INTERVAL_MS)
        }
    }

    private fun startResourceTicker() {
        mainHandler.removeCallbacks(resourceTicker)
        mainHandler.postDelayed(resourceTicker, RESOURCE_FIRST_DELAY_MS)
    }

    fun currentStatus(): Status = Status(
        listenerState = listenerState(),
        overlayGranted = Settings.canDrawOverlays(appContext),
        sessionCount = lastSessionCount,
        playingCount = lastPlayingCount,
        title = lastTitle,
        artist = lastArtist,
        hitSource = hitSource,
        verdict = lastVerdict
    )

    /**
     * 「权限是否授予」与「服务是否已连接」分开判。
     *
     * 只读系统保存的授权列表，不产生副作用，界面可以随时调。
     */
    fun listenerState(): ListenerState {
        if (listenerConnected) return ListenerState.GRANTED
        return when (Permissions.isListenerGranted(appContext)) {
            true -> ListenerState.GRANTED_NOT_BOUND
            else -> ListenerState.NOT_GRANTED
        }
    }

    /**
     * 已授权却没连上时，主动请求系统重新绑定，免去「去设置里关掉再打开一次」。
     *
     * 返回 true 表示当前要么已连接、要么请求已发出（等待系统回应）；返回 false
     * 表示确实没有授权，此时唯一出路是让用户去授权。
     */
    fun tryRebind(force: Boolean = false): Boolean {
        if (listenerConnected) return true
        if (Permissions.isListenerGranted(appContext) != true) return false
        val now = SystemClock.elapsedRealtime()
        // 首次必须真的发出去：lastRebindAt 为 0 时不能因为「开机才几秒」被节流掉
        if (!force && lastRebindAt != 0L && now - lastRebindAt < REBIND_MIN_INTERVAL_MS) {
            return true
        }
        lastRebindAt = now
        Permissions.requestRebind(appContext)
        if (Probe.enabled) Probe.write("权限", "requestRebind force=$force")
        return true
    }

    /**
     * 只刷新「权限两项事实」，不动会话与歌词。
     *
     * 未连接时 [refresh] 会提前返回，快照里的权限字段就会停在旧值；诊断页因此
     * 每秒同步一次，保证报告与实际状态一致（否则会把「已授权未绑定」显示成别的）。
     */
    fun syncPermissionFacts() {
        val granted = Permissions.isListenerGranted(appContext) == true
        if (Probe.snapshot.listenerGranted != granted ||
            Probe.snapshot.listenerConnected != listenerConnected
        ) {
            Probe.snapshot = Probe.snapshot.copy(
                listenerGranted = granted,
                listenerConnected = listenerConnected
            )
        }
    }

    /** 已授权但服务未绑定时为 true：靠低频查询代偿缺失的推送事件。 */
    private var degraded = false

    /**
     * 降级模式下的低频兜底查询。
     *
     * 正常路径由 `onNotificationPosted` 与 `MediaController` 回调驱动；服务没绑上时
     * 这两个事件源都不存在，只能自己定期查一次活动会话，否则界面会永远停在空状态。
     * 只在降级期间运行，服务一连上就停。
     */
    private val degradedTicker = object : Runnable {
        override fun run() {
            if (!degraded) return
            requestRefresh("degraded_tick")
            if (degraded) mainHandler.postDelayed(this, DEGRADED_INTERVAL_MS)
        }
    }

    private fun startDegraded() {
        if (degraded) return
        degraded = true
        if (Probe.enabled) Probe.write("权限", "degraded=true（已授权未绑定，改走低频查询）")
        mainHandler.postDelayed(degradedTicker, DEGRADED_INTERVAL_MS)
        // 进降级时再要一次重绑，能连上就自动退出
        tryRebind()
    }

    private fun stopDegraded() {
        if (!degraded) return
        degraded = false
        mainHandler.removeCallbacks(degradedTicker)
        if (Probe.enabled) Probe.write("权限", "degraded=false（服务已连接）")
    }

    fun onListenerConnected() {
        listenerConnected = true
        stopDegraded()
        if (Probe.enabled) Probe.write("权限", "listenerConnected=true")
        requestRefresh("listener_connected")
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        if (Probe.enabled) Probe.write("权限", "listenerDisconnected")
        detachController()
        currentLines = emptyList()
        hitSource = null
        lastInputKey = null
        lastNotificationSignature = null
        stopLineTick()
        overlay.hide()
        syncPermissionFacts()
        // 权限还勾着却被系统解绑（内存紧张回收、系统重启服务）：再要一次绑定，
        // 免得悬浮窗就此消失、只能等用户重新打开应用。已授权才会真的发请求。
        tryRebind()
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
        if (current != null && current.packageName != sbn.packageName) {
            // 与本应用无关的通知。**这是本应用最容易被自己拖死的一处**：
            // 代理、应用商店这类应用每秒发一条通知，之前每条都会跑一遍全量刷新，
            // 实测稳定吃掉 17% CPU。这里只做「会话可能变了吗」这一次判断，且节流。
            val now = SystemClock.elapsedRealtime()
            if (now - lastForeignPickAt < foreignQueryInterval()) {
                Perf.onSkip()
                return
            }
            lastForeignPickAt = now
            requestRefresh(REASON_NOTIFICATION_OTHER)
            return
        }

        requestRefresh(REASON_NOTIFICATION)
    }

    /**
     * 外来通知触发的会话重挑间隔。
     *
     * 当前会话还在放时 5 秒一次就够 —— 换播放器必然先有暂停（那时立刻放开限制），
     * 或者新播放器自己播起来，两者都会在几秒内被下一次查询看到。当前会话已经不在
     * 播放时缩短到 1 秒：那才是「用户刚点了另一个应用」的真实场景。
     */
    private fun foreignQueryInterval(): Long =
        if (lastPlayState == PlayState.PLAYING) QUERY_FOREIGN_INTERVAL_MS
        else QUERY_FOREIGN_ACTIVE_MS

    fun attach() {
        requestRefresh("attach")
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        refreshScheduled = false
        pendingReason = null
        tickScheduled = false
        degraded = false
        detachController()
        overlay.hide()
    }

    /** 样式变更（字号／预设／透明度／行数…）立即生效，不重建窗口。 */
    fun applyStyle() {
        overlay.applyStyle()
        // 「无歌词时隐藏」这类策略属于渲染阶段判定，样式改完必须重算一次。
        // 没有会话时不重算：否则用户一改样式，屏上会凭空多出一个「暂无歌词」。
        if (controller != null) render()
    }

    /**
     * 事件合并入口。
     *
     * 播放器换一句歌词会连发 `state` + `metadata` 两三个回调（真机上一帧内能到 5 个），
     * 外部应用的通知又是每秒一条。每个回调都直接跑全量刷新，等于把责任链当轮询用。
     * 这里把同一帧内的多次请求并成一次，并保留信息量最大的那个原因便于判因。
     */
    private fun requestRefresh(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestRefresh(reason) }
            return
        }
        pendingReason = mergeReason(pendingReason, reason)
        if (refreshScheduled) return
        refreshScheduled = true
        mainHandler.post {
            refreshScheduled = false
            val next = pendingReason ?: reason
            pendingReason = null
            refreshNow(next)
        }
    }

    /**
     * 原因合并：换会话 / 换歌这类「根因」不能被一串 `state` 回调盖掉。
     *
     * 顺序按「谁更能解释这次变化」排：根因 > 本应用通知 > 元数据 > 状态 > 外来通知。
     */
    private fun mergeReason(current: String?, next: String): String {
        if (current == null) return next
        return if (rankReason(next) > rankReason(current)) next else current
    }

    private fun rankReason(reason: String): Int = when (reason) {
        REASON_STATE, REASON_METADATA -> 1
        REASON_NOTIFICATION_OTHER -> 0
        REASON_NOTIFICATION -> 2
        else -> 3
    }

    /** 全量重算：会话 → 状态 → 责任链 → 渲染。只在事件合并之后、主线程上跑。 */
    private fun refreshNow(reason: String) {
        Perf.onRefresh()

        if (!listenerConnected) {
            syncPermissionFacts()
            if (Permissions.isListenerGranted(appContext) != true) {
                // 真的没授权：任何查询都会被系统拒绝
                stopLineTick()
                overlay.hide()
                return
            }
            // 已授权但系统没把服务绑上，**不是「不能用」**：
            // getActiveSessions 的权限边界是授权列表，不要求服务实例已连接；
            // MediaController 的回调走 MediaSession 自己的 binder。因此 L0/L1 与进度
            // 照常工作，缺的只有 L2（通知文本）与推送事件 —— 进入降级模式继续干活。
            startDegraded()
        } else {
            stopDegraded()
        }

        // getActiveSessions 是跨进程调用，且会把每个会话的元数据整包搬过来。
        // 只有「可能换应用」的路径才值得查：无会话、通知类事件、播放状态发生跳变，
        // 其余情况直接沿用当前控制器。
        val now = SystemClock.elapsedRealtime()
        val cachedState = mapState(controller?.playbackState?.state ?: PlaybackState.STATE_NONE)
        val fullQuery = when {
            controller == null -> true
            reason == REASON_NOTIFICATION_OTHER -> now - lastFullQueryAt >= foreignQueryInterval()
            reason == REASON_STATE || reason == REASON_METADATA || reason == REASON_NOTIFICATION ->
                cachedState != lastPlayState || now - lastFullQueryAt >= QUERY_MIN_INTERVAL_MS
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

        val previousToken = controller?.sessionToken
        val next = pick(sessions)
        val sessionChanged = next?.sessionToken != previousToken
        if (sessionChanged) {
            bind(next)
            if (Probe.enabled) {
                Probe.write("会话", "bind=${next?.packageName ?: "none"} reason=$reason")
            }
        }

        // 外来通知短路：会话没换，这次通知对歌词没有任何影响。
        // 走进下面的流程意味着重新读元数据、重解析歌词、重绘、重算诊断快照 ——
        // 而结果必然一模一样，白白烧掉一次 CPU。
        if (!sessionChanged && reason == REASON_NOTIFICATION_OTHER) {
            Perf.onSkip()
            return
        }

        val active = controller
        if (active == null) {
            currentLines = emptyList()
            hitSource = null
            lastTitle = null
            lastArtist = null
            lastInputKey = null
            stopLineTick()
            overlay.hide()
            publishSnapshot(null, null, 0L)
            return
        }

        val playbackState = active.playbackState
        val metadata = active.metadata
        val playState = mapState(playbackState?.state ?: PlaybackState.STATE_NONE)
        lastPlayState = playState
        val rawPosition = playbackState?.position ?: -1L

        clock.update(rawPosition, playbackState?.playbackSpeed ?: 1f, playState)
        observePosition(rawPosition, playState)

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        if (title != lastTitle) lastTitleChangeAt = now
        lastTitle = title
        lastArtist = artist

        val previousVerdict = lastVerdict
        lastVerdict = timeline.record(now, title, duration, mediaId, artist, identityExtra(active, metadata))
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

        // 责任链只在这些输入真的变了时才重跑。元数据用对象身份判等：播放器换了内容
        // 必然是一个新对象（跨 binder 反序列化出来的），同一对象则内容必然相同。
        val activeTexts = notificationTexts[active.packageName].orEmpty()
        val inputKey = buildString(112) {
            append(active.packageName).append('\u0001')
            append(System.identityHashCode(metadata)).append('\u0001')
            append(title).append('\u0001')
            append(artist).append('\u0001')
            append(duration).append('\u0001')
            append(mediaId).append('\u0001')
            append(lastVerdict.name).append('\u0001')
            append(activeTexts.hashCode())
        }
        val inputsChanged = inputKey != lastInputKey

        if (inputsChanged) {
            lastInputKey = inputKey
            Perf.onChain()

            val metadataValues = textValues(metadata)
            lastMetadataKeys = metadataValues.keys.toList()

            val trackContext = TrackContext(
                packageName = active.packageName,
                title = title,
                artist = artist,
                durationMs = duration,
                mediaId = mediaId,
                metadata = metadataValues,
                notificationTexts = activeTexts,
                pseudoTitle = lastVerdict == TitleVerdict.PSEUDO,
                titleLine = title
            )

            // 实测耗时才写进日志：L0 命中时这里是「解析一整段歌词」的真实成本，
            // 有缓存之后应当稳定在 0~1ms。数字比「感觉快了」可靠。
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val outcome = chain.resolve(trackContext)
            val costMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
            hitSource = outcome.hit?.sourceId
            currentLines = outcome.hit?.lines.orEmpty()

            if (Probe.enabled) {
                Probe.attempts = outcome.attempts
                Probe.metadataPreview = metadataValues.mapValues { preview(it.value) }
                Probe.notificationFields = activeTexts
                Probe.titleEvents = timeline.snapshot()
                Probe.hitSource = hitSource
                Probe.write(
                    "歌词",
                    "reason=$reason verdict=$lastVerdict hit=${hitSource ?: "none"} " +
                        "lines=${currentLines.size} buf=${titleStream.bufferedCount()} " +
                        "cost=${costMs}ms " +
                        "attempts=" + outcome.attempts.joinToString(",") { attempt ->
                            when (attempt) {
                                is LyricResult.Hit -> "${attempt.sourceId}:hit"
                                is LyricResult.Miss -> "${attempt.sourceId}:${attempt.reason}"
                            }
                        }
                )
            }
        }

        publishSnapshot(active, title, duration)

        // 渲染每次都走：即使输入没变，进度也在推进，当前行可能已经该换了。
        // 这一步只是二分定位 + 一次 setText（内容没变时悬浮层内部会再挡一层），
        // 与重跑解析完全不是一个量级。
        render()
        scheduleLineTick()
    }

    /** 换歌指纹的附加位：duration / mediaId 都缺失时，靠包名 + 专辑 + 音轨兜底。 */
    private fun identityExtra(active: MediaController, metadata: MediaMetadata?): String =
        buildString(40) {
            append(active.packageName)
            append('|')
            append(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            append('|')
            append(metadata?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) ?: 0L)
        }

    private fun render() {
        Perf.onRender()
        overlay.setPlaying(lastPlayState == PlayState.PLAYING)

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
                if (waiting) lastTitle else trackLabel(),
                prompt = true
            )
            return
        }

        val timed = currentLines.all { it.timeMs != null }
        val index = if (timed) clock.locate(currentLines, clock.position()) else currentLines.lastIndex
        val safe = index.coerceIn(0, currentLines.lastIndex)

        // 副行按用户选择取：逐行流没有时间轴，只有相邻行或「歌名·歌手」有意义。
        val secondary = when (prefs.style().secondary) {
            Secondary.NEXT -> currentLines.getOrNull(safe + 1)?.text
            Secondary.PREVIOUS -> currentLines.getOrNull(safe - 1)?.text
            Secondary.TRACK -> trackLabel()
            Secondary.NONE -> null
        }
        push(currentLines[safe].text, secondary, prompt = false)
    }

    // ------------------------------------------------------------ 定时歌词的自我推进

    /**
     * 行推进定时器。
     *
     * 带时间轴的歌词只会在「有人问现在到哪了」时才换行。之前那个「有人」其实是别的
     * 应用发通知 —— 通知一停，歌词就卡在某一句；而通知一来，整条责任链又被重跑一遍。
     * 现在改成按行边界自己唤醒：间隔最长 1 秒，一次只做二分定位 + 一次 setText。
     */
    private val lineTicker = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (currentLines.isEmpty()) return
            Perf.onTick()
            // 只重新定位并推文本，不碰责任链：链的输入没有变化，重跑只会得到同样的行。
            render()
            scheduleLineTick()
        }
    }

    private fun scheduleLineTick() {
        if (tickScheduled) return
        // 暂停时进度不动，行也不会换：定时器排上只会凭空唤醒。
        // 播放恢复时一定伴随一次状态回调，那时会重新排。
        if (lastPlayState != PlayState.PLAYING) return
        val delay = LyricTick.nextDelayMs(currentLines, clock.position())
        if (delay < 0) return
        tickScheduled = true
        mainHandler.postDelayed(lineTicker, delay)
    }

    private fun stopLineTick() {
        mainHandler.removeCallbacks(lineTicker)
        tickScheduled = false
    }

    private fun trackLabel(): String? = listOfNotNull(lastTitle, lastArtist)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .ifBlank { null }

    /**
     * 真正把文本推给悬浮层，并记录推了什么。
     *
     * `[渲染]` 这一行是为了回答「歌词已经取到了，为什么屏幕上没有」：它把
     * 交给 WindowManager 的原文与实际挂载状态一起写下来，取到行却没显示时
     * 一眼就能区分是「没渲染」还是「渲染了但窗口没挂上」。
     */
    private fun push(primary: String, secondary: String?, prompt: Boolean) {
        if (prompt) {
            overlay.showPrompt(primary, secondary)
        } else {
            overlay.showContent(primary, secondary)
        }
        if (!Probe.enabled) return
        val signature = "$primary\u0000${secondary.orEmpty()}\u0000$prompt"
        if (signature == lastRenderedSignature) return
        lastRenderedSignature = signature
        val trace = "text=${primary.take(24)} next=${secondary?.take(12) ?: "none"} " +
            "prompt=$prompt attached=${overlay.isAttached()} measured=${overlay.isMeasured()}"
        Probe.lastRender = trace
        Probe.write("渲染", trace)
    }

    private fun publishSnapshot(active: MediaController?, title: String?, duration: Long) {
        Probe.snapshot = ProbeSnapshot(
            listenerConnected = listenerConnected,
            listenerGranted = Permissions.isListenerGranted(appContext) == true,
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
     *
     * 两个细节是性能相关的：
     *  · 不再用 `getString(key)` 兜底 —— 位图键会走到它，白白触发一次类型告警；
     *  · `getText` 已经返回 String 时不再 `toString()` —— 歌词正文可能好几 KB，
     *    每秒复制一遍就是在给 GC 添活。
     */
    private fun textValues(metadata: MediaMetadata?): Map<String, Any?> {
        if (metadata == null) return emptyMap()
        val keys = metadata.keySet()
        val values = HashMap<String, Any?>(keys.size)
        for (key in keys) {
            val text = metadata.getText(key)
            values[key] = when {
                text == null -> null
                text is String -> text
                else -> text.toString()
            }
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
        lastInputKey = null
        lastRenderedSignature = null
        stopLineTick()
        // 进度跳变也必须按会话重置：换应用时 position 从 89s 掉到 44s
        // 是正常的，不能计成 CLOCK_UNSTABLE。
        lastPosition = 0L
        positionJumps = 0
        titleOnlyOnPause = false
        lastTitleChangeAt = SystemClock.elapsedRealtime()
        overlay.resetCollapsed()
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
        /** 刷新原因。用常量而不是散落的字面量：合并与限流都靠它判优先级。 */
        private const val REASON_STATE = "state"
        private const val REASON_METADATA = "metadata"
        private const val REASON_NOTIFICATION = "notification"
        private const val REASON_NOTIFICATION_OTHER = "notification_other"
        private const val REASON_UI = "overlay_event"

        /** 状态／元数据回调里，两次完整会话查询的最小间隔。 */
        private const val QUERY_MIN_INTERVAL_MS = 3_000L

        /** 当前会话正在播放时，外来通知触发的会话重挑间隔：防止刷屏设备空转。 */
        private const val QUERY_FOREIGN_INTERVAL_MS = 5_000L

        /**
         * 当前会话已不在播放时，外来通知触发的会话重挑间隔。
         *
         * 这个更短是有原因的：用户点了另一个播放器，旧会话会先暂停，然后新播放器才发通知。
         * 5 秒的间隔会让悬浮窗有 5 秒显示着已经停掉的旧歌词。
         */
        private const val QUERY_FOREIGN_ACTIVE_MS = 1_000L

        /** 标题停下多久还没歌词，就承认这个播放器没有歌词通道。 */
        private const val TITLE_WAIT_TIMEOUT_MS = 20_000L

        /** 回跳幅度超过这个值视为用户拖动进度，不计入「进度不稳」。 */
        private const val POSITION_JITTER_MAX_MS = 5_000L

        /** 位置落到这个值以下视为换歌／重播，不是回跳。 */
        private const val RESTART_NEAR_ZERO_MS = 2_000L

        /** 两次主动重绑请求的最小间隔，避开系统对 requestRebind 的频率限制。 */
        private const val REBIND_MIN_INTERVAL_MS = 5_000L

        /** 首条资源采样延迟：启动瞬间的数字虚高（类加载、布局），没参考价值。 */
        private const val RESOURCE_FIRST_DELAY_MS = 3_000L

        /** 资源采样间隔。一分钟一条，一天 1440 行，日志缓冲 300 行足够回溯两小时。 */
        private const val RESOURCE_INTERVAL_MS = 60_000L

        /**
         * 降级模式的兜底查询间隔。
         *
         * 只在这个期间跑：服务一旦被系统绑上就停。8 秒一次 binder 查询比等推送重得多，
         * 但比「整个功能不可用」划算，且不会随播放器刷新频率放大。
         */
        private const val DEGRADED_INTERVAL_MS = 8_000L

        @Volatile
        private var instance: SessionHub? = null

        fun get(context: Context): SessionHub =
            instance ?: synchronized(this) {
                instance ?: SessionHub(context.applicationContext).also { instance = it }
            }
    }
}
