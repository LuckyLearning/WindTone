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
import com.anylyric.accessibility.ForegroundApps
import com.anylyric.accessibility.overlayHiddenFor
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
import com.anylyric.lyrics.online.OnlineLyricsFetcher
import com.anylyric.lyrics.TitleVerdict
import com.anylyric.lyrics.TrackContext
import com.anylyric.lyrics.sources.ExtrasSource
import com.anylyric.lyrics.sources.L0MetadataSource
import com.anylyric.lyrics.sources.L1TitleStreamSource
import com.anylyric.lyrics.sources.L2NotificationSource
import com.anylyric.overlay.OverlayHost
import com.anylyric.service.BootPolicy
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
        val verdict: TitleVerdict,

        /** TITLE 与歌词行相同：播放器在拿 TITLE 推歌词，不能当歌名展示。 */
        val titleIsLyric: Boolean = false
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
        listOf(L0MetadataSource(), ExtrasSource(), titleStream, L2NotificationSource())
    )
    private val overlay = OverlayHost(appContext, prefs)

    /**
     * 本应用自己的界面当前有几个在屏上（≥1 即让悬浮窗让位）。
     *
     * 用计数而不是布尔：设置页 → 诊断页这种「先 onStart 新的、后 onStop 旧的」顺序下，
     * 布尔会先置 true 再被旧的 onStop 置 false，悬浮窗就在两页之间闪一下。
     */
    private var ownUiCount = 0

    /** 前台应用变化：只重算一次隐藏态，不做任何其它事。 */
    private val foregroundListener: (String?) -> Unit = { applyHideState() }

    /** packageName -> 通知文本字段。只在内存，不落盘。 */
    private val notificationTexts = HashMap<String, Map<String, String>>()

    /**
     * packageName -> 上一次通知的**原始** 5 个文本字段（未 trim）。
     *
     * 存在的唯一目的是「零分配地判断这条通知有没有变」：播放器每 250ms 刷一条通知，
     * 内容一模一样。以前每条都要建 HashMap、拼指纹串、再判等一次 —— 那些对象最后全进 GC。
     * 现在只把 5 个 `CharSequence` 读进一个复用数组，逐字段 `TextUtils.equals` 比完就结束。
     */
    private val lastNotificationFields = HashMap<String, Array<CharSequence?>>()

    /** 复用数组：只在主线程写，不需要同步 */
    private val fieldBuffer = arrayOfNulls<CharSequence>(FIELD_KEYS.size + 1)

    private var controller: MediaController? = null
    private var listenerConnected = false

    /** 上次向系统请求重绑的时刻，用于节流（系统自身对 requestRebind 有频率限制）。 */
    private var lastRebindAt = 0L

    private var currentLines: List<LyricLine> = emptyList()

    /** 在线补词的结果；只在本地通道全 miss 时使用。 */
    private var onlineLines: List<LyricLine> = emptyList()

    /** 已经为本首歌发起过在线请求的键，避免每次刷新都重复请求。 */
    private var onlineRequestedKey: String? = null

    /**
     * 无时间轴行集里「当前行」的锚点。-1 表示取最后一行（L1 缓冲的形态）。
     *
     * 由命中的来源显式给出（见 [LyricResult.Hit.currentLineIndex]）：L2 的多行通知
     * 第一行才是当前句，旧实现一律取末行，等于把下一句当当前句画。
     */
    private var currentAnchorIndex = -1
    private var hitSource: String? = null

    /** 展示用标题 / 歌手：多键兜底 + 粘住上一次非空值。 */
    private var lastTitle: String? = null
    private var lastArtist: String? = null

    /** 原始 TITLE：伪歌名判定与逐行流必须看它，不能看粘住后的展示值。 */
    private var lastRawTitle: String? = null

    /** 粘住的上一次非空展示值：换歌瞬间元数据为空时不至于标题消失。 */
    private var stickyTitle: String? = null
    private var stickyArtist: String? = null

    /** BYD 第三方媒体控制 SDK 的兜底元数据（仅 BYD 车机会有）。 */
    private var externalTitle: String? = null
    private var externalArtist: String? = null
    private var externalLyrics: String? = null

    /** 上次的换歌指纹：同一会话内 mediaId/duration/album/track 变了才算换歌。 */
    private var lastIdentity: String? = null
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

    /**
     * 责任链的输入指纹（逐字段比较，不再拼字符串）。
     *
     * 责任链的产物只取决于「哪个会话 + 元数据内容 + 标题/歌手/时长/媒体号 + 判定 + 通知文本」。
     * 这些东西都没变时，重跑一遍解析不可能得到不同结果 —— 而真机上元数据与状态回调
     * 一秒能来好几个，全量重跑就是拿轮询冒充事件驱动。
     *
     * 元数据用**内容指纹**而不是对象身份：有的播放器每次回调都新建一个 `MediaMetadata`
     * （跨 binder 反序列化的必然结果），按身份判等会让指纹永远「变了」。
     */
    private var keyValid = false
    private var keyController: MediaController? = null
    private var keyMetaSig = 0
    private var keyTitle: String? = null
    private var keyArtist: String? = null
    private var keyDuration = Long.MIN_VALUE
    private var keyMediaId: String? = null
    private var keyVerdict: TitleVerdict? = null
    private var keyTextsHash = 0

    /**
     * `PlaybackState.extras` 里歌词字段的指纹。
     *
     * 必须进输入指纹：BYD 这类播放器把歌词放在 extras，且**逐行变化**。如果只看
     * 元数据/通知，责任链永远不会因为「歌词换了一句」而重跑，滚动窗口就卡在第一句。
     */
    private var keyExtrasSig = 0

    /** `identityExtra` 的缓存：包名/专辑/音轨三者逐字段比较，都没变就复用上次拼好的串。 */
    private var identityPkg: String? = null
    private var identityAlbum: String? = null
    private var identityTrack = Long.MIN_VALUE
    private var identityExtraValue = ""

    /** 上一次推给悬浮层的文本，同一句不重复走渲染。 */
    private var lastPushedPrimary: String? = null
    private var lastPushedSecondary: List<String> = emptyList()
    private var lastPushedTitle: String? = null
    private var lastPushedPrompt = false

    /**
     * 上下文行的缓存。
     *
     * `render()` 每个状态回调都会跑一遍（一秒好几次），而真正换行一秒才一次。
     * 每次为新的一句重新建一个 List 纯属喂 GC —— 行号、歌词表都没变时直接复用。
     */
    private var ctxIndex = -1
    private var ctxRef: List<LyricLine>? = null
    private var ctxResult: List<String> = emptyList()

    /** `trackLabel()` 的缓存，随标题/歌手变化失效。 */
    private var labelCache: String? = null

    /** 当前歌词是否全带时间轴。换歌时算一次，逐行流不必每次全表扫描。 */
    private var currentLinesTimed = false

    /** 上一次渲染时定位到的行号，用于让定时器「行没换就别重绘」。 */
    private var lastLineIndex = -1

    /** 刷新合并：同一帧内的多个回调只跑一次全量刷新。 */
    private var refreshScheduled = false
    private var pendingReason: String? = null

    /**
     * 合并后真正执行刷新的任务。
     *
     * 复用一个实例：事件量最大的时候一秒能有好几次 `requestRefresh`，
     * 每次都 new 一个 lambda 只是给 GC 送料。它只读 [pendingReason]，没有额外状态。
     */
    private val refreshTask = Runnable {
        refreshScheduled = false
        val next = pendingReason ?: REASON_UI
        pendingReason = null
        refreshNow(next)
    }

    /** 上一次为「外来通知」重挑会话的时刻，与播放状态相关（见 [foreignQueryInterval]）。 */
    private var lastForeignPickAt = 0L

    /** 定时歌词的自驱动 tick 是否已排上。 */
    private var tickScheduled = false

    /**
     * 回调里拿到的元数据与播放状态。
     *
     * `MediaController.getMetadata()` / `getPlaybackState()` 每次都是**跨进程调用**，
     * 而且会把整个 Bundle 重新反序列化一遍 —— 播放器把整段歌词塞进元数据时，
     * 每次刷新都等于搬运几 KB。而这两个回调本来就把最新对象送到手上了，直接用即可。
     * `valid=false` 时（刚换会话、回调还没来）才退回主动查询。
     */
    private var callbackMetadata: MediaMetadata? = null
    private var callbackMetadataValid = false
    private var callbackState: PlaybackState? = null
    private var callbackStateValid = false

    /**
     * 上一次喂给 [clock] 的原始进度 / 状态 / 倍速。
     *
     * 读缓存之后同一个进度会被反复看到。若每次都拿它重新锚定时钟，
     * `anchorAt` 会被不断刷新到「现在」，外推就永远等于原值 —— 表现为歌词卡住不动。
     * 只在值真的变化时才锚定。
     */
    private var clockRawPosition = Long.MIN_VALUE
    private var clockRawState = PlayState.NONE
    private var clockRawSpeed = Float.MIN_VALUE

    /** TITLE 最近一次变化的时刻，用于区分「还在等第一句」与「这播放器没有歌词通道」。 */
    private var lastTitleChangeAt = SystemClock.elapsedRealtime()

    /** 上一次真正写进悬浮层的文本，避免同一句反复重绘与重复记日志。 */
    private var lastRenderedSignature = Int.MIN_VALUE

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
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // 回调给的对象就是最新的：留一份，省掉每次刷新一次跨进程查询
            callbackMetadata = metadata
            callbackMetadataValid = true
            requestRefresh(REASON_METADATA)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            callbackState = state
            callbackStateValid = true
            requestRefresh(REASON_STATE)
        }

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
        verdict = lastVerdict,
        titleIsLyric = titleIsLyric()
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
        // 调试关掉时快照根本没人看，也不必为它做一次跨进程查询
        if (!Probe.enabled) return
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
        // 刚连上是看门狗最需要的事实：必须立刻落盘，不能等 5 分钟的节流窗口。
        prefs.markAlive(force = true)
        if (Probe.enabled) Probe.write("权限", "listenerConnected=true")
        requestRefresh("listener_connected")
        maybeBootStandby()
    }

    /**
     * 开机自启的另一半：不只是把服务绑回来，还要让悬浮窗**真的出现在屏上**。
     *
     * 没有这一步，车机开机后就算服务活了，也要等用户播放音乐才有任何可见反馈，
     * 看起来就像「没启动」。刚开机 [BOOT_STANDBY_WINDOW_MS] 内（且开关打开）
     * 补一条待播提示；如果刷新已经画了真歌词或「暂无歌词」，就不重复盖一条。
     * [SystemClock.elapsedRealtime] 就是本次开机的时长，不需要任何状态文件。
     */
    private fun maybeBootStandby() {
        if (!BootPolicy.shouldShowStandby(
                SystemClock.elapsedRealtime(), prefs.bootAutostart, BOOT_STANDBY_WINDOW_MS
            )
        ) return
        mainHandler.postDelayed({
            if (!overlay.hasWindow()) {
                overlay.showPrompt(appContext.getString(R.string.overlay_standby), emptyList())
                if (Probe.enabled) Probe.write("渲染", "boot standby prompt shown")
            }
        }, BOOT_STANDBY_DELAY_MS)
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        if (Probe.enabled) Probe.write("权限", "listenerDisconnected")
        detachController()
        setCurrentLines(emptyList())
        hitSource = null
        keyValid = false
        lastNotificationFields.clear()
        stopLineTick()
        overlay.hide()
        syncPermissionFacts()
        // 权限还勾着却被系统解绑（内存紧张回收、系统重启服务）：再要一次绑定，
        // 免得悬浮窗就此消失、只能等用户重新打开应用。已授权才会真的发请求。
        tryRebind()
    }

    /**
     * BYD 第三方媒体控制 SDK 推来的元数据（主线程）。
     *
     * 车机上标准 MediaSession 常常很稀疏，BYD 自己的媒体中心才拿得到真实歌名 / 歌手 / 歌词。
     * 这里只作为**兜底源**：标准 TITLE 为空或是歌词（伪歌名）时才采用，正常播放器不会被它覆盖。
     */
    fun onBydMetadata(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        lyrics: String?
    ) {
        if (Probe.enabled) {
            Probe.write(
                "会话",
                "byd metadata title=$title artist=$artist album=$album dur=$durationMs"
            )
        }
        var changed = false
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTitle != null && cleanTitle != externalTitle) {
            externalTitle = cleanTitle
            // 换歌：旧的外部歌词作废；新歌词会随同一个 bundle 一起到。
            externalLyrics = null
            stickyTitle = null
            stickyArtist = null
            onlineLines = emptyList()
            onlineRequestedKey = null
            changed = true
        }
        val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanArtist != null && cleanArtist != externalArtist) {
            externalArtist = cleanArtist
            changed = true
        }
        val cleanLyrics = lyrics?.takeIf { it.isNotBlank() }
        if (cleanLyrics != null && cleanLyrics != externalLyrics) {
            externalLyrics = cleanLyrics
            changed = true
        }
        if (!changed) return
        // 外部源变了：标题 / 歌词都可能来自它，强制下一轮重跑责任链。
        keyValid = false
        requestRefresh("byd_metadata")
    }

    fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        if (pkg == appContext.packageName) return

        // 能收到通知回调 = 服务活着。心跳顺带在这里更新（内部 5 分钟节流），
        // 空转时也能证明「没掉线」，避免看门狗把正常常驻误判成掉线反复重绑。
        prefs.markAlive()

        val current = controller
        // 「与本应用无关」= 有会话但不是这个包，或者根本没有会话。
        val foreign = current == null || current.packageName != pkg

        // 先判节流，再碰 extras。**这是本应用最容易被自己拖死的一处**：
        // 代理、应用商店这类应用每秒发一条通知，之前每条都要拆 extras、拼指纹、
        // 再跑一遍全量刷新，实测稳定吃掉 17% CPU。节流窗口内连解析都不做。
        val now = SystemClock.elapsedRealtime()
        if (foreign) {
            if (now - lastForeignPickAt < foreignQueryInterval()) {
                Perf.onSkip()
                return
            }
            lastForeignPickAt = now
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 第二道闸：内容没变就直接结束。
        // 这一层挡的是「同一条通知反复重发」——播放器每 250ms 刷一次进度时最常见，
        // 内容一个字都不差。以前每条都要建 HashMap + 拼指纹串，白白喂给 GC。
        if (!captureFields(extras, notification.tickerText, lastNotificationFields[pkg])) {
            Perf.onSkip()
            return
        }
        lastNotificationFields[pkg] = fieldBuffer.copyOf()
        // 上限是硬边界：设备上装几十个会发通知的应用时，这两张表不该无限长。
        // 必须一起清 —— 只清一张会让「上次内容」与「当前内容」对不上，
        // 之后每条通知都被判成「变了」，白白多跑一遍刷新。
        // 键以 lastNotificationFields 为准：连一条文本都没有的通知也会在那张表里留位置。
        if (lastNotificationFields.size > MAX_NOTIFICATION_PACKAGES) {
            notificationTexts.clear()
            lastNotificationFields.clear()
        }

        val fields = HashMap<String, String>(5)
        for (i in FIELD_KEYS.indices) {
            val value = fieldBuffer[i]?.toString()?.trim()
            if (!value.isNullOrEmpty()) fields[FIELD_KEYS[i]] = value
        }
        // tickerText 不是 extras 常量，是 Notification 上的字段
        val ticker = fieldBuffer[FIELD_KEYS.size]?.toString()?.trim()
        if (!ticker.isNullOrEmpty()) fields[KEY_TICKER] = ticker

        if (fields.isEmpty()) {
            notificationTexts.remove(pkg)
        } else {
            notificationTexts[pkg] = fields
        }

        if (Probe.enabled) {
            Probe.write("通知", "pkg=$pkg fields=${fields.keys.joinToString(",")}")
        }

        if (foreign) {
            requestRefresh(REASON_NOTIFICATION_OTHER)
            return
        }

        requestRefresh(REASON_NOTIFICATION)
    }

    /**
     * 把 5 个通知文本字段读进 [fieldBuffer]，返回「与 [previous] 相比是否有变化」。
     *
     * 全过程**不分配对象**：`getCharSequence` 拿到的是播放器那边已有的 CharSequence，
     * `TextUtils.equals` 逐字符比较、不构造 String。只有确认变了之后才去做 trim 与建表 ——
     * 那才是少数情况。
     *
     * @param previous 上一条同一包名的通知字段；`null` 表示第一次见到这个包
     */
    private fun captureFields(
        extras: android.os.Bundle,
        ticker: CharSequence?,
        previous: Array<CharSequence?>?
    ): Boolean {
        var changed = previous == null
        for (i in FIELD_KEYS.indices) {
            val value = extras.getCharSequence(FIELD_KEYS[i])
            fieldBuffer[i] = value
            if (!changed && !equalsText(value, previous!![i])) changed = true
        }
        fieldBuffer[FIELD_KEYS.size] = ticker
        if (!changed && !equalsText(ticker, previous!![FIELD_KEYS.size])) changed = true
        return changed
    }

    private fun equalsText(a: CharSequence?, b: CharSequence?): Boolean =
        android.text.TextUtils.equals(a, b)

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
        // 前台包名可能在本服务起来之前就已经报过了（无障碍服务先被系统拉起），
        // 所以注册完立刻按当前值重算一次，别等下一次应用切换。
        ForegroundApps.addListener(foregroundListener)
        applyHideState()
        requestRefresh("attach")
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        refreshScheduled = false
        pendingReason = null
        tickScheduled = false
        degraded = false
        detachController()
        // 在线结果跟着服务生命周期释放；缓存本身有界（64 首），留着避免重绑后来回重抓。
        onlineLines = emptyList()
        onlineRequestedKey = null
        // BYD 外部源也一起清掉：服务销毁后它不再可信，重绑时会重新推。
        externalTitle = null
        externalArtist = null
        externalLyrics = null
        stickyTitle = null
        stickyArtist = null
        lastRawTitle = null
        lastIdentity = null
        // 服务销毁用 release 而不是 hide：连 OverlayHost 自己的回调（隐藏到期等）
        // 一起摘掉，否则残留的 hiddenUntil 会把下一次会话的歌词卡在「隐藏期内」。
        overlay.release()
        // 监听也要摘：否则服务重启前残留的前台事件仍会来改（已不存在的）窗口状态。
        ForegroundApps.removeListener(foregroundListener)
    }

    // ---------------------------------------------------------------- 按应用隐藏

    /**
     * 本应用界面进出屏（由 [com.anylyric.ui.BaseActivity] 的 onStart/onStop 驱动）。
     *
     * 这一条**不需要任何权限**：自己的设置页、诊断页本来就该让出屏幕 ——
     * 悬浮窗压在上面时，滑块和开关根本点不到。
     */
    fun setOwnUiVisible(visible: Boolean) {
        ownUiCount = if (visible) ownUiCount + 1 else (ownUiCount - 1).coerceAtLeast(0)
        applyHideState()
    }

    /**
     * 隐藏名单或开关变了：立刻重算，不用等下一次前台切换。
     *
     * 关掉开关时顺手清掉残留包名 —— 否则下次打开会先按旧包名藏一下。
     */
    fun refreshHideByApp() {
        if (!prefs.hideByAppEnabled) ForegroundApps.reset()
        applyHideState()
    }

    /**
     * 两个来源取「或」：本应用界面可见，或当前前台命中名单。
     *
     * 合成一个布尔再交给 [OverlayHost.setSuppressed]，是为了让窗口层只有一个
     * 「临时隐藏」状态 —— 两个来源各自撤销时不会互相把对方的状态覆盖掉。
     */
    private fun applyHideState() {
        // 编辑态例外：用户正在设置页里拖位置、调大小，这时把窗口藏起来等于让人对着空气操作。
        // 名单命中仍然优先（[byApp] 不看编辑态）—— 用户既然勾了「这些应用里别挡」，
        // 编辑态也不该成为例外。
        val byOwnUi = ownUiCount > 0 && !overlay.isEditable()
        val byApp = prefs.hideByAppActive &&
            overlayHiddenFor(ForegroundApps.currentPkg, prefs.hiddenApps)
        val hide = byOwnUi || byApp
        // 两个来源叠加时结果常常不变（例如已在名单应用里，又点开了自己的设置页）：
        // 结果没变就什么都不做，连一次窗口 API 调用都不发。
        if (overlay.isSuppressed() == hide) return
        overlay.setSuppressed(hide)
        // 真机上「该隐藏却没隐藏」时，这条日志能直接回答「事件有没有来、命中的是谁」。
        // 先判开关再拼串：Probe.write 内部虽然也会早退，但那是在参数已经拼好之后。
        if (Probe.enabled) {
            Probe.write(
                "隐藏",
                if (hide) {
                    "悬浮窗让位（自身界面=$byOwnUi 名单命中=$byApp 前台=${ForegroundApps.currentPkg}）"
                } else {
                    "悬浮窗恢复（前台=${ForegroundApps.currentPkg ?: "-"}）"
                }
            )
        }
    }

    /** 编辑态进出后重算一次：进入编辑要显示窗口，退出编辑要恢复原判定。 */
    fun refreshHideState() {
        applyHideState()
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
        mainHandler.post(refreshTask)
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
            // 只有「真连上」才记心跳：降级模式下进程活着但服务没绑，看门狗本来就该重绑。
            // 这里写盘由 Prefs 内部节流（5 分钟一次），刷新频率再高也不会放大写放大。
            prefs.markAlive()
        }

        // getActiveSessions 是跨进程调用，且会把每个会话的元数据整包搬过来。
        // 只有「可能换应用」的路径才值得查：无会话、通知类事件、播放状态发生跳变，
        // 其余情况直接沿用当前控制器。
        val now = SystemClock.elapsedRealtime()
        // 播放状态优先用回调送来的那一份：`getPlaybackState()` 是本方法里最贵的几次
        // 跨进程调用之一，而回调本来就会把同一个对象递到手上。
        val knownState = if (callbackStateValid) callbackState else controller?.playbackState
        val cachedState = mapState(knownState?.state ?: PlaybackState.STATE_NONE)
        val fullQuery = when {
            controller == null -> true
            reason == REASON_NOTIFICATION_OTHER -> now - lastFullQueryAt >= foreignQueryInterval()
            reason == REASON_STATE || reason == REASON_METADATA || reason == REASON_NOTIFICATION ->
                cachedState != lastPlayState || now - lastFullQueryAt >= QUERY_MIN_INTERVAL_MS
            else -> true
        }
        // 不查会话时直接用当前控制器，不再包一层 listOfNotNull —— 那是每次刷新
        // 一两个临时 List，一秒好几个，最后全进 GC。
        val sessions: List<MediaController>? = if (fullQuery) {
            lastFullQueryAt = now
            querySessions().also {
                lastSessionCount = it.size
                lastPlayingCount = it.count { c ->
                    c.playbackState?.state == PlaybackState.STATE_PLAYING
                }
            }
        } else {
            null
        }

        val previousToken = controller?.sessionToken
        val next = if (sessions != null) pick(sessions) else controller
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
            setCurrentLines(emptyList())
            hitSource = null
            lastTitle = null
            lastArtist = null
            lastRawTitle = null
            stickyTitle = null
            stickyArtist = null
            lastIdentity = null
            labelCache = null
            keyValid = false
            stopLineTick()
            overlay.hide()
            publishSnapshot(null, null, 0L)
            return
        }

        // 两处都优先取回调缓存：换会话时 [bind] 已经把缓存清掉，这里自然退回主动查询。
        val playbackState = if (callbackStateValid) callbackState else active.playbackState
        val metadata = if (callbackMetadataValid) callbackMetadata else active.metadata
        val playState = mapState(playbackState?.state ?: PlaybackState.STATE_NONE)
        lastPlayState = playState
        val rawPosition = playbackState?.position ?: -1L
        val rawSpeed = playbackState?.playbackSpeed ?: 1f

        // 只有「确实变了」才重新锚定时钟。读缓存后同一个进度会被反复看到，
        // 若每次都锚定，外推永远等于原值 —— 歌词会卡在当前句不动。
        if (rawPosition != clockRawPosition ||
            playState != clockRawState ||
            rawSpeed != clockRawSpeed
        ) {
            clockRawPosition = rawPosition
            clockRawState = playState
            clockRawSpeed = rawSpeed
            clock.update(rawPosition, rawSpeed, playState)
        }
        observePosition(rawPosition, playState)

        // 标准元数据不止 TITLE / ARTIST：车机上不少播放器只填 DISPLAY_TITLE /
        // ALBUM_ARTIST / DISPLAY_SUBTITLE，只认前两个就会「时不时取不到」。
        val standardTitle = TitleResolver.firstNonBlank(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        )
        val standardArtist = TitleResolver.firstNonBlank(
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        )
        val rawDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        // 有的播放器把时长按「秒」上报（240 而不是 240000）。不到 10 秒的音乐不存在，
        // 这个量级只可能是单位错了 —— 不修正时进度会被夹在开头、行推进永远停在第一句。
        val duration = if (rawDuration in 1..9_999L) rawDuration * 1_000L else rawDuration
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        // 换歌判定：同一会话内 mediaId / duration / album / track 变了才清掉粘住的标题。
        val identity = identityExtra(active, metadata)
        if (identity != lastIdentity) {
            lastIdentity = identity
            stickyTitle = null
            stickyArtist = null
            externalLyrics = null
        }

        // BYD 兜底值只对 BYD 系包名生效：切到别的播放器后，上一首的 BYD 歌名不能沿用到它身上。
        val useExternal = isBydPackage(active.packageName)
        val externalT = externalTitle.takeIf { useExternal }
        val externalA = externalArtist.takeIf { useExternal }

        // 伪歌名判定与逐行流必须看**原始 TITLE**：展示标题会被粘住或替换成 BYD 的真实歌名，
        // 拿它去判定的话「一行一变」这个关键特征就没了。
        val rawTitle = standardTitle ?: externalT
        val rawArtist = standardArtist ?: externalA

        if (rawTitle != lastRawTitle) lastTitleChangeAt = now
        lastRawTitle = rawTitle

        val previousVerdict = lastVerdict
        lastVerdict = timeline.record(now, rawTitle, duration, mediaId, rawArtist, identity)
        if (timeline.consumeIdentityChanged()) {
            titleStream.reset()
            titleOnlyOnPause = false
            lastTitleChangeAt = now
            // 换歌必然伴随 position 归零／重置，不能计成进度不稳
            lastPosition = 0L
            positionJumps = 0
        }

        if (!rawTitle.isNullOrBlank()) {
            // 关键：判定未成立时也要入缓冲。若等判定成立才入，判定前看到的几句
            // 会被永久丢掉，第一句还得再等一个歌词周期才出现。
            titleStream.onTitleLine(rawTitle)
            if (lastVerdict == TitleVerdict.PSEUDO && playState == PlayState.PAUSED) {
                titleOnlyOnPause = true
            }
        }

        // 展示标题：原始 TITLE 为空或是歌词（伪歌名）时，优先用 BYD 外部源的真实歌名；
        // 仍然取不到就保留上一次非空值（粘住），别让换歌 / 切前后台的瞬间标题消失。
        val standardIsLyric = TitleResolver.isLyricTitle(standardTitle, currentLines)
        val preferExternal = standardTitle.isNullOrBlank() ||
            standardIsLyric ||
            lastVerdict == TitleVerdict.PSEUDO
        val chosenTitle = if (preferExternal) {
            TitleResolver.firstNonBlank(externalT)
                ?: if (standardIsLyric) null else standardTitle
        } else {
            standardTitle
        }
        if (!chosenTitle.isNullOrBlank()) stickyTitle = chosenTitle
        // 明确判定「原始 TITLE 就是歌词」且没有外部真实歌名时，宁可空着也不回落到上一首的旧标题。
        val displayTitle = when {
            !chosenTitle.isNullOrBlank() -> chosenTitle
            standardIsLyric -> null
            else -> stickyTitle
        }

        val chosenArtist = if (preferExternal) {
            TitleResolver.firstNonBlank(externalA) ?: standardArtist
        } else {
            standardArtist
        }
        if (!chosenArtist.isNullOrBlank()) stickyArtist = chosenArtist
        val displayArtist = when {
            !chosenArtist.isNullOrBlank() -> chosenArtist
            standardIsLyric -> null
            else -> stickyArtist
        }

        if (displayTitle != lastTitle || displayArtist != lastArtist) {
            labelCache = null
            // 换歌：上一首的在线结果必须立刻作废，否则新歌开头几秒会闪出旧歌词。
            onlineLines = emptyList()
            onlineRequestedKey = null
            hitSource = null
        }
        lastTitle = displayTitle
        lastArtist = displayArtist

        if (Probe.enabled && lastVerdict != previousVerdict) {
            Probe.write(
                "歌词",
                "verdict=${previousVerdict.name}->${lastVerdict.name} " +
                    "changes=${timeline.snapshot().size} buf=${titleStream.bufferedCount()}"
            )
        }

        // 责任链只在这些输入真的变了时才重跑。元数据比的是**内容指纹**而不是对象身份：
        // 有的播放器每次回调都新建一个 MediaMetadata，按身份判等会让指纹永远「变了」，
        // 于是每个回调都重跑一遍解析 —— 正是真机上 CPU 被吃掉的那条路。
        val activeTexts = notificationTexts[active.packageName].orEmpty()
        val metaSig = metadataSignature(metadata)
        val extrasSig = extrasSignature(playbackState)
        val inputsChanged = !keyValid ||
            keyController !== active ||
            keyMetaSig != metaSig ||
            keyTitle != rawTitle ||
            keyArtist != rawArtist ||
            keyDuration != duration ||
            keyMediaId != mediaId ||
            keyVerdict != lastVerdict ||
            keyTextsHash != textsHash(activeTexts) ||
            keyExtrasSig != extrasSig

        if (inputsChanged) {
            keyValid = true
            keyController = active
            keyMetaSig = metaSig
            keyTitle = rawTitle
            keyArtist = rawArtist
            keyDuration = duration
            keyMediaId = mediaId
            keyVerdict = lastVerdict
            keyTextsHash = textsHash(activeTexts)
            keyExtrasSig = extrasSig
            Perf.onChain()

            val metadataValues = textValues(metadata)
            lastMetadataKeys = metadataValues.keys.toList()

            val trackContext = TrackContext(
                packageName = active.packageName,
                title = rawTitle,
                artist = rawArtist,
                durationMs = duration,
                mediaId = mediaId,
                metadata = metadataValues,
                notificationTexts = activeTexts,
                // 只在输入真的变了时才建表：BYD 的 lrc_line_* 每换一句都会走到这里，
                // 但每秒多次的进度刷新不会，成本被输入指纹挡在前面。
                playbackExtras = buildPlaybackExtras(playbackState, active.packageName),
                pseudoTitle = lastVerdict == TitleVerdict.PSEUDO,
                titleLine = rawTitle
            )

            // 实测耗时才写进日志：L0 命中时这里是「解析一整段歌词」的真实成本，
            // 有缓存之后应当稳定在 0~1ms。数字比「感觉快了」可靠。
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val outcome = chain.resolve(trackContext)
            val costMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
            val hit = outcome.hit
            if (hit != null) {
                onlineLines = emptyList()
                hitSource = hit.sourceId
                setCurrentLines(hit.lines, hit.currentLineIndex)
            } else if (onlineLines.isNotEmpty()) {
                // 本地全 miss，但上一轮在线补词已拿到结果：继续用。
                // 否则每次刷新都会把它清掉，在线模式等于失效。
                hitSource = ONLINE_SOURCE
                setCurrentLines(onlineLines, -1)
            } else {
                hitSource = null
                setCurrentLines(emptyList())
            }
            // 在线匹配用**展示用**歌名/歌手：它才是用户看到的真实曲目信息。
            if (hit == null) maybeRequestOnline(displayTitle, displayArtist, duration)

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

        publishSnapshot(active, displayTitle, duration)

        // 渲染每次都走：即使输入没变，进度也在推进，当前行可能已经该换了。
        // 这一步只是二分定位 + 一次 setText（内容没变时悬浮层内部会再挡一层），
        // 与重跑解析完全不是一个量级。
        render()
        scheduleLineTick()
    }

    /** 换歌指纹的附加位：duration / mediaId 都缺失时，靠包名 + 专辑 + 音轨兜底。 */
    private fun identityExtra(active: MediaController, metadata: MediaMetadata?): String {
        val pkg = active.packageName
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val track = metadata?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) ?: -1L
        // 逐字段比较，不先拼串：这几个值每首歌内都不变，每秒为它们拼一次串只是给 GC 添活。
        if (pkg == identityPkg && album == identityAlbum && track == identityTrack) {
            return identityExtraValue
        }
        identityPkg = pkg
        identityAlbum = album
        identityTrack = track
        identityExtraValue = "$pkg|${album.orEmpty()}|$track"
        return identityExtraValue
    }

    /**
     * 元数据内容指纹。
     *
     * 比对象身份可靠：跨 binder 的 `MediaMetadata` 每次回调都是新对象，按身份判等会让
     * 「输入变了」恒为真，责任链就成了 1Hz 轮询。这里只看内容 —— 键名 + 文本值，
     * 字符串的 hashCode 首次算完即缓存，之后是 O(1)。
     *
     * 0.2.6 补了一层对象身份缓存：回调缓存生效后，同一个对象会被刷新反复看到，
     * 而算一次指纹要遍历整个 keySet。同一个实例的内容不可能变（MediaMetadata 是不可变快照）。
     */
    private var metaSigSource: MediaMetadata? = null
    private var metaSigValue = 0

    private fun metadataSignature(metadata: MediaMetadata?): Int {
        if (metadata == null) return 0
        if (metadata === metaSigSource) return metaSigValue
        var hash = 1
        for (key in metadata.keySet()) {
            hash = 31 * hash + key.hashCode()
            val text = metadata.getText(key) ?: continue
            hash = 31 * hash + text.hashCode()
        }
        metaSigSource = metadata
        metaSigValue = hash
        return hash
    }

    /**
     * `PlaybackState.extras` 的歌词字段指纹。
     *
     * 按对象身份缓存：回调缓存生效后，同一个 `PlaybackState` 会被刷新反复看到。
     * 值只取 4 个键的 hashCode / length —— 不建表、不 toString，逐行驱动的热路径上零分配。
     */
    private var extrasSigSource: PlaybackState? = null
    private var extrasSigValue = Int.MIN_VALUE

    private fun extrasSignature(state: PlaybackState?): Int {
        if (state == null) return 0
        if (state === extrasSigSource) return extrasSigValue
        val extras = try {
            state.extras
        } catch (_: Throwable) {
            null
        }
        if (extras == null) {
            extrasSigSource = state
            extrasSigValue = 0
            return 0
        }
        var hash = 1
        for (key in EXTRA_LYRIC_KEYS) {
            val value = extras.getCharSequence(key)
            // String.hashCode 首次算完即缓存；Spannable 之类没重写 hashCode（是身份哈希），
            // 不转成文本会让指纹每次都「变了」，责任链退回 1Hz 轮询。
            val text = when (val v = value) {
                null -> null
                is String -> v
                else -> v.toString()
            }
            hash = 31 * hash + key.hashCode()
            hash = 31 * hash + (text?.hashCode() ?: 0)
            hash = 31 * hash + (text?.length ?: 0)
        }
        extrasSigSource = state
        extrasSigValue = hash
        return hash
    }

    /** 当前会话是否属于 BYD 系（媒体中心 / BYD 自带播放器）。 */
    private fun isBydPackage(pkg: String?): Boolean {
        val name = pkg?.lowercase() ?: return false
        return name.contains("byd") || name.contains("mediacenter") || name.contains("dilink")
    }

    /** 标准 extras + BYD 外部歌词合并；外部歌词作为 `lyrics` 键参与 L0b。 */
    private fun buildPlaybackExtras(state: PlaybackState?, pkg: String?): Map<String, String> {
        val external = externalLyrics.takeIf { isBydPackage(pkg) }
        if (external.isNullOrBlank()) return extrasTextValues(state)
        val base = extrasTextValues(state)
        val merged = HashMap<String, String>(base.size + 1)
        merged.putAll(base)
        // 外部源更可信：整段歌词优先用 BYD 的。
        merged["lyrics"] = external
        return merged
    }

    /** 只把 4 个歌词键读成文本表；没有就返回空表，不分配 HashMap。 */
    private fun extrasTextValues(state: PlaybackState?): Map<String, String> {
        val extras = try {
            state?.extras
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        var out: HashMap<String, String>? = null
        for (key in EXTRA_LYRIC_KEYS) {
            val text = extras.getCharSequence(key)?.toString()?.trim()
            if (text.isNullOrEmpty()) continue
            if (out == null) out = HashMap(EXTRA_LYRIC_KEYS.size)
            out[key] = text
        }
        return out ?: emptyMap()
    }

    /**
     * 通知文本表的内容哈希。
     *
     * 同一个包的表只在通知真的变了时才会被替换成新实例，因此也可以按身份缓存 ——
     * 否则每次刷新都要遍历一遍 5 个 entry 算哈希。
     */
    private var textsHashSource: Map<String, String>? = null
    private var textsHashValue = 0

    private fun textsHash(texts: Map<String, String>): Int {
        if (texts === textsHashSource) return textsHashValue
        textsHashSource = texts
        textsHashValue = texts.hashCode()
        return textsHashValue
    }

    /** 换歌词列表的唯一入口：顺带把「是否全带时间轴」算一次，别再每次渲染全表扫。 */
    private fun setCurrentLines(lines: List<LyricLine>, anchorIndex: Int = -1) {
        currentLines = lines
        currentAnchorIndex = anchorIndex
        lastLineIndex = -1
        var timed = lines.isNotEmpty()
        for (line in lines) {
            if (line.timeMs == null) {
                timed = false
                break
            }
        }
        currentLinesTimed = timed
    }

    private fun render() {
        Perf.onRender()

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
                listOfNotNull(titleForOverlay()),
                null,
                prompt = true
            )
            return
        }

        val index = if (currentLinesTimed) {
            clock.locate(currentLines, clock.position())
        } else {
            currentAnchorIndex.takeIf { it >= 0 } ?: currentLines.lastIndex
        }
        val safe = index.coerceIn(0, currentLines.lastIndex)
        lastLineIndex = safe

        // 副行是一**组**相邻行：用户把行数调到 3、4 时要看到更多歌词，
        // 只给一条文本的话预算再多也只有两行可画（0.2.5 及以前的真实表现）。
        push(currentLines[safe].text, contextLines(safe), titleForOverlay(), prompt = false)
    }

    /**
     * 本地全 miss 且开关打开时，异步请求一次在线歌词。
     *
     * 同一首歌只请求一次（[onlineRequestedKey] 去重）。结果在主线程回调，
     * 回来后会重新核对「还是不是当前这首歌」——换歌后到达的旧结果直接丢弃。
     * 如果期间本地通道已经命中，也不覆盖：本地歌词一定比在线匹配更准。
     */
    private fun maybeRequestOnline(title: String?, artist: String?, durationMs: Long) {
        if (!prefs.onlineLyrics) return
        val cleanTitle = title?.trim().orEmpty()
        if (cleanTitle.isEmpty()) return
        val cleanArtist = artist?.trim().orEmpty()
        val key = "$cleanTitle\u0000$cleanArtist"
        if (key == onlineRequestedKey) return
        onlineRequestedKey = key
        if (Probe.enabled) Probe.write("歌词", "online request: $cleanTitle / $cleanArtist")

        OnlineLyricsFetcher.request(cleanTitle, cleanArtist, durationMs) { lines ->
            // 已在主线程回调
            if (!prefs.onlineLyrics) return@request
            if (lastTitle?.trim().orEmpty() != cleanTitle) return@request
            if (lastArtist?.trim().orEmpty() != cleanArtist) return@request
            if (hitSource != null) {
                if (Probe.enabled) {
                    Probe.write("歌词", "online result ignored (local hit=$hitSource)")
                }
                return@request
            }
            onlineLines = lines
            setCurrentLines(lines, -1)
            hitSource = ONLINE_SOURCE
            render()
            scheduleLineTick()
            if (Probe.enabled) Probe.write("歌词", "online hit lines=${lines.size}")
        }
    }

    /** 设置页切换在线补词时调用：关掉要立刻释放缓存与已显示的结果。 */
    fun onOnlineLyricsChanged() {
        if (!prefs.onlineLyrics) {
            onlineLines = emptyList()
            onlineRequestedKey = null
            OnlineLyricsFetcher.clear()
            hitSource = null
        }
        // 强制下一轮重跑责任链：否则关掉开关后，旧的在线歌词会一直留在屏上
        // （输入没变时责任链被指纹短路，currentLines 不会被重新计算）。
        keyValid = false
        requestRefresh("online_switch")
    }

    /**
     * 取当前行之后最多 [CONTEXT_MAX] 行的文本，按显示顺序；后面没有行时回退取前面的行。
     *
     * 「回退」是为了逐行流（L1/L2）：这种来源没有时间轴，「下一句」要么不存在
     * （L1 缓冲里只有历史），要么只在两行通知里凑出一句。当前行之后凑不出任何行时，
     * 用前面的行补位 —— 上下文行本来就是给「行数预算」填空的，方向不是用户可感知的承诺。
     *
     * 行数预算由渲染器按 `maxLines` 分配：这里只管「能拿到哪些行」，
     * 拿多了渲染器会丢掉，拿少了就会出现「4 行窗口只有 2 行字」。
     */
    private fun contextLines(index: Int): List<String> {
        if (index == ctxIndex && currentLines === ctxRef) return ctxResult
        val out = ArrayList<String>(CONTEXT_MAX)
        for (step in 1..CONTEXT_MAX) {
            out += currentLines.getOrNull(index + step)?.text ?: break
        }
        if (out.isEmpty()) {
            for (step in 1..CONTEXT_MAX) {
                val previous = currentLines.getOrNull(index - step) ?: break
                if (previous.text.isNotBlank()) out += previous.text
            }
        }
        ctxIndex = index
        ctxRef = currentLines
        ctxResult = out
        return out
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
            // 行没换就什么都不做：二分结果与上次相同，重绘只会白跑一次绘制。
            // 只重新定位并推文本，不碰责任链：链的输入没有变化，重跑只会得到同样的行。
            if (currentIndex() != lastLineIndex) render()
            scheduleLineTick()
        }
    }

    /** 当前应该显示的行号（与 [render] 的定位规则一致）。 */
    private fun currentIndex(): Int {
        if (currentLines.isEmpty()) return -1
        val index = if (currentLinesTimed) {
            clock.locate(currentLines, clock.position())
        } else {
            currentAnchorIndex.takeIf { it >= 0 } ?: currentLines.lastIndex
        }
        return index.coerceIn(0, currentLines.lastIndex)
    }

    private fun scheduleLineTick() {
        if (tickScheduled) return
        // 暂停时进度不动，行也不会换：定时器排上只会凭空唤醒。
        // 播放恢复时一定伴随一次状态回调，那时会重新排。
        if (lastPlayState != PlayState.PLAYING) return
        val delay = LyricTick.nextDelayMs(currentLines, clock.position(), currentLinesTimed)
        if (delay < 0) return
        tickScheduled = true
        mainHandler.postDelayed(lineTicker, delay)
    }

    private fun stopLineTick() {
        mainHandler.removeCallbacks(lineTicker)
        tickScheduled = false
    }

    private fun trackLabel(): String? = labelCache ?: listOfNotNull(lastTitle, lastArtist)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .ifBlank { null }
        .also { labelCache = it }

    /**
     * 悬浮窗标题行的内容。
     *
     * 伪歌名（PSEUDO）判定成立时，元数据 TITLE 本身就是播放器逐行推来的**歌词**，
     * 拿它当「歌名 · 歌手」显示，标题行就会跟着歌词一起滚 —— 真机 0.2.6 的反馈
     * 「歌曲名和歌手取错了，现在是歌词」就是这条路。此时标题直接不显示。
     */
    private fun titleForOverlay(): String? {
        // lastTitle 已经是「解析后」的展示值（可能是 BYD 外部源的真实歌名）。
        // 是否隐藏只看它本身是不是歌词行，不能再按 PSEUDO 一刀切 ——
        // 否则 BYD 兜底拿到的真实歌名会被一起盖掉。
        if (titleIsLyric()) return null
        return trackLabel()
    }

    /**
     * 标题文本是否与当前歌词行完全相同。
     *
     * TitleTimeline 的 PSEUDO 判定需要两条以上样本且窗口 15s —— 逐行推歌词的播放器
     * 在「换歌后的头几秒」（UNKNOWN）和「长间奏」（相邻间隔被拉大后可能翻回 STABLE）
     * 会漏网，标题就闪出一句歌词（0.2.7 真机仍见「歌曲名取成歌词」）。补一道强信号：
     * TITLE 与任何一条已知歌词行相同，无论判定是什么都不当标题用。真正的歌名恰好
     * 与某句歌词相同的情况存在，但远比「标题变歌词」少见，且误伤的后果只是少一个标题。
     */
    private fun titleIsLyric(): Boolean {
        val candidate = lastTitle?.trim() ?: return false
        if (candidate.isEmpty()) return false
        for (line in currentLines) {
            if (line.text.trim() == candidate) return true
        }
        return false
    }

    /**
     * 真正把文本推给悬浮层，并记录推了什么。
     *
     * `[渲染]` 这一行是为了回答「歌词已经取到了，为什么屏幕上没有」：它把
     * 交给 WindowManager 的原文与实际挂载状态一起写下来，取到行却没显示时
     * 一眼就能区分是「没渲染」还是「渲染了但窗口没挂上」。
     */
    private fun push(
        primary: String,
        secondary: List<String>,
        title: String?,
        prompt: Boolean
    ) {
        // 同一句反复推是最常见的情况（播放器状态回调比歌词变化频繁得多）：
        // 在这里挡掉，连悬浮层入口都不必进。
        // 只在「窗口还挂着」时跳过：窗口被收起／退出编辑／隐藏到期后 view 已经没了，
        // 这时候跳过推送就再没人去建窗口 —— 表现是「歌词永远不回来了」。
        if (overlay.hasWindow() &&
            primary == lastPushedPrimary && secondary == lastPushedSecondary &&
            title == lastPushedTitle && prompt == lastPushedPrompt
        ) return
        lastPushedPrimary = primary
        lastPushedSecondary = secondary
        lastPushedTitle = title
        lastPushedPrompt = prompt

        if (prompt) {
            overlay.showPrompt(primary, secondary)
        } else {
            overlay.showContent(primary, secondary, title)
        }
        if (!Probe.enabled) return
        if (secondary.hashCode() * 31 + primary.hashCode() + title.hashCode() ==
            lastRenderedSignature
        ) return
        lastRenderedSignature = secondary.hashCode() * 31 + primary.hashCode() + title.hashCode()
        val trace = "text=${primary.take(24)} context=${secondary.size} " +
            "title=${title?.take(12) ?: "none"} prompt=$prompt " +
            "attached=${overlay.isAttached()} measured=${overlay.isMeasured()}"
        Probe.lastRender = trace
        Probe.write("渲染", trace)
    }

    /**
     * 诊断快照。
     *
     * **只在调试开启时构建**：它要问系统两件事（通知使用权、悬浮窗权限）再查一次显示器，
     * 前两个都是跨进程调用。关着调试还每秒算一遍，纯属拿用户的车机做慈善。
     */
    private fun publishSnapshot(active: MediaController?, title: String?, duration: Long) {
        if (!Probe.enabled) return
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
            timedLyrics = currentLinesTimed,
            viewAttached = overlay.isAttached(),
            viewMeasured = overlay.isMeasured(),
            // 这条以前写死 false（等于报告里永远说「没按规则隐藏」）。按真实语义填：
            // 用户开了「无歌词时隐藏」且当前确实没有歌词行 → 窗口是被这条规则收掉的。
            hiddenByRule = prefs.hideWhenNoLyrics && currentLines.isEmpty(),
            a11yConnected = ForegroundApps.serviceConnected,
            hideByAppEnabled = prefs.hideByAppEnabled,
            hiddenAppCount = prefs.hiddenApps.size,
            foregroundPkg = ForegroundApps.currentPkg,
            foregroundHidden = prefs.hideByAppActive &&
                overlayHiddenFor(ForegroundApps.currentPkg, prefs.hiddenApps),
            overlaySuppressed = overlay.isSuppressed(),
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
        val pool = if (playing.isNotEmpty()) {
            playing
        } else {
            // 没有「正在播放」的会话时，优先有标题的那个：车载上常有空壳会话
            // （导航语音、残留会话）把真正暂停中的播放器挤掉。
            // 只在兜底分支读 metadata —— 正常播放时不为每个会话多付一次跨进程调用。
            val titled = sessions.filter {
                !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            }
            titled.ifEmpty { sessions }
        }
        return pool.maxByOrNull {
            it.playbackState?.lastPositionUpdateTime ?: 0L
        }
    }

    private fun bind(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, mainHandler)
        // 缓存属于上一个会话：不清掉的话，新会话的第一帧会拿旧播放器的事实去渲染 ——
        // 表现就是换播放器后先闪一下上一首的歌词。
        invalidateSessionCaches()
        if (next == null) return

        // 换会话＝换播放器，判定与行缓冲一律从零开始：
        // 否则两个元数据同样稀疏的应用会共用指纹，把上一个应用的歌词带过来。
        timeline.reset()
        titleStream.reset()
        lastVerdict = TitleVerdict.UNKNOWN
        lastTitle = null
        setCurrentLines(emptyList())
        hitSource = null
        keyValid = false
        lastRenderedSignature = Int.MIN_VALUE
        lastPushedPrimary = null
        lastPushedSecondary = emptyList()
        lastPushedTitle = null
        lastPushedPrompt = false
        ctxRef = null
        ctxIndex = -1
        ctxResult = emptyList()
        labelCache = null
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
        invalidateSessionCaches()
    }

    /**
     * 清掉「只对当前会话有效」的缓存：回调送来的元数据/状态、时钟锚点、换歌附加指纹。
     *
     * 换会话或断开时必须清 —— 这些值的生命周期就是「一个会话」。
     */
    private fun invalidateSessionCaches() {
        callbackMetadata = null
        callbackMetadataValid = false
        callbackState = null
        callbackStateValid = false
        clockRawPosition = Long.MIN_VALUE
        clockRawState = PlayState.NONE
        clockRawSpeed = Float.MIN_VALUE
        identityPkg = null
        // 指纹缓存也属于「上一个会话的事实」，不清会把旧元数据的哈希带过来
        metaSigSource = null
        textsHashSource = null
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

        /** 在线补词的来源标识（进探测报告与状态卡）。 */
        private const val ONLINE_SOURCE = "ONLINE"

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

        /** 通知文本表最多记几个包：设备上会发通知的应用可能几十个，不能无限长。 */
        private const val MAX_NOTIFICATION_PACKAGES = 16

        /** 开机后这段时间内连接成功，才补「待播」悬浮窗；再晚就不算「刚开机」。 */
        private const val BOOT_STANDBY_WINDOW_MS = 120_000L

        /** 待播提示延后一拍：让开机后的第一次刷新先跑，有真歌词就不盖提示。 */
        private const val BOOT_STANDBY_DELAY_MS = 800L

        /**
         * 一次给渲染器准备几条上下文行。
         *
         * 行数上限是 4（当前行至少占 1 行），所以 3 条足够填满；再多渲染器也会按预算丢掉，
         * 那只是白建 String。
         */
        private const val CONTEXT_MAX = 3

        /**
         * 关注的通知文本字段。前四项来自 extras，最后一项 tickerText 挂在 Notification 上，
         * 所以 [fieldBuffer] 的长度是 size + 1。
         */
        private val FIELD_KEYS = arrayOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            // 展开态正文：部分播放器的桌面歌词会写进这里，可能多行
            Notification.EXTRA_BIG_TEXT
        )

        /** tickerText 的键名（不是 extras 常量，自己定一个） */
        private const val KEY_TICKER = "android.tickerText"

        /**
         * `PlaybackState.extras` 里关注的歌词键。
         *
         * `lyrics` 是整段 LRC；`lrc_line_1~3` 是滚动窗口（第 1 行是当前句），
         * BYD 车机走的就是后者。两个都读，谁有内容用谁。
         */
        private val EXTRA_LYRIC_KEYS = arrayOf(
            "lyrics", "lrc_line_1", "lrc_line_2", "lrc_line_3"
        )

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
