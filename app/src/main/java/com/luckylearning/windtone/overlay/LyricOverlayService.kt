package com.luckylearning.windtone.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.os.Looper
import android.os.Handler
import android.net.Uri
import android.database.ContentObserver
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.diag.PerfMarks
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * 桌面悬浮歌词服务。
 *
 * ## 形态
 * 一至三行文字（外加可选的「双语副行」），配一层底板。
 *
 * 底板不是装饰，而是**可读性保险**。最初的设计是「纯白字 + 深色投影」，
 * 假设投影足以在亮色桌面上把字托起来。实测在亮色壁纸 / 桌面白色搜索框上，
 * 白字直接糊掉——投影半径按像素算，高密度屏上几乎看不见，
 * 于是用户侧的表现就是「服务在跑、窗口挂着、就是什么都看不到」。
 * 现在底板可配（无 / 半透明 / 实底），默认半透明。
 *
 * ## 两种模式
 *  - **锁定态（日常）**：`FLAG_NOT_TOUCHABLE`，手指划过去直接穿透到桌面，
 *    不会误挡导航操作。这是绝大多数时间的状态。
 *  - **调整态（拖动）**：从设置页点「调整位置」进入。此时摘掉 `NOT_TOUCHABLE`，
 *    窗口吃触摸，可拖动到任意位置；四周显示蓝色描边提示「现在可以拖了」。
 *    点设置页的「完成」退出，位置落盘，窗口恢复穿透。
 *    ⚠️ **超时会自动锁定**（[EDITING_TIMEOUT_MS]）：用户在桌面上拖动后忘了回去点
 *    「完成」的话，窗口会一直吃触摸、挡桌面操作。超时后由服务发一条
 *    [LyricOverlayBus.requestExitEditing] 让播放域把状态收回去。
 *
 * ## 显示时机
 * **只在 App 退到后台时显示**。App 在前台时（用户正看着播放页歌词），
 * 桌面字幕纯属重复，直接隐藏。三个例外：设置页的「调整位置」模式、
 * 刚打开开关时的几秒前台预览、以及改外观设置后的即时预览。
 * 灭屏时也直接隐藏（看不见还画是白耗电，见 [screenReceiver]）。
 *
 * ## 失败必须留痕
 * 挂窗失败 / 前台服务被系统拒绝都会被 catch 到。这类失败没有可自动恢复的手段，
 * 但**不能静默**：否则用户侧就是「开关开了、什么都没发生」，无从排查。
 * 失败时统一走 [CrashLog.record] + [LyricOverlayBus.report] 两条路：
 * 前者落到设置页 → 关于与诊断 → 崩溃日志，后者让设置页直接显示原因并回退开关。
 *
 * ## 内容由谁决定
 * 服务**不做任何歌词判断**——「该显示哪一句、有没有上下文、是不是间奏」
 * 全部由 [com.luckylearning.windtone.lyrics.OverlayContentResolver] 算好后经
 * [LyricOverlayBus] 送进来；这里只把 [OverlayTextComposer] 组装出的几行画出来。
 * 这样「显示什么」可单测，「怎么画」可调样式，两件事不相干。
 *
 * ## 为什么用 `WindowManager` 手搓 View 而不是 Compose
 * 悬浮窗不需要 Compose 运行时，一个 [TextView] 足够，省掉 `ComposeView` 的
 * 生命周期挂载与重组开销；车机 CPU 本就吃紧，越轻越好。
 *
 * ## 关于前台服务
 * Android 8.0+ 后台服务的存活时间受限，必须提为前台服务。
 * 目标车机（比亚迪宋 L DM-i 26 款，DiLink 100 / Android 12）用 `mediaPlayback`
 * 类型即可，不需要 Android 14 才引入的 `specialUse`。
 */
class LyricOverlayService : Service(), OverlayStateBinder.Host {

    private var windowManager: WindowManager? = null

    /** 外层容器：拖动时整体移动，虚线边框画在它身上 */
    override var container: FrameLayout? = null

    /** 真正显示歌词的子 View */
    override var lyricView: TextView? = null

    /** 顶部「歌名 / 歌手」两行小字（v1.1.15，可选显示） */
    private var titleView: TextView? = null

    override var layoutParams: WindowManager.LayoutParams? = null

    // ---------------------------------------------------------------- 收起钮（v1.1.91）
    //
    // 为什么是**独立窗口**而不是窗口里的一个按钮：非编辑态整块歌词窗是
    // FLAG_NOT_TOUCHABLE（否则会挡住下面的桌面应用），窗口内的按钮永远点不到。
    // 所以收起钮必须自己是一个可触摸的小悬浮窗，贴在屏幕左缘、与歌词窗垂直居中。
    private var collapseHandleView: android.view.View? = null
    private var collapseHandleParams: WindowManager.LayoutParams? = null

    // v1.1.95：第二颗钮 —— 打开播放页
    private var playHandleView: android.view.View? = null
    private var playHandleParams: WindowManager.LayoutParams? = null

    // v1.2.0：第三颗钮 —— 播放 / 暂停（图标自绘，不依赖系统字体里的符号）
    private var pauseHandleView: PlayPauseIconView? = null
    private var pauseHandleParams: WindowManager.LayoutParams? = null

    /** 无障碍状态侧钮（绿=已开 / 红=未开；点击跳系统无障碍页） */
    private var a11yHandleView: AccessibilityIconView? = null
    private var a11yHandleParams: WindowManager.LayoutParams? = null
    private var a11yWatchJob: Job? = null
    /** Settings.Secure 无障碍名单变更 → 立刻刷侧钮，轮询只做兜底 */
    private var a11ySettingsObserver: ContentObserver? = null

    /**
     * 播放/暂停钮的状态订阅（v1.2.40 起有名字）。
     *
     * 之前是个裸 `scope.launch`，没有 Job 引用 —— 而 `detachHandles()` 只做
     * `removeView` + 置 null，**不会**取消它。于是每次侧钮重建（车载模式进出、
     * 无障碍掉线重连、用户改侧钮开关或顺序都会改掩码）就多挂一条永不返回的
     * `content.collect`，订阅者只增不减。这条必须能被取消。
     */
    private var pauseHandleJob: Job? = null

    /**
     * 钮的纵向锚点（v1.1.95）。
     *
     * 之前每次重算都用「当前测量高度」居中，而歌词行数一变窗口高度就变，
     * 钮就跟着上下跳 —— 用户原话「位置不要变来变去」。
     * 现在只在**窗口顶边移动**或**首次放置**时重算，内容高度变化不再影响。
     */
    private var handleAnchorY = -1

    /**
     * 已挂出的是「哪几颗」钮（v1.3.0）。
     *
     * 三颗钮各有开关，配置一变就必须**整组重建** —— 单独增删会让锚点与序号错位，
     * 出现"点第二颗其实是第三颗"这种问题。用位掩码比较，最省事也最不容易错。
     */
    private var appliedHandleMask = -1
    private var appliedHandleOrder: String = ""
    private var anchorFromWindowY = Int.MIN_VALUE

    /** 歌词内容列（收起时整列隐藏，只留窗口宽度的一点点） */
    private var contentColumn: android.view.View? = null

    /** 是否处于「收起」态：窗口收成左侧一条，点钮还原 */
    private var collapsed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 状态接线（内容/编辑/前台/预览/外观 → 窗口外观与可见性），见 [OverlayStateBinder] */
    private val stateBinder = OverlayStateBinder(this, scope)

    /** 调整态的超时任务：长时间没人动，就请播放域把「调整位置」收回去 */
    private var editingTimeoutJob: Job? = null

    /** 当前是否处于调整（拖动）模式；拖动逻辑里高频读，用普通字段 */
    override var editing = false

    /** 屏幕是否亮着。灭屏时窗口整体隐藏——看不见还继续画纯属白耗电。 */
    override var screenInteractive = true

    /** 上一次算出来的「该不该显示」，亮屏 / 前台切换时据此立刻恢复，不必等下一句歌词 */
    override var desiredVisible = false

    /** 窗口首次测量完成后是否需要补一次位置夹取（挂载瞬间拿不到自身宽高） */
    private var pendingMeasureClamp = false

    /**
     * 已应用的外观档位。
     *
     * 服务不随设置页刷新（它是独立存活的），所以每帧读一次 Prefs 并和自己记的比，
     * 变了就地重绘。用 Int 比较，开销可以忽略。
     */
    /** 已应用的正文字号（sp，v1.1.51） */
    private var appliedTextSp = -1

    /** 已应用的歌词行数 / 标题占正文百分比（v1.1.15）；-1 = 还没应用过 */
    private var appliedLines = -1
    private var appliedTitlePercent = -1

    /** 已应用的背景不透明度 / 暂停表现 / 对齐 */
    private var appliedBgAlpha = -1
    private var appliedPauseMode = -1
    private var appliedAlign = -1

    /** 已应用的窗口宽度百分比（v1.1.4）；-1 = 还没应用过 */
    private var appliedWidthPercent = -1

    // 自定义尺寸 / 位置 / 背景（v1.1.48）：「已应用」快照，避免每帧 updateViewLayout
    private var appliedBoxHeightPercent = -1
    private var appliedFullHeightPercent = -1
    private var appliedFullYPercent = -1
    private var appliedXPct = -1
    private var appliedYPct = -1
    private var appliedBgKey = Int.MIN_VALUE

    /** 已应用的底板档位（决定文字描边强度） */
    private var appliedLegibility = Int.MIN_VALUE

    /** 已应用的文字描边（宽度×31+颜色，v1.1.52）；0 宽 = 自动描边 */
    private var appliedStrokeKey = Int.MIN_VALUE

    /** 已应用的单条行距 dp（v1.1.52）；-1 = 还没应用过 */
    private var appliedLineSpacingDp = -1

    /** 已应用的「全屏高度」开关（v1.1.19）；-1 = 还没应用过 */
    private var appliedFullHeight = -1

    /** 已应用的全屏宽度 / 水平位置（v1.1.20）；-1 = 还没应用过 */
    private var appliedFullWidthPercent = -1
    private var appliedFullOffsetPercent = -1

    /**
     * 上一次渲染的「角色 + 文字」签名。
     *
     * 为什么不能只比文字：全屏模式下同一句在「在唱 / 间奏」之间切换时文字不变、
     * 只有角色变（主角 vs 压暗），只比对 `tv.text` 的话这次重绘会被整个跳过，
     * 屏幕上就停在旧的强调状态。签名里带上角色即可。
     */
    private var lastRenderKey = ""

    /** 已应用的正文色（ARGB）；哨兵值 = 还没应用过 */
    private var appliedTextColor = COLOR_NOT_APPLIED

    /** 行切换淡入淡出是否正在进行——动画期间不许别处再改 alpha，否则两处打架 */
    private var fading = false

    /**
     * 灭屏广播：灭屏即隐藏，亮屏即恢复。
     *
     * `ACTION_SCREEN_OFF` 只能在代码里注册（清单里注册收不到），所以跟着服务的
     * 生命周期走。为什么值得做：车机导航时经常整段灭屏，而悬浮窗的可见性判断
     * 只看「App 在不在前台」——灭屏时播放还在继续，窗口会一直被绘制。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    applyVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    applyVisibility()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        startForeground(OverlayNotification.ID, OverlayNotification.build(this))
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        // 灭屏 / 亮屏：只在代码里注册才收得到（清单注册 Android 不会派发）
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        }
        LyricOverlayBridge.report(LyricOverlayBus.Status.RUNNING, "服务已启动")
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次被拉起都对齐一次开关状态：关闭了就地退出，打开着就补挂窗口。
        val enabled = OverlayPrefs.enabled(this)
        if (!enabled) {
            LyricOverlayBridge.report(LyricOverlayBus.Status.IDLE, "开关已关闭")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDraw()) {
            // 授权被系统撤掉：开关由播放域回置，这里只负责把原因说清楚
            LyricOverlayBridge.report(LyricOverlayBus.Status.FAILED, "未获得「出现在其他应用上」权限")
            stopSelf()
            return START_NOT_STICKY
        }
        if (container == null) attachWindow()
        if (intent?.action == ACTION_TOGGLE_COLLAPSE) {
            // v1.1.93：通知栏的「收起 / 展开」入口 —— 万一屏上那个小钮在车机上
            // 点不到（触摸被系统吞 / 被别的窗口盖住），用户仍有一条可靠的路。
            toggleCollapsed()
        }
        if (intent?.action == ACTION_RESET_POSITION) {
            // 自检里的「重置位置」：清掉存下来的坐标，回到默认位置
            pendingPositionSave = false
            applyPosition()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换后屏幕宽度变了，已存的绝对坐标可能落到屏幕外，
        // 直接按新尺寸重新夹取一次最省事。拖动中不打扰——那会把窗口弹回旧坐标。
        // 全屏模式没有坐标可夹，但**尺寸要跟着新屏幕重算**，否则竖屏时窗口还是横屏的高。
        if (container != null && Prefs(this).lyricOverlayFullHeight) {
            applyFullHeightChrome(true)
        } else if (container != null && !editing) {
            applyPosition()
        }
    }

    override fun onDestroy() {
        serviceAlive = false
        windowAttached = false
        windowVisible = false
        windowGeometry = "未挂载"
        stateBinder.dispose()
        editingTimeoutJob?.cancel()
        foregroundJob?.cancel()
        pauseHandleJob?.cancel()
        stopA11yWatch()
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        detachWindow()
        LyricOverlayBus.clear()
        ForegroundAppState.reset()
        LyricOverlayBridge.report(LyricOverlayBus.Status.IDLE, "服务已停止")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 窗口

    private fun attachWindow(): Boolean {
        val wm = windowManager ?: return false
        if (container != null) return true
        PerfMarks.overlayMountBegin()

        val tv = TextView(this).apply {
            // 正文色取自设置（默认纯白）。已唱部分的颜色**不在这里设**——它要按行内
            // 位置逐段染色，每次渲染都读最新值（见 OverlayTextStyler.build）。
            setTextColor(Prefs(this@LyricOverlayService).lyricOverlayTextColor)
            // 投影只是锦上添花的第二重保险；真正的可读性由 box 的深色底板兜底
            // （见 [refreshModeChrome]）。投影半径必须按 dp 换算——写死 6f 时，
            // 在 3x 屏上只有 2dp，亮色壁纸下等于没有。
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.argb(220, 0, 0, 0))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            includeFontPadding = false
            // 文字在框内的对齐：居中档整句居中（折行时每行也居中），居左档每行都从左起排。
            // 这里就按 Prefs 落定，不依赖「挂载后必有一次状态推送」——observeState 在
            // onCreate 里就起来了，而窗口是 onStartCommand 才挂的，首帧很可能读不到 container。
            // 之后的变化由 applyAppearance 接管。
            gravity = if (Prefs(this@LyricOverlayService).lyricOverlayAlign == OverlayPositioner.ALIGN_LEFT) {
                Gravity.START or Gravity.CENTER_VERTICAL
            } else {
                Gravity.CENTER
            }
            // 行数与省略策略由 observeState 按模式决定（手机 2 行 / 车载 6 行），
            // 这里只给多行留好行距（v1.1.52 起行距可在设置里调，0 = 紧凑）——
            // 单行时它不产生任何效果。
            setLineSpacing(
                dp(Prefs(this@LyricOverlayService).lyricOverlayLineSpacingDp).toFloat(),
                1f,
            )
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsDefaults.OVERLAY_TEXT_SP.toFloat())
            // ⚠️ 这里**不能**设 INVISIBLE：显隐只由 observeState 切外层 box，
            // 子 View 一旦被设成 INVISIBLE 就再也没人把它设回来——
            // 结果是底板（画在 box 上）照常出现、文字（画在 tv 上）永远消失，
            // 用户看到的就是「有窗口没字」。这个 bug 从第一版起就在。
        }

        // 顶部「歌名 / 歌手」两行小字（v1.1.15）：比歌词小一号、压暗，默认隐藏。
        // 放在独立的 TextView 里而不是拼进歌词串，是为了让两处字号 / 行距各自独立 ——
        // 标题调大不该把歌词顶出去，歌词换行数也不该影响标题。
        val title = TextView(this).apply {
            setTextColor(OverlayTextStyler.withAlpha(Prefs(this@LyricOverlayService).lyricOverlayTextColor, TITLE_ALPHA))
            setPadding(dp(18), dp(8), dp(18), 0)
            includeFontPadding = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                OverlayAppearance.titleTextSp(
                    Prefs(this@LyricOverlayService).lyricOverlayTextSp,
                    Prefs(this@LyricOverlayService).lyricOverlayTitlePercent,
                ),
            )
            gravity = OverlayAppearance.alignGravity(Prefs(this@LyricOverlayService).lyricOverlayAlign)
            visibility = View.GONE
        }

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(
                title,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                tv,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val box = FrameLayout(this).apply {
            addView(
                column,
                FrameLayout.LayoutParams(
                    // 宽度铺满窗口（窗口宽 = 屏宽 × 百分比），文字在框内怎么排由
                    // tv.gravity 决定；高度仍由内容决定。
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            // 只藏外层：父 View INVISIBLE 时子 View 一并不可见，
            // 挂载到第一次状态收集之间不会闪出空底板。
            visibility = View.INVISIBLE
        }

        // 尺寸统一由 OverlayFrame 算（v1.1.48）：宽度按屏宽百分比（v1.1.4 起），
        // 高度可给百分比（0 = 自动，仍交给内容撑开）。两套形态各用各的一组值。
        val initialPrefs = Prefs(this)
        val (initScreenW, initScreenH) = screenSize()
        val initialFrame = frameFor(initialPrefs, initScreenW, initScreenH)
        val params = WindowManager.LayoutParams(
            initialFrame.width,
            if (initialFrame.autoHeight) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                initialFrame.height
            },
            // TYPE_APPLICATION_OVERLAY 是 Android 8.0+ 唯一允许第三方应用使用的
            // 系统级悬浮窗类型，需要 SYSTEM_ALERT_WINDOW 授权。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        return try {
            wm.addView(box, params)
            container = box
            lyricView = tv
            titleView = title
            contentColumn = column
            attachCollapseHandle()
            layoutParams = params
            windowAttached = true
            pendingMeasureClamp = true
            tv.setOnTouchListener(dragListener)
            applyPosition()
            refreshModeChrome()
            // 窗口要量过一遍才知道自己多宽多高，而「夹取到屏幕内」依赖这个尺寸。
            // 挂载瞬间 width/height 还是 0，所以布局完成后再补夹一次。
            //
            // 注意这里**不能无条件调 applyPosition**：它读的是 Prefs 里的坐标，
            // 而拖动过程中坐标只在内存里，布局回调会把窗口一次次弹回旧位置，
            // 表现为「拖不动 / 拖了又跳回去」。所以只做一次性的「测量后补夹」。
            box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (pendingMeasureClamp && box.width > 0 && box.height > 0) {
                    pendingMeasureClamp = false
                    applyPosition()
                }
                updateGeometryReport()
            }
            box.post { updateGeometryReport() }
            LyricOverlayBridge.report(LyricOverlayBus.Status.RUNNING, "窗口已显示")
            PerfMarks.overlayMountEnd(ok = true)
            true
        } catch (t: Throwable) {
            // 授权被撤销 / 厂商 ROM 拦白名单时会走到这里。
            // 这类失败没有可自动恢复的手段，直接停服，免得用户以为开着但什么都看不到。
            // 但必须**留下痕迹**：只进 logcat 的话用户在手机上无从排查，
            // 设置页 → 关于与诊断 → 崩溃日志里能看到这条诊断。
            windowAttached = false
            PerfMarks.overlayMountEnd(ok = false)
            val detail = "挂载悬浮窗失败：${t.javaClass.simpleName}: ${t.message}"
            CrashLog.record(this, "LyricOverlay", detail + "\n\n" + t.stackTraceToString())
            LyricOverlayBridge.report(LyricOverlayBus.Status.FAILED, detail)
            // 同步把开关关掉，别让设置页显示「已开启」而桌面什么都没有
            Prefs(this).lyricOverlay = false
            stopSelf()
            false
        }
    }

    private fun detachWindow() {
        val wm = windowManager
        val v = container
        if (wm != null && v != null) runCatching { wm.removeView(v) }
        // v1.1.91：收起钮是独立窗口，必须一起摘掉，否则悬空留在桌面上
        collapseHandleView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        collapseHandleView = null
        collapseHandleParams = null
        playHandleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        playHandleView = null
        playHandleParams = null
        pauseHandleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        pauseHandleView = null
        pauseHandleParams = null
        handleAnchorY = -1
        anchorFromWindowY = Int.MIN_VALUE
        contentColumn = null
        collapsed = false

        container = null
        lyricView = null
        titleView = null
        layoutParams = null
    }

    /** 窗口基础 flags：不抢焦点、不拦输入法。是否可触摸由 [refreshModeChrome] 追加 */
    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    // ------------------------------------------------------------------ 定位

    /**
     * 把窗口摆到当前位置。
     *
     * **窗口位置完全由拖动决定**（2026-10-16 用户拍板，推翻「横向归对齐」）：
     * 横向存 `lyricOverlayX`、纵向存 `lyricOverlayY`，都没拖过时
     * 横向水平居中、纵向「状态栏下方 64dp」。设置里的「对齐」只管
     * 歌词文字在窗口内部怎么排，与窗口位置无关。
     *
     * 所有坐标都要夹取到屏幕内（防止换分辨率后跑出屏幕）。
     * 夹取必须用「屏幕尺寸 − 窗口自身尺寸」：只用屏幕尺寸的话，
     * `x = screenW` 会让窗口完全落在屏幕右侧之外，用户看到的就是一片空白
     * ——这正是「自检全绿却看不到任何东西」的头号嫌疑。
     *
     * 只有参数真的变了才 `updateViewLayout`：本方法会被布局回调反过来调用，
     * 无条件更新会构成「布局 → 更新 → 布局」的死循环。
     */
    override fun applyPosition() {
        // 收起态不接受几何重算：播放状态每帧都会算一次窗口外形，
        // 不挡住就会下一帧弹回展开尺寸（1.1.91 的教训）。
        if (collapsed) return
        val p = layoutParams ?: return
        val prefs = Prefs(this)
        val (screenW, screenH) = screenSize()
        val frame = frameFor(prefs, screenW, screenH)

        if (p.gravity == (Gravity.TOP or Gravity.START) && p.x == frame.x && p.y == frame.y) return
        p.gravity = Gravity.TOP or Gravity.START
        p.x = frame.x
        p.y = frame.y
        // 尺寸也顺手对齐：转屏 / 改宽度之后，位置夹取依赖窗口尺寸，
        // 只挪位置不改尺寸会算出一个「按旧尺寸夹过」的坐标
        if (p.width != frame.width) p.width = frame.width
        runCatching { windowManager?.updateViewLayout(container, p) }
        updateGeometryReport()
        // 歌词窗移了，收起钮要跟着保持「与窗垂直居中」
        updateHandlesPosition()
    }

    /**
     * 当前形态对应的窗口外形（尺寸 + 位置）。两种形态共用同一套算法（v1.1.48）。
     *
     * 全屏形态：底部让出车机 Dock（[SettingsDefaults.OVERLAY_DOCK_RESERVED_DP]）；
     * 单条形态：高度可自动（0 = 按行数撑开），不扣让位。
     * 位置取「可移动余量的百分比」，所以 100% 宽度时横向余量为 0 —— 与旧行为一致。
     */
    private fun frameFor(prefs: Prefs, screenW: Int, screenH: Int): OverlayFrame.Frame {
        val full = prefs.lyricOverlayFullHeight
        val measured = container?.height?.takeIf { it > 0 } ?: 0
        return OverlayFrame.frame(
            OverlayFrame.Params(
                screenW = screenW,
                screenH = screenH,
                widthPercent = if (full) prefs.lyricOverlayFullWidthPercent else prefs.lyricOverlayWidthPercent,
                heightPercent = if (full) {
                    prefs.lyricOverlayFullHeightPercent
                } else {
                    prefs.lyricOverlayBoxHeightPercent
                },
                xPercent = if (full) {
                    prefs.lyricOverlayFullOffsetPercent
                } else {
                    effectiveXPercent(prefs, screenW)
                },
                yPercent = if (full) prefs.lyricOverlayFullYPercent else effectiveYPercent(prefs, screenW, screenH),
                minWidthPx = dp(MIN_OVERLAY_WIDTH_DP),
                bottomReservedPx = if (full) dp(SettingsDefaults.OVERLAY_DOCK_RESERVED_DP) else 0,
                autoHeightPx = if (full) 0 else measured,
            ),
        )
    }

    /**
     * 单条形态的横向位置（百分比）：把旧版本存下来的**像素坐标**换算一次并落盘。
     *
     * 不换算的话，升级上来的用户会忽然回到默认位置 —— 而「位置被重置」是最让人
     * 怀疑「版本越改越差」的那类问题。只迁移一次（写完 pct 就 >= 0 了）。
     */
    private fun effectiveXPercent(prefs: Prefs, screenW: Int): Int {
        val stored = prefs.lyricOverlayXPercent
        if (stored >= 0) return stored
        val width = overlayWidthPx(prefs.lyricOverlayWidthPercent)
        val maxX = (screenW - width).coerceAtLeast(0)
        val legacy = prefs.lyricOverlayX
        val pct = if (legacy == OverlayPositioner.UNSET) {
            SettingsDefaults.OVERLAY_X_PERCENT
        } else {
            OverlayFrame.percentOf(legacy, maxX, SettingsDefaults.OVERLAY_X_PERCENT)
        }
        prefs.lyricOverlayXPercent = pct
        return pct
    }

    /** 单条形态的纵向位置（百分比）：同上，旧像素坐标换算一次 */
    private fun effectiveYPercent(prefs: Prefs, screenW: Int, screenH: Int): Int {
        val stored = prefs.lyricOverlayYPercent
        if (stored >= 0) return stored
        val height = container?.height?.takeIf { it > 0 } ?: 0
        val maxY = (screenH - height).coerceAtLeast(0)
        val legacy = prefs.lyricOverlayY
        val defaultY = statusBarHeight() + dp(TOP_OFFSET_DP)
        val pct = if (legacy == OverlayPositioner.UNSET || maxY <= 0) {
            // 还没拖过（或窗口还没测量）：按「状态栏下方一点」折算
            OverlayFrame.percentOf(defaultY, maxY, SettingsDefaults.OVERLAY_Y_PERCENT)
        } else {
            OverlayFrame.percentOf(legacy, maxY, SettingsDefaults.OVERLAY_Y_PERCENT)
        }
        prefs.lyricOverlayYPercent = pct
        return pct
    }

    /** 屏幕真实尺寸（像素）。优先用 WindowMetrics，避免 displayMetrics 在部分机型上偏小。 */
    private fun screenSize(): Pair<Int, Int> {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
            if (b != null && b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /**
     * 悬浮窗宽度（像素）：屏幕宽度的 [percent]%（1..100）。
     *
     * 低于 [MIN_OVERLAY_WIDTH_DP] 时按下限走 —— 1% 在 1080p 屏上只有 10px，
     * 窗口会小到既看不见也拖不着。
     */
    private fun overlayWidthPx(percent: Int): Int {
        val (screenW, _) = screenSize()
        return (screenW * percent.coerceIn(1, 100) / 100f).toInt().coerceAtLeast(dp(MIN_OVERLAY_WIDTH_DP))
    }

    /**
     * 全屏模式的外形（尺寸 + 位置），把 dp 让位换算集中在这一处。
     *
     * 三个调用点（安装时、改宽度 / 位置、转屏）必须用同一份算法，否则
     * 「设置里看着靠右、屏幕上却在中间」这种偏差极难解释。
     */
    private fun fullFrameFor(screenW: Int, screenH: Int, prefs: Prefs): OverlayPositioner.FullFrame =
        OverlayPositioner.fullFrame(
            screenW = screenW,
            screenH = screenH,
            bottomReserved = dp(SettingsDefaults.OVERLAY_DOCK_RESERVED_DP),
            widthPercent = prefs.lyricOverlayFullWidthPercent,
            offsetPercent = prefs.lyricOverlayFullOffsetPercent,
        )

    /** 状态栏高度（像素），拿不到时按 24dp 兜底 */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /**
     * 记录窗口的真实几何，供设置页「悬浮歌词自检」读取。
     *
     * 为什么自检需要它：`addView` 成功只说明**挂上了**，不代表**看得见**。
     * 窗口被推到屏幕外、尺寸量成 0、子 View 被藏起来，都会让用户看到
     * 「什么都没有」而各项检查全绿——所以外层窗口和文字 View **各自**都要报。
     */
    override fun updateGeometryReport() {
        val box = container
        val tv = lyricView
        if (box == null || tv == null) {
            windowGeometry = "未挂载"
            return
        }
        if (box.width <= 0 || box.height <= 0) {
            windowGeometry = "尚未完成测量"
            return
        }
        val loc = IntArray(2)
        runCatching { box.getLocationOnScreen(loc) }
        val (screenW, screenH) = screenSize()
        val onScreen = loc[0] + box.width > 0 && loc[0] < screenW &&
            loc[1] + box.height > 0 && loc[1] < screenH
        val textState = when {
            tv.visibility != View.VISIBLE -> "文字View被隐藏 ← 异常"
            tv.width <= 0 || tv.height <= 0 -> "文字View未测量 ← 异常"
            else -> "文字View ${tv.width}x${tv.height}"
        }
        windowGeometry = "x=${loc[0]} y=${loc[1]} 窗口 ${box.width}x${box.height}，" +
            "$textState / 屏幕 ${screenW}x${screenH}${if (onScreen) "" else " ← 已超出屏幕"}"
    }

    // ------------------------------------------------------------------ 拖动

    /** 拖动基准：记录上一次手指的原始坐标，用「增量」而非「绝对定位」推动窗口 */
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** 本次触摸里是否真的移动过窗口；只有真移动过才允许把坐标写进 Prefs */
    override var pendingPositionSave = false

    /** 上次抬手的时间 / 位置，用来在编辑态识别「双击锁定」 */
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /**
     * 拖动把手 —— 两种形态共用，但**只动各自允许的轴**：
     *
     * - 单条字幕（全屏高度关）：横纵两轴都跟手，位置存像素。窗口小而自由，
     *   用户拖到哪儿就是哪儿（2026-10-16 用户拍板）。
     * - 全屏歌词（全屏高度开，v1.1.20）：**只跟横向**。高度已经由「屏高 − Dock」
     *   决定，纵向拖会立刻露出底部 Dock 或把歌词顶出屏幕，没有第二种合理结果。
     *   位置在松手时反算成「余量百分比」落盘 —— 换分辨率后仍然保持相对位置。
     *
     * 位移按「手指位移量」累加，而不是「手指绝对位置 = 窗口位置」——
     * 后者会让窗口在按下瞬间跳到手指中心，手感很突兀。
     *
     * 只有真正拖动过才落盘（[pendingPositionSave]）。曾经的做法是
     * `ACTION_DOWN` 一律归一化坐标 + `ACTION_UP` 一律落盘，结果是
     * 「进调整模式随手点一下、什么都没拖」也会把坐标存下来，
     * 而那个坐标算错了半屏，窗口就此被推到屏幕右侧之外再也看不见。
     *
     * 编辑态**双击**即可退出并锁定：全屏窗口贴着整块屏，
     * 用户拖完往往直接回到桌面，此时还得回来点设置页的「完成」很别扭；
     * 双击是唯一在桌面上也一定点得到的收尾手势。
     */
    @SuppressLint("ClickableViewAccessibility")
    private val dragListener = View.OnTouchListener { _, event ->
        val p = layoutParams ?: return@OnTouchListener false
        val prefs = Prefs(this)
        val full = prefs.lyricOverlayFullHeight
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 记下起点，MOVE 时算增量才有参照
                lastRawX = event.rawX
                lastRawY = event.rawY
                pendingPositionSave = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).roundToInt()
                val dy = (event.rawY - lastRawY).roundToInt()
                p.x += dx
                p.y += dy
                lastRawX = event.rawX
                lastRawY = event.rawY
                // 夹住，别拖出屏幕就再也抓不回来。按窗口自身尺寸收紧边界，
                // 只用屏幕尺寸会把窗口整个推到边界外。
                val (screenW, screenH) = screenSize()
                val windowW = p.width.coerceAtLeast(container?.width ?: 0)
                val maxX = (screenW - windowW).coerceAtLeast(0)
                p.x = p.x.coerceIn(0, maxX)
                // v1.1.48：两种形态都可自由拖动（此前全屏只能左右挪），
                // 纵向位置由「可移动余量的百分比」记住，换分辨率也不跑偏
                val maxY = (screenH - (container?.height ?: 0)).coerceAtLeast(0)
                p.y = p.y.coerceIn(0, maxY)
                if (dx != 0 || dy != 0) pendingPositionSave = true
                runCatching { windowManager?.updateViewLayout(container, p) }
                updateGeometryReport()
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val upAt = android.os.SystemClock.uptimeMillis()
                val slop = android.view.ViewConfiguration.get(this).scaledDoubleTapSlop
                val isTap = !pendingPositionSave
                val isDoubleTap = isTap &&
                    upAt - lastTapUpAt <= android.view.ViewConfiguration.getDoubleTapTimeout() &&
                    kotlin.math.abs(event.rawX - lastTapX) <= slop &&
                    kotlin.math.abs(event.rawY - lastTapY) <= slop
                if (isDoubleTap) {
                    // 双击 = 收工：退出调整态，窗口恢复穿透
                    lastTapUpAt = 0L
                    savePositionAfterDrag(prefs, p, full)
                    LyricOverlayBus.requestExitEditing()
                    return@OnTouchListener true
                }
                if (isTap) {
                    lastTapUpAt = upAt
                    lastTapX = event.rawX
                    lastTapY = event.rawY
                }
                savePositionAfterDrag(prefs, p, full)
                true
            }

            else -> false
        }
    }

    /**
     * 把拖出来的位置落盘。
     *
     * 单条字幕存像素（自由拖动，存哪儿是哪儿）；全屏模式存「余量百分比」，
     * 因为它的可移动范围跟着宽度与屏宽变 —— 存像素的话换个分辨率 / 转个屏，
     * 上次的绝对坐标就会落到屏幕外。
     */
    private fun savePositionAfterDrag(
        prefs: Prefs,
        p: WindowManager.LayoutParams,
        full: Boolean,
    ) {
        if (!pendingPositionSave) return
        pendingPositionSave = false
        val (screenW, screenH) = screenSize()
        val h = if (p.height > 0) p.height else (container?.height ?: 0)
        val maxX = (screenW - p.width.coerceAtLeast(0)).coerceAtLeast(0)
        val maxY = (screenH - h).coerceAtLeast(0)
        if (full) {
            // 余量为 0（满宽 / 满高）时不动用户原来的偏好，免得随手拖一下把设置抹平
            if (maxX > 0) {
                prefs.lyricOverlayFullOffsetPercent =
                    OverlayFrame.percentOf(p.x, maxX, prefs.lyricOverlayFullOffsetPercent)
                appliedFullOffsetPercent = prefs.lyricOverlayFullOffsetPercent
            }
            prefs.lyricOverlayFullYPercent =
                OverlayFrame.percentOf(p.y, maxY, prefs.lyricOverlayFullYPercent)
            appliedFullYPercent = prefs.lyricOverlayFullYPercent
        } else {
            prefs.lyricOverlayXPercent = OverlayFrame.percentOf(p.x, maxX, prefs.lyricOverlayXPercent.coerceAtLeast(0))
            prefs.lyricOverlayYPercent = OverlayFrame.percentOf(p.y, maxY, prefs.lyricOverlayYPercent.coerceAtLeast(0))
            appliedXPct = prefs.lyricOverlayXPercent
            appliedYPct = prefs.lyricOverlayYPercent
            // 同时写一份旧像素坐标：万一回退到旧版本，位置不至于丢掉
            prefs.lyricOverlayX = p.x
            prefs.lyricOverlayY = p.y
        }
        // 通知播放域回读一次：设置页的位置滑块与这里是同一份数据
        LyricOverlayBridge.notifyPositionSaved()
    }

    // ------------------------------------------------------------------ 行数

    /**
     * 这次到底画几行（v1.1.49，v1.1.50 放开形态上限）。
     *
     * 三个来源，优先级从高到低：
     *  1. 全屏形态：一律按面板高度自适应 —— 面板高度本就是用户可调的（30..100%），
     *     写死行数在面板调矮 / 手机竖屏时会被窗口裁掉最后几行；
     *  2. 单条字幕 + 用户手选 1..5：照旧，尊重用户的明确选择；
     *  3. 单条字幕 + 「自动」：按固定高度反推（高度也是自动时按
     *     [OverlayLineFit.STRIP_FALLBACK_LINES] 行，否则会「行多变高、变高又多行」）。
     *
     * 自动档两种形态共用 [OverlayLineFit.MAX_AUTO_LINES] 这一个宽松上限：
     * 行数主要由高度决定（播放页效果 = 高度用满），上限只是安全网。
     */
    override fun resolveLineCount(prefs: Prefs, full: Boolean): Int {
        if (full) return autoLineCount(prefs, full = true)
        val configured = prefs.lyricOverlayLines
        if (configured > 0) return configured
        return autoLineCount(prefs, full = false)
    }

    /**
     * 按窗口高度反推行数。
     *
     * 固定开销（[chrome]）由三块组成：上下内边距、可选的「歌名 / 歌手」两行小字、
     * 以及一句译文 / 长句折行的余量 —— 少算最后一块时，双语歌词的当前句会把
     * 末尾一行顶出窗口，看起来就是「少显示了一句」。
     */
    private fun autoLineCount(prefs: Prefs, full: Boolean): Int {
        val (screenW, screenH) = screenSize()
        val frame = frameFor(prefs, screenW, screenH)
        // 高度自动时没有「可用高度」可依据，直接给兜底值（见 resolveLineCount 注释）
        if (frame.autoHeight) return OverlayLineFit.STRIP_FALLBACK_LINES

        val (extraDp, mult) = if (full) {
            OverlayAppearance.fullHeightLineSpacing()
        } else {
            // v1.1.52：单条形态的行距开放为设置项（0 = 紧凑）
            prefs.lyricOverlayLineSpacingDp to 1f
        }
        val lineBox = OverlayLineFit.lineBoxPx(
            textSizePx = spToPx(prefs.lyricOverlayTextSp.toFloat()),
            spacingExtraPx = dp(extraDp).toFloat(),
            spacingMult = mult,
        )
        val titlePx = if (prefs.lyricOverlayShowTitle) {
            val titleSize = spToPx(
                OverlayAppearance.titleTextSp(prefs.lyricOverlayTextSp, prefs.lyricOverlayTitlePercent),
            )
            (titleSize * OverlayLineFit.FONT_HEIGHT_FACTOR * TITLE_ROWS).roundToInt() + dp(8)
        } else {
            0
        }
        // 上下内边距（歌词 View 的 8dp×2）+ 标题 + 一句译文余量
        val chrome = dp(16) + titlePx + lineBox
        return OverlayLineFit.fit(
            availablePx = frame.height,
            lineBoxPx = lineBox,
            chromePx = chrome,
            // v1.1.50：不再按形态分 11/7 两档 —— 手机竖屏高度 94% 时 7 行只用了
            // 窗口的一小半，用户要的是播放页那种「高度用满」的效果。行数交给
            // 高度反推，这里只留一个宽松安全网。
            maxLines = OverlayLineFit.MAX_AUTO_LINES,
        )
    }

    /** sp → 像素（含系统字体缩放），与 TextView 的字号换算同口径 */
    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    // ------------------------------------------------------------------ 状态

    /** v1.1.29 前台检测轮询协程；「不需要知道前台是谁」时撤掉 */
    private var foregroundJob: Job? = null

    /**
     * 按「是否需要前台检测」启停 UsageStats 轮询（v1.1.29）。
     *
     * 两种需要：① 隐藏名单非空（要判「当前应用在不在名单里」）；
     * ② 用户开着「前台应用记录」。两者都不成立就撤协程、清残留包名 ——
     * **一个字节都不查**，这是零权限开销的底线。
     *
     * 没授 UsageStats 权限时不启动（无障碍服务若已被用户开启，由它自己喂
     * [ForegroundAppState]，这里不抢）；两个通道都没有时按「不隐藏」放行。
     *
     * 由收集循环每帧调用（设置变更会推高外观版本，必然触发），
     * 所以用户在设置里勾名单 / 开记录的**下一帧**就生效，不用重启服务。
     */
    override fun syncForegroundTracking(hiddenApps: Set<String>, logEnabled: Boolean) {
        if (!foregroundDetectionNeeded(hiddenApps, logEnabled)) {
            foregroundJob?.cancel()
            foregroundJob = null
            ForegroundAppState.reset()
            return
        }
        if (foregroundJob?.isActive == true) return
        // 缓存版：未授权时这个前置检查会每次都白跑一次 AppOps Binder（见方法注释）
        if (!ForegroundAppDetector.usageStatsGrantedCached(this)) return
        foregroundJob = scope.launch {
            ForegroundAppDetector.pollLoop(this@LyricOverlayService) { pkg ->
                if (Prefs(this@LyricOverlayService).foregroundLogEnabled) {
                    ForegroundAppLog.append(this@LyricOverlayService, pkg, ForegroundAppLog.labelOf(this@LyricOverlayService, pkg))
                }
            }
        }
    }

    /**
     * 订阅各路状态并逐帧重算窗口外观与可见性。
     * 实际接线已抽到 [OverlayStateBinder]，服务只负责窗口生命周期。
     */
    private fun observeState() = stateBinder.observe()

    /**
     * 应用外观配置里「和上一次不同才动手」的那几项。
     *
     * 每帧调用，所以每一步都要先和自己记的值比一次——不然每帧重建
     * `GradientDrawable` 会造成无谓的分配与重排。
     */
    override fun applyAppearance(prefs: Prefs) {
        val tv = lyricView ?: return

        // v1.2.0：侧钮随「车载模式 / 开关」变化实时增删 ——
        // 用户切到手机模式或关掉开关时不能留三颗钮飘在桌面上。
        syncHandlesVisibility()

        // 底板透明度变了就重算一次文字的「可读性包」：不透明度 0 = 无底板，文字直接
        // 压在任何画面上，必须有更硬的描边，否则浅色文字落在浅色地图 / 泊车画面上
        // 就是一片糊（v1.1.25）。v1.1.51 起背景只有「颜色 + 不透明度」一套语义。
        // v1.1.52：描边宽度 / 颜色开放为设置项（0 = 自动），一并纳入「变了才重算」。
        val backdropNow = OverlayBackground.legibilityKey(prefs.lyricOverlayBgAlphaPercent)
        val strokeKey = prefs.lyricOverlayStrokeWidthDp * 31 +
            (prefs.lyricOverlayStrokeColor and 0x00FFFFFF)
        if (appliedLegibility != backdropNow || appliedStrokeKey != strokeKey) {
            appliedLegibility = backdropNow
            appliedStrokeKey = strokeKey
            applyTextLegibility(tv, backdropNow, prefs)
        }

        // 全屏高度（v1.1.19）：窗口尺寸与位置改由屏幕决定，宽度百分比与行数两项
        // 在这个模式下不生效（值仍留在 Prefs 里，关掉全屏后照旧恢复）。
        val fullHeight = prefs.lyricOverlayFullHeight
        val fullFlag = if (fullHeight) 1 else 0
        if (appliedFullHeight != fullFlag) {
            appliedFullHeight = fullFlag
            applyFullHeightChrome(fullHeight)
        }

        // 尺寸（宽 + 高）与位置：两种形态各一套值，统一走 frameFor（v1.1.48）。
        // 尺寸一变必须重摆位置 —— 可移动余量是「屏幕 − 窗口」，窗口变大余量变小。
        // 拖动期间（editing）不按落盘值重摆：此刻位置只在内存里，
        // 一旦按旧 Prefs 重算，手指还按着窗口就跳回去了。
        if (fullHeight) {
            val changed = appliedFullWidthPercent != prefs.lyricOverlayFullWidthPercent ||
                appliedFullHeightPercent != prefs.lyricOverlayFullHeightPercent ||
                (!editing && appliedFullOffsetPercent != prefs.lyricOverlayFullOffsetPercent) ||
                (!editing && appliedFullYPercent != prefs.lyricOverlayFullYPercent)
            if (changed) applyFullHeightChrome(true)
        } else {
            val changed = appliedWidthPercent != prefs.lyricOverlayWidthPercent ||
                appliedBoxHeightPercent != prefs.lyricOverlayBoxHeightPercent ||
                (!editing && appliedXPct != prefs.lyricOverlayXPercent) ||
                (!editing && appliedYPct != prefs.lyricOverlayYPercent)
            if (changed) applyStripChrome()
        }

        // 背景（颜色 / 不透明度 / 自定义开关）：三项合成一个键，任一变化就重刷底板。
        // 不这样做的话，调色板上改颜色、改不透明度都要等下一次窗口重建才生效。
        val bgKey = backgroundKey(prefs)
        if (appliedBgKey != bgKey) {
            appliedBgKey = bgKey
            refreshModeChrome()
        }

        // 正文色落到 TextView 上就够了 —— 整窗只有这一种颜色，主角与上下句靠
        // 明暗 / 字重区分（v1.1.8 移除逐字染色后不再有「已唱色」）。
        val textColor = prefs.lyricOverlayTextColor
        if (appliedTextColor != textColor) {
            appliedTextColor = textColor
            tv.setTextColor(textColor)
            // 标题用同一个色相、压低透明度：换歌词颜色时两处一起走，不会出现
            // 「歌词变黄、标题还是白的」这种半截生效
            titleView?.setTextColor(OverlayTextStyler.withAlpha(textColor, TITLE_ALPHA))
        }

        val textSp = prefs.lyricOverlayTextSp
        // 标题字号 = 正文 × 百分比，所以正文一变标题也得跟着重设一遍
        val textSpChanged = appliedTextSp != textSp
        if (textSpChanged) {
            appliedTextSp = textSp
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp.toFloat())
            // 这里不再设 maxWidth：宽度已由窗口（屏宽 × 百分比）统一控制，
            // 两处都限宽只会让「调了没反应」变得难以解释。
        }

        // 歌名 / 歌手两行小字：字号 = 正文 × 百分比，正文一调它就跟着走（v1.1.51）
        val titlePercent = prefs.lyricOverlayTitlePercent
        if (appliedTitlePercent != titlePercent || textSpChanged) {
            appliedTitlePercent = titlePercent
            titleView?.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                OverlayAppearance.titleTextSp(textSp, titlePercent),
            )
        }

        // 行数变了窗口高度就变了，夹取上限（屏高 − 窗高）跟着变。
        // 必须等一帧、让布局先量出新高度，否则拿到的是旧高度。
        val lines = prefs.lyricOverlayLines
        if (appliedLines != lines) {
            appliedLines = lines
            // 全屏模式下行数按面板高度自适应，用户那一档不生效，自然也不需要重摆
            if (!fullHeight) container?.post { if (!editing) applyPosition() }
        }

        // v1.1.52：单条形态的行距开放为设置项。全屏形态的行距由
        // [OverlayAppearance.fullHeightLineSpacing] 固定给（播放页同款），不吃这个值。
        val lineSpacingDp = prefs.lyricOverlayLineSpacingDp
        if (appliedLineSpacingDp != lineSpacingDp) {
            appliedLineSpacingDp = lineSpacingDp
            if (!fullHeight) {
                tv.setLineSpacing(dp(lineSpacingDp).toFloat(), 1f)
                lastRenderKey = ""
                // 行距变了行高就变：固定高度 + 行数自动的窗口行数会跟着变，
                // 交一帧布局量完再重摆一次
                container?.post { if (!editing) applyPosition() }
            }
        }

        val bgAlpha = prefs.lyricOverlayBgAlphaPercent
        val pauseMode = prefs.lyricOverlayPauseMode
        if (appliedBgAlpha != bgAlpha || appliedPauseMode != pauseMode) {
            appliedBgAlpha = bgAlpha
            appliedPauseMode = pauseMode
            refreshModeChrome()
        }

        // 对齐方式现在**只管文字在窗口内部怎么排**（2026-10-16 用户拍板）；
        // 窗口在屏幕上的位置完全由拖动决定（applyPosition 读 savedX/savedY），
        // 所以这里只改 tv.gravity，绝不能顺手重摆窗口 —— 否则用户拖好的位置
        // 会在切换对齐档位时被悄悄拉回去。
        val align = prefs.lyricOverlayAlign
        if (appliedAlign != align) {
            appliedAlign = align
            tv.gravity = OverlayAppearance.alignGravity(align)
            // 标题跟着歌词一起对齐，否则「居左」时标题还悬在中间，看着像两个控件
            titleView?.gravity = OverlayAppearance.alignGravity(align)
        }
    }

    /**
     * 文字的可读性「包」（v1.1.25）。
     *
     * 为什么需要：选「无底板」时文字直接压在车机画面上 —— 泊车影像、浅色地图、
     * 白底导航路口图都会把浅色歌词吃掉，真机反馈就是「无底板时歌词模糊」。
     * 模糊的观感其实来自两件事叠加：
     *  1. 投影半径 3dp + 下移 1dp 是个「柔光」，浅色字 + 柔光在浅底上边界全糊；
     *  2. 上下句只有 42% 不透明度，压暗之后在浅底上直接看不见。
     *
     * 处理：无底板时改成**零位移 + 更紧的深色描边**（边界立刻变硬），
     * 有底板时保持原来的柔光（底板已经给了对比度，再加深会显脏）。
     */
    private fun applyTextLegibility(tv: TextView, backdrop: Int, prefs: Prefs) {
        // v1.1.52：用户手动设了描边宽度就完全交给它 —— 零位移的实色投影围一圈
        // 就是描边（TextView 没有原生描边，投影是唯一不引自定义 View 的画法）；
        // 0 = 自动：无底板换硬描边、有底板保持柔光。
        val manualStroke = prefs.lyricOverlayStrokeWidthDp
        if (manualStroke > 0) {
            tv.setShadowLayer(
                dp(manualStroke).toFloat(),
                0f,
                0f,
                prefs.lyricOverlayStrokeColor,
            )
        } else {
            val shadow = OverlayAppearance.shadowFor(backdrop)
            // 无底板时半径取 2dp：远小于字重，只加粗边缘而不会糊掉笔画之间的空隙
            tv.setShadowLayer(
                dp(shadow.radiusDp).toFloat(),
                0f,
                dp(shadow.offsetYDp).toFloat(),
                Color.argb(shadow.colorAlpha, 0, 0, 0),
            )
        }
        // 描边变了必须重画：span 里的颜色没变，但字形渲染变了
        lastRenderKey = ""
    }

    /**
     * 应用「全屏高度」模式的窗口外形（v1.1.19 / v1.1.20）。
     *
     * 全屏 = 宽度按设置（默认整屏）+ 高度 = 屏高 − 底部 56dp（让开车机 Dock）+
     * 纵向贴顶 + 横向按「余量百分比」取位；文字改走 11 行排版（行距放大），
     * 由 [OverlayTextStyler.build] 的 full 分支负责角色样式。
     *
     * 关掉时把外形完整还原：宽度回到百分比、高度回到 WRAP_CONTENT、行距复原，
     * 再重摆一次位置。**必须把 appliedWidthPercent 置回 -1** —— 否则宽度分支
     * 以为「这个百分比已经应用过」，还原后窗口会卡在全屏宽度上。
     *
     * 宽度 / 水平位置都在这里落定，设置页滑块改一下就能实时看到窗口变化。
     */
    private fun applyFullHeightChrome(full: Boolean) {
        if (collapsed) return
        val p = layoutParams ?: return
        val tv = lyricView ?: return
        val prefs = Prefs(this)
        val (screenW, screenH) = screenSize()
        val frame = frameFor(prefs, screenW, screenH)
        p.width = frame.width
        p.height = if (frame.autoHeight) WindowManager.LayoutParams.WRAP_CONTENT else frame.height
        // 拖动中只改尺寸、不抢位置：用户正按着窗口，重算 x/y 会让它跳一下
        if (!editing) {
            p.x = frame.x
            p.y = frame.y
        }
        p.gravity = Gravity.TOP or Gravity.START
        // 记下「已经用到哪一档」，否则 applyAppearance 每帧都会认为尺寸变了，
        // 于是每帧 updateViewLayout，屏幕上表现为歌词不停轻微抖动
        appliedFullWidthPercent = prefs.lyricOverlayFullWidthPercent
        appliedFullOffsetPercent = prefs.lyricOverlayFullOffsetPercent
        appliedFullHeightPercent = prefs.lyricOverlayFullHeightPercent
        appliedFullYPercent = prefs.lyricOverlayFullYPercent
        appliedWidthPercent = prefs.lyricOverlayWidthPercent
        appliedBoxHeightPercent = prefs.lyricOverlayBoxHeightPercent
        appliedXPct = prefs.lyricOverlayXPercent
        appliedYPct = prefs.lyricOverlayYPercent

        if (full) {
            val (lsExtra, lsMult) = OverlayAppearance.fullHeightLineSpacing()
            tv.setLineSpacing(dp(lsExtra).toFloat(), lsMult)
        } else {
            // v1.1.52：单条行距开放为设置项；全屏形态保持播放页同款固定行距
            tv.setLineSpacing(dp(prefs.lyricOverlayLineSpacingDp).toFloat(), 1f)
        }
        // 排版模式变了，旧的 span 签名作废，下一帧必须重画
        lastRenderKey = ""
        runCatching { windowManager?.updateViewLayout(container, p) }
        refreshModeChrome()
        container?.post {
            updateGeometryReport()
            updateHandlesPosition()
        }
    }

    /**
     * 单条形态的尺寸 / 位置应用（v1.1.48）。
     *
     * 与全屏那个方法分开，是因为两者「谁来决定高度」不同：单条可以是「自动高度」，
     * 交给 `WRAP_CONTENT`，此时 [OverlayFrame.Frame.height] 只是测量值，不能写进
     * layoutParams —— 写进去窗口就再也不会随行数变了。
     */
    private fun applyStripChrome() {
        if (collapsed) return
        val p = layoutParams ?: return
        val prefs = Prefs(this)
        val (screenW, screenH) = screenSize()
        // 迁移旧像素坐标（只发生一次）
        effectiveXPercent(prefs, screenW)
        effectiveYPercent(prefs, screenW, screenH)
        val frame = frameFor(prefs, screenW, screenH)
        p.width = frame.width
        p.height = if (frame.autoHeight) WindowManager.LayoutParams.WRAP_CONTENT else frame.height
        if (!editing) {
            p.x = frame.x
            p.y = frame.y
        }
        p.gravity = Gravity.TOP or Gravity.START
        appliedWidthPercent = prefs.lyricOverlayWidthPercent
        appliedBoxHeightPercent = prefs.lyricOverlayBoxHeightPercent
        appliedXPct = prefs.lyricOverlayXPercent
        appliedYPct = prefs.lyricOverlayYPercent
        runCatching { windowManager?.updateViewLayout(container, p) }
        // 尺寸变了，夹取上限跟着变：等一帧让布局量完再摆一次
        container?.post {
            applyPosition()
            updateHandlesPosition()
        }
    }

    /**
     * 顶部「歌名 / 歌手」两行小字的内容与显隐（v1.1.15）。
     *
     * 两个字段各自可能为空：歌名必有，歌手可能拿不到（`<unknown>` 已被上游清成空串）。
     * 只有一行时就显示一行，不硬凑。
     */
    override fun applyTitle(content: LyricOverlayBus.Content, prefs: Prefs): Boolean {
        val tv = titleView ?: return false
        val text = if (prefs.lyricOverlayShowTitle) {
            // v1.1.52：标题内容可选 —— 只歌名 / 只歌手 / 两者（默认）。
            // 各行仍可能为空：歌手拿不到时（`<unknown>` 已被上游清成空串）只显示剩下那行。
            val parts = when (prefs.lyricOverlayTitleMode) {
                1 -> listOf(content.title)
                2 -> listOf(content.artist)
                else -> listOf(content.title, content.artist)
            }
            parts.filter { it.isNotBlank() }.joinToString("\n")
        } else {
            ""
        }
        if (text.isEmpty()) {
            if (tv.visibility == View.GONE) return false
            tv.visibility = View.GONE
            return true
        }
        var changed = false
        if (tv.text.toString() != text) {
            tv.text = text
            changed = true
        }
        if (tv.visibility != View.VISIBLE) {
            tv.visibility = View.VISIBLE
            changed = true
        }
        return changed
    }

    /**
     * 把组装好的几行画到 [TextView] 上。
     *
     * 三种角色的样式只在这里定义：
     *  - 主角：最亮（默认白，即 [Prefs.lyricOverlayTextColor]）；
     *  - 配角（上下句、间奏时的主角）：压暗；
     *  - 双语副行：压暗 + 小一号。
     *
     * 行切换时做一次短促的淡入淡出——长句之间硬切会有「跳一下」的突兀感。
     * 动画期间不再手动改 alpha（[fading] 挡住），否则两处会互相打架。
     */
    override fun renderLines(
        tv: TextView,
        lines: List<OverlayTextComposer.StyledLine>,
        opaque: Boolean,
        fullHeight: Boolean,
    ) {
        val targetAlpha = OverlayAppearance.targetAlpha(opaque)
        // 样式需要的两个输入在服务侧现读：正文色用「已应用值」兜底用户最新设置，
        // 是否有底板决定配角/译文的压暗程度。
        val baseColor = if (appliedTextColor != COLOR_NOT_APPLIED) {
            appliedTextColor
        } else {
            Prefs(this).lyricOverlayTextColor
        }
        val renderPrefs = Prefs(this)
        val noBackdrop = OverlayBackground.legibilityKey(renderPrefs.lyricOverlayBgAlphaPercent) ==
            OverlayAppearance.BACKDROP_NONE
        val spanned = OverlayTextStyler.build(
            lines,
            fullHeight,
            baseColor,
            noBackdrop,
            currentColor = renderPrefs.lyricOverlayCurrentColor,
            dimPercent = renderPrefs.lyricOverlayDimPercent,
        )
        val sameText = tv.text.toString() == spanned.toString()
        // 「角色 + 文字 + 样式输入」一起做去重键：全屏模式下间奏 ⇄ 演唱是同一串文字、
        // 不同角色，只比文字的话这次重绘会被跳过，画面停在旧的强调状态。
        // v1.1.52：当前行颜色 / 压暗百分比也进了样式，一并入键 —— 否则改了设置
        // 文字没变时这次重绘会被整个跳过。
        val renderKey = buildString {
            append(if (fullHeight) "F|" else "S|")
            append(if (opaque) "1|" else "0|")
            append(renderPrefs.lyricOverlayCurrentColor).append('|')
            append(renderPrefs.lyricOverlayDimPercent).append('|')
            lines.forEach { append(it.role.name).append(':').append(it.text).append('\n') }
        }
        val sameRender = renderKey == lastRenderKey
        lastRenderKey = renderKey

        // v1.1.52：换行动画时长开放为设置项。淡出按 9/16 比例给（旧版 160/90 的关系），
        // 淡入即用户设的值；0 = 关动画，直接切。
        val fadeInMs = renderPrefs.lyricOverlayFadeMs.toLong()
        val fadeOutMs = fadeInMs * 9L / 16L

        if (!sameRender && !sameText) {
            tv.animate().cancel()
            if (fadeInMs <= 0L) {
                // 动画关掉：直接换字，别再走 animate 链
                tv.text = spanned
                tv.alpha = targetAlpha
            } else if (!fading && container?.visibility == View.VISIBLE) {
                fading = true
                tv.animate()
                    .alpha(0f)
                    .setDuration(fadeOutMs)
                    .withEndAction {
                        tv.text = spanned
                        tv.animate()
                            .alpha(targetAlpha)
                            .setDuration(fadeInMs)
                            .withEndAction { fading = false }
                            .start()
                    }
                    .start()
            } else {
                tv.text = spanned
                tv.alpha = targetAlpha
            }
        } else if (!sameText) {
            // 文字没变、只有角色或暂停态变了：直接换 span，不做淡入淡出
            // （同一句歌词在间奏 ⇄ 演唱之间来回时，闪烁比硬切更刺眼）
            tv.animate().cancel()
            tv.text = spanned
            tv.alpha = targetAlpha
        } else if (!fading && tv.alpha != targetAlpha) {
            // 暂停 / 恢复：只改透明度，不动文字
            tv.animate().cancel()
            tv.alpha = targetAlpha
        }
    }

    /**
     * 把「该不该显示」落到 View 上。
     *
     * 与前台 / 灭屏两件事统一在这里判定：窗口在灭屏时依旧会被系统合成，
     * 看不见还继续画是纯浪费——车机导航整段灭屏时这点电费是要省的。
     */
    override fun applyVisibility() {
        val box = container ?: return
        // 调用次数与真正改动次数分开记；v1.2.42 起：未改显隐时不再刷无障碍 /
        // 重算侧钮坐标（那两步各带跨进程 / updateViewLayout，歌词推进时无意义）。
        OverlayMetrics.onVisibilityApply()
        val show = desiredVisible && screenInteractive
        val target = if (show) View.VISIBLE else View.INVISIBLE
        var changed = false
        if (box.visibility != target) {
            box.visibility = target
            changed = true
        }
        // v1.1.91：收起钮跟着一起进出 —— 歌词都不显示了，单独飘一个钮只会让人困惑
        collapseHandleView?.let { if (it.visibility != target) { it.visibility = target; changed = true } }
        playHandleView?.let { if (it.visibility != target) { it.visibility = target; changed = true } }
        pauseHandleView?.let { if (it.visibility != target) { it.visibility = target; changed = true } }
        a11yHandleView?.let { if (it.visibility != target) { it.visibility = target; changed = true } }
        if (changed) OverlayMetrics.onVisibilityChanged()
        windowVisible = show
        // 仅在「本次真变成显示」时补刷：平时靠 ContentObserver + 慢轮询；
        // 侧钮坐标随 applyPosition() 走，不必每句歌词重算。
        if (changed && show) {
            refreshA11yHandleStatus()
            updateHandlesPosition()
        }
    }

    // ---------------------------------------------------------------- 收起钮（v1.1.91）

    /**
     * 挂上「收起 / 展开」小圆钮（独立可触摸窗口）。
     *
     * 位置：屏幕左缘、与歌词窗垂直居中。风格刻意与歌词区区分：实心橙色圆钮 +
     * 白色方向箭头，一眼能看出「这是个能点的控件」，不会被当成歌词的一部分。
     */
    /**
     * 当前配置下"应该显示哪几颗"侧钮（v1.3.0）。
     *
     * 位掩码：bit0 收起 / bit1 播放暂停 / bit2 打开播放页。
     * 只在**车载模式**且整体开关打开时才可能有钮；两者任一关掉就是 0。
     */
    private fun desiredHandleMask(): Int {
        val prefs = Prefs(this)
        return OverlayCollapseHelper.handleMask(
            carMode = prefs.carMode,
            handlesEnabled = prefs.overlayHandles,
            collapse = prefs.overlayHandleCollapse,
            playPause = prefs.overlayHandlePlayPause,
            player = prefs.overlayHandlePlayer,
            // 三档（2026-10-31）：判据是"无障碍真的可用"，不是"系统里勾着"
            a11y = OverlayCollapseHelper.a11yHandleVisible(
                mode = prefs.overlayHandleA11yMode,
                running = A11yRuntime.running(this),
            ),
        )
    }

    private fun attachCollapseHandle() {
        if (collapseHandleView != null) return
        if (desiredHandleMask() and 1 == 0) return
        // v1.1.93：40dp 在车机上偏小（手指在颠簸中容易点到边缘外的歌词窗上），
        // 放大到 48dp；点击本身先记账 —— 「点了没反应」要能区分
        // 「没收到点击」与「收到了但没生效」，这是两种完全不同的病。
        val size = dp(48)
        val handle = TextView(this).apply {
            text = "‹"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // v1.2.0：按用户给的截图统一风格 —— 深色半透明底 + 白色图标，
            // 不再用实心橙 / 蓝大圆块（在车机上"按钮特别大"的观感一半来自那个色块）
            background = handleDisc(HANDLE_DISC_ALPHA)
            contentDescription = "收起悬浮歌词"
            elevation = dp(4).toFloat()
            setOnClickListener {
                com.luckylearning.windtone.diag.DiagLog.log("OVERLAY", "收起钮被点击")
                toggleCollapsed()
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(160)
        }
        val ok = runCatching { windowManager?.addView(handle, lp) }.isSuccess
        if (!ok) {
            // 加不上就别留半套：歌词功能本身不受影响
            return
        }
        collapseHandleView = handle
        collapseHandleParams = lp
        attachPauseHandle()
        attachPlayHandle()
        container?.post { updateHandlesPosition() }
    }

    /**
     * 第二颗钮：打开播放页（v1.1.95）。
     *
     * 与收起钮并排放在歌词窗左侧：**蓝色圆 + 音符**，与橙色箭头一眼区分；
     * 点击走 [AppShortcut.OPEN_PLAYER] 这条既有深链（小组件也在用），
     * 不必新开一套 Intent 解析。
     */
    private fun attachPlayHandle() {
        if (playHandleView != null) return
        if (desiredHandleMask() and 4 == 0) return
        val size = dp(48)
        val play = TextView(this).apply {
            text = "♪"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = handleDisc(HANDLE_DISC_ALPHA)
            contentDescription = "打开播放页"
            elevation = dp(4).toFloat()
            setOnClickListener {
                com.luckylearning.windtone.diag.DiagLog.log("OVERLAY", "打开播放页按钮被点击")
                openPlayerPage()
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(216)
        }
        if (!runCatching { windowManager?.addView(play, lp) }.isSuccess) return
        playHandleView = play
        playHandleParams = lp
    }

    /**
     * 第三颗钮：播放 / 暂停（v1.2.0）。
     *
     * 排在三颗钮的中间 —— 收起（上）/ 播放暂停（中）/ 打开播放页（下）。
     * 图标用 [PlayPauseIconView] 自绘：车机字体常把 `⏸` 这类符号裁成豆腐块。
     */
    private fun attachPauseHandle() {
        if (pauseHandleView != null) return
        if (desiredHandleMask() and 2 == 0) return
        val size = dp(48)
        val view = PlayPauseIconView(this).apply {
            background = handleDisc(HANDLE_DISC_ALPHA)
            setPlaying(false)
            elevation = dp(4).toFloat()
            setOnClickListener {
                com.luckylearning.windtone.diag.DiagLog.log("OVERLAY", "播放/暂停按钮被点击")
                LyricOverlayBus.requestTogglePlay()
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(264)
        }
        if (!runCatching { windowManager?.addView(view, lp) }.isSuccess) return
        pauseHandleView = view
        pauseHandleParams = lp
        // 状态由播放域推送（content.playing），这里只负责换图标 ——
        // 服务不判断"该不该播"，避免与播放域各算一套。
        // 必须存成 Job：detachHandles() 要能把它取消掉，否则每次重建都多留一条
        // 永不返回的 collect（见 pauseHandleJob 的注释）。
        pauseHandleJob?.cancel()
        pauseHandleJob = scope.launch {
            LyricOverlayBus.content.collect { content ->
                pauseHandleView?.setPlaying(content.playing)
            }
        }
    }


    /**
     * 第四颗钮：无障碍状态（对齐播放页「前台识别」）。
     *
     * 绿 = 已开，红 = 未开；点击走 [ForegroundAppDetector.openAccessibilitySettings]，
     * **不能**在进程内假开关（系统无障碍必须由用户在系统页确认）。
     */
    private fun attachA11yHandle() {
        if (a11yHandleView != null) return
        if (desiredHandleMask() and 8 == 0) return
        val size = dp(48)
        val view = AccessibilityIconView(this).apply {
            background = handleDisc(HANDLE_DISC_ALPHA)
            // 绿色 = 无障碍**真的可用**（开关勾着 + 服务在案）。只看勾选会把
            // DiLink 掉线那种"看着绿的其实没工作"画成绿钮，误导比不显示更糟。
            setA11yEnabled(A11yRuntime.running(this@LyricOverlayService))
            elevation = dp(4).toFloat()
            setOnClickListener {
                com.luckylearning.windtone.diag.DiagLog.log("OVERLAY", "无障碍侧钮被点击")
                openAccessibilityFromHandle()
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(312)
        }
        if (!runCatching { windowManager?.addView(view, lp) }.isSuccess) return
        a11yHandleView = view
        a11yHandleParams = lp
    }

    /**
     * 侧钮状态轮询（2026-10-31 升级）。
     *
     * 两件事一起做：
     * 1. 颜色 —— 绿 = 真的可用，红 = 未开 / 掉了；
     * 2. 显隐 —— 「仅未运行时显示」这一档要靠它发现状态变了，然后整组重建。
     *
     * **不随无障碍钮一起生灭**：那颗钮被摘掉之后，轮询仍要跑，否则状态恢复时
     * 没人把它挂回来。只有「一颗钮都不该显示」时才停（见 [stopA11yWatch]）。
     */
    private fun ensureA11ySettingsObserver() {
        if (a11ySettingsObserver != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // 系统改了无障碍名单：立刻刷灯 + 可能要重建「仅未运行时显示」的侧钮
                refreshA11yHandleStatus()
                if (desiredHandleMask() != appliedHandleMask) syncHandlesVisibility()
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                /* notifyForDescendants = */ false,
                obs,
            )
            a11ySettingsObserver = obs
        }
    }

    private fun clearA11ySettingsObserver() {
        val obs = a11ySettingsObserver ?: return
        runCatching { contentResolver.unregisterContentObserver(obs) }
        a11ySettingsObserver = null
    }

    private fun startA11yWatch() {
        if (a11yWatchJob?.isActive == true) return
        ensureA11ySettingsObserver()
        a11yWatchJob = scope.launch {
            while (isActive) {
                // 计时回答：慢轮询 + Secure 跨进程到底贵不贵。进 OverlayMetrics。
                val started = System.nanoTime()
                refreshA11yHandleStatus()
                if (desiredHandleMask() != appliedHandleMask) syncHandlesVisibility()
                OverlayMetrics.onA11yTick(System.nanoTime() - started)
                // v1.2.40：悬浮窗藏着的时候，这颗状态钮也跟着一起藏 —— 此刻没人看得见它，
                // 状态就算变了等下次显示时由 applyVisibility() 立刻补刷一次即可。
                // 车机上悬浮窗被导航挡住是常态，这一段能省掉大部分跨进程调用。
                delay(if (windowVisible) A11Y_WATCH_INTERVAL_MS else A11Y_WATCH_INTERVAL_HIDDEN_MS)
            }
        }
    }

    /** 侧钮全关时停掉轮询，别让一个 2s 定时器白转 */
    private fun stopA11yWatch() {
        a11yWatchJob?.cancel()
        a11yWatchJob = null
        clearA11ySettingsObserver()
    }

    private fun refreshA11yHandleStatus() {
        val view = a11yHandleView ?: return
        view.setA11yEnabled(A11yRuntime.running(this))
    }

    private fun openAccessibilityFromHandle() {
        val ok = ForegroundAppDetector.openAccessibilitySettings(this)
        if (!ok) {
            val msg = if (ForegroundAppDetector.canOpenAccessibilityPage(this)) {
                getString(R.string.player_a11y_open_failed)
            } else {
                getString(R.string.player_a11y_no_page)
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        } else {
            // 用户可能马上开完返回：立刻刷一次，轮询会再确认
            refreshA11yHandleStatus()
        }
    }

    /** 按当前模式与开关，挂上或摘掉侧钮（v1.2.0） */
    private fun syncHandlesVisibility() {
        val mask = desiredHandleMask()
        val order = Prefs(this).overlayHandleOrder
        if (mask == appliedHandleMask && order == appliedHandleOrder) return
        // 走到这里才会真的摘了重挂（addView/removeView），是本服务里最贵的操作
        OverlayMetrics.onHandleRebuild()
        // 配置变了（整体关掉、或某颗单独关掉/打开、或上下顺序）：整组摘掉再按新配置挂，
        // 避免残留的钮与新序号错位。
        detachHandles()
        appliedHandleOrder = order
        if (mask == 0) {
            appliedHandleMask = 0
            stopA11yWatch()
            return
        }
        attachCollapseHandle()
        attachPauseHandle()
        attachPlayHandle()
        attachA11yHandle()
        appliedHandleMask = mask
        updateHandlesPosition()
        // 重建出来的 View 默认是 VISIBLE；灭屏 / 前台不该显示时要按全局开关压回去。
        applyVisibility()
        // 无障碍钮可能已经被摘掉，但"状态一变就要重新算掩码"的轮询得继续跑
        // （否则「仅未运行时显示」在修复无障碍后就再也不会自己回来）。
        startA11yWatch()
    }

    /** 摘掉三颗侧钮（不碰歌词窗本身） */
    private fun detachHandles() {
        collapseHandleView?.let { runCatching { windowManager?.removeView(it) } }
        collapseHandleView = null
        collapseHandleParams = null
        playHandleView?.let { runCatching { windowManager?.removeView(it) } }
        playHandleView = null
        playHandleParams = null
        pauseHandleView?.let { runCatching { windowManager?.removeView(it) } }
        pauseHandleView = null
        pauseHandleParams = null
        // 摘钮就要摘订阅：这条协程挂在 StateFlow 上永不返回，不取消就永远留着
        pauseHandleJob?.cancel()
        pauseHandleJob = null
        // ⚠️ 这里**不取消** a11yWatch：它已经从"这颗钮的附属品"升级成
        // "侧钮状态轮询"（2026-10-31），摘钮时取消会让「仅未运行时显示」
        // 再也等不到状态变回来的那一刻。暂停由 stopA11yWatch() 单独负责。
        a11yHandleView?.let { runCatching { windowManager?.removeView(it) } }
        a11yHandleView = null
        a11yHandleParams = null
        handleAnchorY = -1
        anchorFromWindowY = Int.MIN_VALUE
        // appliedHandleMask / Order 由 syncHandlesVisibility 在重建时重写
    }

    /** 钮的统一底片：深色半透明圆，图标一律白色（参考截图里的控制键风格） */
    private fun handleDisc(alpha: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor((alpha shl 24) or 0x000000)
            setStroke(dp(1), 0x33FFFFFF)
        }

    /** 从悬浮窗拉起播放页（深链与桌面小组件共用一条通道） */
    private fun openPlayerPage() {
        val intent = Intent(this, com.luckylearning.windtone.MainActivity::class.java).apply {
            action = com.luckylearning.windtone.ui.AppShortcut.ACTION
            putExtra(
                com.luckylearning.windtone.ui.AppShortcut.EXTRA,
                com.luckylearning.windtone.ui.AppShortcut.OPEN_PLAYER,
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val ok = runCatching { startActivity(intent) }.isSuccess
        com.luckylearning.windtone.diag.DiagLog.log(
            "OVERLAY",
            "打开播放页：${if (ok) "成功" else "失败（后台启动被限制）"}",
        )
    }

    /**
     * 两颗钮的位置（v1.1.95）。
     *
     * 三条规则，都是冲着用户那句「位置不要变来变去」：
     *  1. **横坐标与收起状态无关**：展开 / 收起用同一个 x，点一下不会横跳；
     *  2. **纵坐标只在窗口顶边移动时重算**：歌词行数变化会改窗口高度，此前按高度居中
     *     导致钮上下跳；现在高度不参与，只有用户拖动窗口才跟着走；
     *  3. 两颗钮**竖排**在歌词窗左侧（收起钮在上、播放页钮在下），整体夹在屏幕内。
     */
    private fun updateHandlesPosition() {
        val p = layoutParams ?: return
        val anyView = collapseHandleView ?: pauseHandleView ?: playHandleView ?: a11yHandleView ?: return
        val size = anyView.height.takeIf { it > 0 }
            ?: dp(OverlayCollapseHelper.DEFAULT_HANDLE_SIZE_DP)
        val gap = dp(OverlayCollapseHelper.HANDLE_GAP_DP)

        val boxH = container?.height?.takeIf { it > 0 }
            ?: p.height.takeIf { it > 0 }
            ?: dp(OverlayCollapseHelper.FALLBACK_BOX_HEIGHT_DP)
        val (screenW, screenH) = screenSize()

        val order = OverlayCollapseHelper.mergeHandleOrder(
            Prefs(this).overlayHandleOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
        val mask = desiredHandleMask()
        val kinds = OverlayCollapseHelper.visibleHandles(order, mask)
        if (kinds.isEmpty()) return

        val totalH = size * kinds.size + gap * (kinds.size - 1)
        val anchor = OverlayCollapseHelper.resolveHandleAnchor(
            windowY = p.y,
            boxHeightPx = boxH,
            totalHandlesHeightPx = totalH,
            screenH = screenH,
            currentAnchorY = handleAnchorY,
            currentAnchorFromWindowY = anchorFromWindowY,
        )
        handleAnchorY = anchor.anchorY
        anchorFromWindowY = anchor.anchorFromWindowY

        val column = OverlayCollapseHelper.handleColumn(
            windowX = p.x,
            handleSizePx = size,
            gapPx = gap,
            edgeGapPx = dp(OverlayCollapseHelper.HANDLE_EDGE_GAP_DP),
            screenW = screenW,
            anchorY = handleAnchorY,
            count = kinds.size,
        )
        kinds.forEachIndexed { index, kind ->
            val (view, params) = when (kind) {
                OverlayCollapseHelper.OverlayHandleKind.COLLAPSE ->
                    collapseHandleView to collapseHandleParams
                OverlayCollapseHelper.OverlayHandleKind.PLAY_PAUSE ->
                    pauseHandleView to pauseHandleParams
                OverlayCollapseHelper.OverlayHandleKind.PLAYER ->
                    playHandleView to playHandleParams
                OverlayCollapseHelper.OverlayHandleKind.A11Y ->
                    a11yHandleView to a11yHandleParams
            }
            if (view == null || params == null) return@forEachIndexed
            params.x = column.x
            params.y = column.ys[index]
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /**
     * 收起 / 展开。
     *
     * 收起 = 内容列隐藏 + 窗口宽度压到 [OverlayCollapseHelper.COLLAPSED_WIDTH_DP] + 贴左边缘，
     * 屏幕上只剩左侧一条窄窗与那个橙色钮；再点一次按 Prefs 里的位置与宽度还原。
     *
     * 收起期间 [applyPosition] / 两个 chrome 方法都会直接 return ——
     * 否则播放状态一变（每帧都会重算）就把窗口弹回展开尺寸，收起根本保持不住。
     */
    private fun toggleCollapsed() {
        val box = container ?: return
        val p = layoutParams ?: return
        collapsed = !collapsed
        val handle = collapseHandleView as? TextView

        if (collapsed) {
            // v1.1.96: collapse geometry via OverlayCollapseHelper (unit-tested).
            contentColumn?.visibility = View.GONE
            val geo = OverlayCollapseHelper.collapsedWindow(
                measuredHeightPx = box.height,
                layoutHeightPx = p.height,
                wrapContentSentinel = WindowManager.LayoutParams.WRAP_CONTENT,
                collapsedWidthPx = dp(OverlayCollapseHelper.COLLAPSED_WIDTH_DP),
                fallbackHeightPx = dp(OverlayCollapseHelper.FALLBACK_COLLAPSE_HEIGHT_DP),
            )
            p.height = geo.heightPx
            p.width = geo.widthPx
            p.x = geo.x
            handle?.text = "›"
            handle?.contentDescription = "展开悬浮歌词"
        } else {
            contentColumn?.visibility = View.VISIBLE
            val prefs = Prefs(this)
            val (sw, sh) = screenSize()
            val frame = frameFor(prefs, sw, sh)
            p.width = frame.width
            p.height = if (frame.autoHeight) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                frame.height
            }
            p.x = frame.x
            p.y = frame.y
            handle?.text = "‹"
            handle?.contentDescription = "收起悬浮歌词"
        }

        val applied = runCatching { windowManager?.updateViewLayout(box, p) }
        // 关键：改完窗口参数再显式请求一次 layout —— 自动高度那份布局缓存不会自己失效
        runCatching {
            box.requestLayout()
            box.invalidate()
        }
        if (!collapsed) refreshModeChrome()
        updateHandlesPosition()
        com.luckylearning.windtone.diag.DiagLog.log(
            "OVERLAY",
            "收起切换：collapsed=$collapsed 参数宽=${p.width} 实际宽=${box.width} " +
                "内容=${if (contentColumn?.visibility == View.GONE) "隐藏" else "显示"} " +
                "update=${if (applied.isSuccess) "ok" else "失败:" + applied.exceptionOrNull()?.javaClass?.simpleName}",
        )
    }

    /**
     * 给「调整位置」装上超时。
     *
     * 调整态窗口是**吃触摸**的：用户拖完忘了点「完成调整」就退到桌面的话，
     * 屏幕上会留一条挡操作的横条，而且桌面上的点击落在哪儿都说不清。
     * 服务只能判定「该收了」，状态归播放域所有，所以发一条请求让它来收。
     */
    override fun armEditingTimeout() {
        editingTimeoutJob?.cancel()
        editingTimeoutJob = scope.launch {
            delay(EDITING_TIMEOUT_MS)
            if (editing) LyricOverlayBus.requestExitEditing()
        }
    }

    /**
     * 按当前模式刷新窗口的触摸能力与外观。
     *
     * 这是「锁定后点击穿透」的落点：编辑态摘掉 `FLAG_NOT_TOUCHABLE` 变成可拖，
     * 退出编辑态加回去，窗口重新变成完全穿透。
     */
    override fun prefs(): Prefs = Prefs(this@LyricOverlayService)

    override fun cancelEditingTimeout() {
        editingTimeoutJob?.cancel()
    }

    override fun refreshModeChrome() {
        val p = layoutParams ?: return
        val box = container ?: return

        // 编辑态两种形态都吃触摸 —— 全屏歌词也要能左右拖（v1.1.20）。
        // ⚠️ 全屏（尤其宽度 100%）吃触摸时整块屏都点不动，所以退出编辑有三条路：
        // 设置页的「完成调整」、双击浮层、60 秒无操作自动锁定（[armEditingTimeout]）。
        // 少任何一条都会变成「拖完回不到桌面」。
        val fullHeight = Prefs(this).lyricOverlayFullHeight
        val editable = editing
        p.flags = if (editable) {
            baseFlags()
        } else {
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager?.updateViewLayout(box, p) }

        // 底板三档：0 无 / 1 半透明（默认）/ 2 实底。
        // 默认给底板而不是「纯白字 + 投影」：后者在亮色壁纸上被真机证实过看不清
        // （投影半径按像素算，高密度屏上几乎等于没有），这是踩过的坑，不是审美偏好。
        // 编辑态无论如何都有底 + 蓝描边：此刻用户需要看清「这块能拖」的边界。
        // （GradientDrawable 不支持声明式虚线间隔，圆角实线框已足够表达「可拖」）
        // 背景色（v1.1.51）：只有「颜色 + 不透明度」一套，0% 即无底板。
        // 取色逻辑在 OverlayBackground 里（纯函数，单测覆盖）。
        val chromePrefs = Prefs(this)
        val bgColor = OverlayBackground.resolve(
            chromePrefs.lyricOverlayBgColor,
            chromePrefs.lyricOverlayBgAlphaPercent,
        )
        // 全屏模式铺满整屏，圆角会在四角露出桌面，看着像渲染错误 —— 直角处理。
        // v1.1.52：单条形态的圆角开放为设置项（0 = 直角）。
        val corner = if (fullHeight) {
            0f
        } else {
            dp(chromePrefs.lyricOverlayCornerRadiusDp).toFloat()
        }
        // v1.1.52：底板边框开放为设置项（0 = 不画）。编辑态保留自己的蓝描边，
        // 不吃这个值 —— 那一圈线的语义是「这块能拖」。
        val borderWidthDp = chromePrefs.lyricOverlayBorderWidthDp
        val borderColor = chromePrefs.lyricOverlayBorderColor
        box.background = when {
            editable -> GradientDrawable().apply {
                setColor(EDIT_BACKDROP_COLOR)
                setStroke(dp(if (fullHeight) 3 else 2), EDIT_STROKE_COLOR)
                cornerRadius = corner
            }
            bgColor == null -> null
            else -> GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = corner
                if (borderWidthDp > 0) setStroke(dp(borderWidthDp), borderColor)
            }
        }
        appliedBgKey = backgroundKey(chromePrefs)
    }

    /**
     * 背景配置的「去重键」：颜色 + 不透明度（v1.1.51 起就这两项决定最终颜色）。
     *
     * v1.1.52：圆角 / 边框宽度 / 边框颜色也画在底板上，一并入键 ——
     * 不这样的话改圆角要等下一次背景变化才重刷。
     *
     * 用 Int 拼而不是字符串：每帧都要比一次，字符串拼接近乎白给 GC 找事。
     */
    private fun backgroundKey(prefs: Prefs): Int {
        var key = prefs.lyricOverlayBgAlphaPercent.coerceIn(0, 100)
        key = key * 31 + (prefs.lyricOverlayBgColor and 0x00FFFFFF)
        key = key * 31 + prefs.lyricOverlayCornerRadiusDp.coerceIn(0, 28)
        key = key * 31 + prefs.lyricOverlayBorderWidthDp.coerceIn(0, 4)
        key = key * 31 + (prefs.lyricOverlayBorderColor and 0x00FFFFFF)
        return key
    }

    private fun canDraw(): Boolean = canDrawOverlay(this)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        // collapse width: OverlayCollapseHelper.COLLAPSED_WIDTH_DP

        /**
         * 钮底片的黑色不透明度（v1.2.0：统一为深色半透明，图标白色）。
         *
         * 从"橙色 / 蓝色实心大圆"改过来：车机上那两块饱和色块很抢眼，
         * 用户明确要求照截图那种**轻描淡写**的控制键风格。
         * 0x59 ≈ 35%，压在亮画面（导航地图 / 视频）上也有足够对比。
         */
        private const val HANDLE_DISC_ALPHA = 0x59

        /** 通知栏「收起 / 展开」的 Intent action（v1.1.93） */
        const val ACTION_TOGGLE_COLLAPSE = "com.luckylearning.windtone.TOGGLE_OVERLAY_COLLAPSE"

        /** 复位悬浮窗位置的 Intent action（见 [resetPosition]） */
        private const val ACTION_RESET_POSITION = "com.luckylearning.windtone.RESET_OVERLAY_POSITION"

        /**
         * 服务是否活着。
         *
         * 给设置页「悬浮歌词自检」用：进程被 ROM 杀掉 / 服务自己退出的情况下，
         * 光看开关与授权是发现不了问题的——这正是"手机上没反应"最难查的一环。
         */
        @Volatile
        var serviceAlive: Boolean = false
            private set

        /** 悬浮窗是否已挂到 WindowManager（服务活着但窗口没挂上 = 挂窗被拒） */
        @Volatile
        var windowAttached: Boolean = false
            private set

        /**
         * 窗口的真实几何（`x=.. y=.. 尺寸 .. / 屏幕 ..`）。
         *
         * 供自检回答那个最要命的问题：**挂上了，可它到底在屏幕的哪个位置？**
         * `addView` 成功 ≠ 用户看得见——窗口被推到屏幕外时各项检查照样全绿。
         */
        @Volatile
        var windowGeometry: String = "未挂载"
            private set

        /** 窗口 View 当前是否处于 VISIBLE（前台时按设计为 false，不要据此判断故障） */
        @Volatile
        var windowVisible: Boolean = false
            private set

        /**
         * 「还没应用过」的哨兵，用于缓存上一次的正文色 / 已唱色。
         * 取 `Int.MIN_VALUE` —— 它是合法 `Int`，但不可能是一个正常的 ARGB 颜色。
         */
        private const val COLOR_NOT_APPLIED = Int.MIN_VALUE

        /**
         * 窗口宽度的下限（dp）。
         *
         * 用户可以把宽度调到 1%，那时按屏宽算出来只有几个像素，窗口会小到完全看不见、
         * 连「拖到哪儿了」都判断不了。留一个下限，让最小档也仍然是一块看得见的字幕。
         *
         * 取 **48dp**（标准最小触控边长），而不是 80dp：下限定得越高，滑块低端
         * 那一整段就越「拖了没反应」—— 80dp 在 1080p / 3x 的机器上已经是屏宽的 22%，
         * 意味着 1%~22% 全是同一个宽度。48dp 把这段死区压到 13% 以内，
         * 同时仍然保证「调整位置」时抓得住。
         */
        private const val MIN_OVERLAY_WIDTH_DP = 48

        /**
         * 默认位置：状态栏高度再往下让这么多 dp。
         *
         * 16dp 曾正好撞上荣耀/Honor 桌面顶部的「媒体胶囊」条（播放时才出现，
         * 而悬浮歌词恰恰只在播放时显示）——文字会整个叠在胶囊上看不清。
         * 64dp 实测能让开胶囊底沿；各机型可拖动微调，这里只管默认观感。
         */
        private const val TOP_OFFSET_DP = 64

        /** 锁定态底板：半透明（默认档）与实底 */
        private val TRANSLUCENT_BACKDROP_COLOR = Color.argb(140, 0, 0, 0)
        private val SOLID_BACKDROP_COLOR = Color.argb(228, 0, 0, 0)

        /** 编辑态底板与描边：此刻要明确告诉用户「这块能拖」 */
        private val EDIT_BACKDROP_COLOR = Color.argb(150, 0, 0, 0)
        private val EDIT_STROKE_COLOR = Color.argb(220, 88, 166, 255)



        /** 歌名 / 歌手小字的不透明度：压暗到 ~70%，层级自然落在歌词之下 */
        private const val TITLE_ALPHA = 0xB4

        /** 歌名 / 歌手最多两行（歌名 + 歌手），行数自适应时按它扣高度 */
        private const val TITLE_ROWS = 2

        // v1.1.52：单条行距与换行动画时长开放为设置项，
        // 旧常量 STRIP_LINE_SPACING_EXTRA_DP(2) / FADE_OUT_MS(90) / FADE_IN_MS(160) 删除，
        // 出厂默认在 SettingsDefaults（OVERLAY_LINE_SPACING_DP / OVERLAY_FADE_MS）。

        /**
         * 「调整位置」多久没人动就自动锁定。
         *
         * 60 秒：足够拖到任何位置并反复微调，又不至于让用户忘了点「完成」之后
         * 长时间有一条吃触摸的横条挡在桌面上。
         */
        private const val EDITING_TIMEOUT_MS = 60_000L

        /**
         * 侧钮状态轮询间隔（v1.2.40 由 2s→5s；v1.2.42 再放到 15s）。
         *
         * 无障碍名单变更优先走 ContentObserver；轮询只防厂商 ROM 漏回调。
         * 那颗钮只是指示灯，晚几秒变色无后果。隐藏时用更长间隔。
         */
        // v1.2.42：有 ContentObserver 兜底后，可见时 15s、隐藏时 60s 足够；
        // 状态变色晚几秒无听感影响，诊断包「无障碍轮询」次数应明显下降。
        private const val A11Y_WATCH_INTERVAL_MS = 15_000L

        /** 悬浮窗不可见时的轮询间隔：此时钮也不可见，状态变化等显示时补刷 */
        private const val A11Y_WATCH_INTERVAL_HIDDEN_MS = 60_000L

        /** 是否已获得「出现在其他应用上」授权 */
        fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * 清掉存下来的坐标并让服务回到默认位置（横向水平居中 + 状态栏下方）。
         *
         * 用于「窗口被推到屏幕外，用户根本抓不到」这种死局：
         * 桌面上摸不到窗口，设置页的「调整位置」也就无从下手。
         * 横纵两个坐标都清（2026-10-16 起两轴都是拖动坐标）。
         */
        fun resetPosition(context: Context) {
            Prefs(context).apply {
                lyricOverlayX = Int.MIN_VALUE
                lyricOverlayY = Int.MIN_VALUE
            }
            val intent = Intent(context, LyricOverlayService::class.java)
                .setAction(ACTION_RESET_POSITION)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /**
         * 按当前开关 + 授权状态同步服务的起停。
         *
         * 幂等：想开就 `startForegroundService`（已开也只是走一遍 onStartCommand），
         * 想关就 `stopService`。调用点覆盖 App 启动、开关切换、授权返回三处。
         */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, LyricOverlayService::class.java)
            if (!enabled || !canDrawOverlay(context)) {
                runCatching { context.stopService(intent) }
                LyricOverlayBus.setEditing(false)
                LyricOverlayBus.setPreview(false)
                LyricOverlayBus.clear()
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Android 12+ 禁止后台应用起前台服务；厂商 ROM 也可能直接拒绝。
                // 这种失败原先被 runCatching 吞掉，用户侧就是「开关开了但什么都没发生」。
                val detail = "启动悬浮窗服务失败：${t.javaClass.simpleName}: ${t.message}"
                CrashLog.record(context, "LyricOverlay", detail + "\n\n" + t.stackTraceToString())
                LyricOverlayBus.report(LyricOverlayBus.Status.FAILED, detail)
                runCatching { context.stopService(intent) }
                LyricOverlayBus.setPreview(false)
            }
        }
    }
}

/**
 * 悬浮歌词开关的读取入口。
 *
 * 单独抽出来是因为 [LyricOverlayService] 会脱离界面单独启动（例如系统重启后
 * 由 `START_STICKY` 拉起），此时不方便也不应该去 construct 一个完整的
 * `PlayerViewModel`，只需要直读一次 SharedPreferences。
 */
internal object OverlayPrefs {
    fun enabled(context: Context): Boolean = Prefs(context).lyricOverlay
}
