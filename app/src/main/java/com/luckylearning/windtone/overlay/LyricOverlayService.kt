package com.luckylearning.windtone.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 桌面悬浮歌词服务（车机用）。
 *
 * ## 形态
 * 一行纯文字，无背景板、无按钮。文字带深色投影，亮色 / 暗色桌面都能看清。
 *
 * ## 两种模式
 *  - **锁定态（日常）**：`FLAG_NOT_TOUCHABLE`，手指划过去直接穿透到桌面，
 *    不会误挡导航操作。这是绝大多数时间的状态。
 *  - **调整态（拖动）**：从设置页点「调整位置」进入。此时摘掉 `NOT_TOUCHABLE`，
 *    窗口吃触摸，可拖动到任意位置；四周显示虚线边框提示「现在可以拖了」。
 *    点设置页的「完成」退出，位置落盘，窗口恢复穿透。
 *
 * ## 显示时机
 * **只在 App 退到后台时显示**。App 在前台时（用户正看着播放页歌词），
 * 桌面字幕纯属重复，直接隐藏。两个例外：设置页的「调整位置」模式，
 * 以及刚打开开关时的几秒前台预览（否则用户在设置页点完开关看不到任何变化）。
 *
 * ## 失败必须留痕
 * 挂窗失败 / 前台服务被系统拒绝都会被 catch 到。这类失败没有可自动恢复的手段，
 * 但**不能静默**：否则用户侧就是「开关开了、什么都没发生」，无从排查。
 * 失败时统一走 [CrashLog.record] + [LyricOverlayBus.report] 两条路：
 * 前者落到设置页 → 关于与诊断 → 崩溃日志，后者让设置页直接显示原因并回退开关。
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
class LyricOverlayService : Service() {

    private var windowManager: WindowManager? = null

    /** 外层容器：拖动时整体移动，虚线边框画在它身上 */
    private var container: FrameLayout? = null

    /** 真正显示文字的子 View */
    private var lyricView: TextView? = null

    private var layoutParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowJob: Job? = null

    /** 当前是否处于调整（拖动）模式；拖动逻辑里高频读，用普通字段 */
    private var editing = false

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换后屏幕宽度变了，已存的绝对坐标可能落到屏幕外，
        // 直接按新尺寸重新夹取一次最省事。
        if (container != null) applyPosition()
    }

    override fun onDestroy() {
        serviceAlive = false
        windowAttached = false
        windowJob?.cancel()
        scope.cancel()
        detachWindow()
        LyricOverlayBus.clear()
        LyricOverlayBridge.report(LyricOverlayBus.Status.IDLE, "服务已停止")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 窗口

    private fun attachWindow(): Boolean {
        val wm = windowManager ?: return false
        if (container != null) return true

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            // 投影是这里唯一的「可读性保险」：车机桌面可能是浅色也可能是深色，
            // 加一层黑色投影后白字在两种底上都能立起来，比铺半透明背景板干净得多。
            setShadowLayer(6f, 0f, 2f, Color.argb(200, 0, 0, 0))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, OVERLAY_TEXT_SP)
            visibility = View.INVISIBLE
        }

        val box = FrameLayout(this).apply {
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            visibility = View.INVISIBLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            layoutParams = params
            windowAttached = true
            tv.setOnTouchListener(dragListener)
            applyPosition()
            refreshModeChrome()
            LyricOverlayBridge.report(LyricOverlayBus.Status.RUNNING, "窗口已显示")
            true
        } catch (t: Throwable) {
            // 授权被撤销 / 厂商 ROM 拦白名单时会走到这里。
            // 这类失败没有可自动恢复的手段，直接停服，免得用户以为开着但什么都看不到。
            // 但必须**留下痕迹**：只进 logcat 的话用户在手机上无从排查，
            // 设置页 → 关于与诊断 → 崩溃日志里能看到这条诊断。
            windowAttached = false
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
        container = null
        lyricView = null
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
     * - 从未调整过（Prefs 里是 `Int.MIN_VALUE`）→ 默认「贴状态栏下沿、水平居中」；
     * - 调整过 → 用存的绝对坐标，并夹取到屏幕范围内（防止换分辨率后跑出屏幕）。
     */
    private fun applyPosition() {
        val p = layoutParams ?: return
        val prefs = Prefs(this)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val savedX = prefs.lyricOverlayX
        val savedY = prefs.lyricOverlayY

        if (savedX == Int.MIN_VALUE || savedY == Int.MIN_VALUE) {
            // 从未调整过：水平居中靠上。注意 gravity 用 CENTER_HORIZONTAL 才是真的居中——
            // 之前注释写着「居中」却仍是 START + x=0，实际贴在左上角。
            p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            p.x = 0
            p.y = dp(TOP_OFFSET_DP)
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = savedX.coerceIn(0, screenW)
            p.y = savedY.coerceIn(0, (screenH - dp(MIN_TOUCH_H_DP)).coerceAtLeast(0))
        }
        runCatching { windowManager?.updateViewLayout(container, p) }
    }

    /** 拖动基准：记录上一次手指的原始坐标，用「增量」而非「绝对定位」推动窗口 */
    private var lastRawX = 0f
    private var lastRawY = 0f

    /**
     * 拖动把手。
     *
     * 只有 [editing] 为真时窗口才会收到触摸事件——非编辑态窗口带着
     * `FLAG_NOT_TOUCHABLE`，系统根本不会把事件派发到这里。
     *
     * 位移按「手指位移量」累加，而不是「手指绝对位置 = 窗口位置」——
     * 后者会让窗口在按下瞬间跳到手指中心，手感很突兀。
     */
    @SuppressLint("ClickableViewAccessibility")
    private val dragListener = View.OnTouchListener { _, event ->
        val p = layoutParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 默认位置用 CENTER_HORIZONTAL 摆放，坐标是「相对屏幕中心的偏移」；
                // 拖动增量必须基于「绝对左上角」才有正确手感。按下瞬间归一化一次：
                // 左上角 x = 屏幕中心 + 当前偏移，之后固定用 TOP|START。
                if (p.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL) {
                    p.x += resources.displayMetrics.widthPixels / 2
                    p.gravity = Gravity.TOP or Gravity.START
                    runCatching { windowManager?.updateViewLayout(container, p) }
                }
                // 记下起点，MOVE 时算增量才有参照
                lastRawX = event.rawX
                lastRawY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                p.x += (event.rawX - lastRawX).roundToInt()
                p.y += (event.rawY - lastRawY).roundToInt()
                lastRawX = event.rawX
                lastRawY = event.rawY
                // 夹住，别拖出屏幕就再也抓不回来。按窗口自身宽高收紧边界，
                // 只用屏幕宽高会把窗口整个推到边界外（x=screenW 时窗口完全不可见）。
                val metrics = resources.displayMetrics
                val v = container
                val maxX = (metrics.widthPixels - (v?.width ?: 0)).coerceAtLeast(0)
                val maxY = (metrics.heightPixels - (v?.height ?: 0)).coerceAtLeast(0)
                p.x = p.x.coerceIn(0, maxX)
                p.y = p.y.coerceIn(0, maxY)
                runCatching { windowManager?.updateViewLayout(container, p) }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 落盘，下次启动沿用
                Prefs(this).apply {
                    lyricOverlayX = p.x
                    lyricOverlayY = p.y
                }
                true
            }

            else -> false
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun observeState() {
        windowJob?.cancel()
        windowJob = scope.launch {
            // 内容 + 编辑态 + 前台态 + 预览态四路合并：任一变化都重新决定「显不显示 / 长什么样」
            combine(
                LyricOverlayBus.content,
                LyricOverlayBus.editing,
                LyricOverlayBus.foreground,
                LyricOverlayBus.preview,
            ) { content, isEditing, isForeground, isPreview ->
                OverlayVisualState(content, isEditing, isForeground, isPreview)
            }
                .collect { (content, isEditing, isForeground, isPreview) ->
                    val box = container ?: return@collect
                    val tv = lyricView ?: return@collect

                    if (editing != isEditing) {
                        editing = isEditing
                        if (!isEditing) {
                            // 退出编辑态时把当前坐标固化，避免只有拖动过才记得住
                            layoutParams?.let { p ->
                                Prefs(this@LyricOverlayService).apply {
                                    lyricOverlayX = p.x
                                    lyricOverlayY = p.y
                                }
                            }
                        }
                        refreshModeChrome()
                    }

                    // 调整 / 预览两种模式下即使 App 在前台、即使没歌词，也要显示——
                    // 调整模式下用户此刻正站在设置页点「调整位置」；
                    // 预览模式是刚打开开关的那几秒，得让用户看见「它确实开了」。
                    // 这是「仅后台显示」规则的唯二例外。
                    val showOverride = isEditing || isPreview
                    val visible = (showOverride || !isForeground) && (content.visible || showOverride)
                    if (!visible) {
                        box.visibility = View.INVISIBLE
                        return@collect
                    }

                    val text = when {
                        content.visible -> content.text
                        isEditing -> "拖动到想要的位置"
                        isPreview -> "悬浮歌词已开启，退到桌面后持续显示"
                        else -> ""
                    }
                    if (tv.text.toString() != text) tv.text = text
                    tv.alpha = if (content.playing || showOverride) 1f else PAUSED_ALPHA
                    if (box.visibility != View.VISIBLE) box.visibility = View.VISIBLE
                }
        }
    }

    /**
     * 按当前模式刷新窗口的触摸能力与外观。
     *
     * 这是「锁定后点击穿透」的落点：编辑态摘掉 `FLAG_NOT_TOUCHABLE` 变成可拖，
     * 退出编辑态加回去，窗口重新变成完全穿透。
     */
    private fun refreshModeChrome() {
        val p = layoutParams ?: return
        val box = container ?: return
        val tv = lyricView ?: return

        p.flags = if (editing) {
            baseFlags()
        } else {
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager?.updateViewLayout(box, p) }

        // 编辑态给一层圆角描边 + 淡底，明确告诉用户「现在这块能拖」；
        // 锁定态把底板完全去掉，回到纯投影的字幕。
        // （GradientDrawable 不支持声明式虚线间隔，圆角实线框已足够表达「可拖」）
        box.background = if (editing) {
            GradientDrawable().apply {
                setColor(Color.argb(70, 0, 0, 0))
                setStroke(dp(2), Color.argb(220, 88, 166, 255))
                cornerRadius = dp(10).toFloat()
            }
        } else {
            null
        }
    }

    private fun canDraw(): Boolean = canDrawOverlay(this)

    // ------------------------------------------------------------------ 通知

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm?.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮歌词", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "在桌面最上层显示当前歌词行"
                    setShowBadge(false)
                },
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_music)
            .setContentTitle("悬浮歌词已开启")
            .setContentText("退到桌面后显示当前歌词行")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        private const val CHANNEL_ID = "lyric_overlay"
        private const val NOTIFICATION_ID = 0x7701

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

        /** 悬浮字幕字号：车机屏大、观看距离远，比手机正文大一档 */
        private const val OVERLAY_TEXT_SP = 20f

        /** 默认距屏幕顶部的距离（dp）；正好让开状态栏 */
        private const val TOP_OFFSET_DP = 48

        /** 暂停时的不透明度：不隐藏，方便看清唱到哪一句 */
        private const val PAUSED_ALPHA = 0.55f

        /** 纵向夹取时预留的高度，保证窗口不会整个跑出屏幕底 */
        private const val MIN_TOUCH_H_DP = 60

        /** 是否已获得「出现在其他应用上」授权 */
        fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

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

/** [LyricOverlayService.observeState] 合并四路状态后的快照（`kotlinx` 的 Quadruple 是 internal 的） */
private data class OverlayVisualState(
    val content: LyricOverlayBus.Content,
    val editing: Boolean,
    val foreground: Boolean,
    val preview: Boolean,
)
