package com.anylyric.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.anylyric.R
import com.anylyric.accessibility.AccessibilityWatchdog
import com.anylyric.accessibility.ForegroundApps
import com.anylyric.data.ListenerState
import com.anylyric.data.PermissionGate
import com.anylyric.data.Permissions
import com.anylyric.data.Prefs
import com.anylyric.diag.Probe
import com.anylyric.overlay.Align
import com.anylyric.overlay.LineMode
import com.anylyric.overlay.OverlayDefaults
import com.anylyric.overlay.OverlayStyle
import com.anylyric.overlay.Preset
import com.anylyric.overlay.SizeBounds
import com.anylyric.overlay.SizeSlider
import com.anylyric.overlay.TitlePos
import com.anylyric.service.ServiceWatchdog
import com.anylyric.session.SessionHub
import com.anylyric.theme.ThemeMode
import com.anylyric.theme.ThemeSwitch
import com.anylyric.update.ApkInstaller
import com.anylyric.update.ApkProvider
import com.anylyric.update.CheckResult
import com.anylyric.update.DownloadRoute
import com.anylyric.update.FailureKind
import com.anylyric.update.Release
import com.anylyric.update.UpdateChecker
import com.anylyric.util.AppLabels
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 唯一主界面，按五个标签页组织：概览（状态与播放器频道）、外观（窗口/样式/配色）、
 * 歌词（标题与行为）、运行（后台保活/按应用隐藏）、更多（诊断与更新）。
 *
 * 三条设计约束（0.2.6 重排时定下，0.2.17 拆页后仍然有效）：
 *  · 分区卡片**自带标题**，不用浮在卡片外的分区标签 —— 双栏时标签会和内容错位；
 *  · 一屏只有一个主操作（进入编辑态），其余都是文字按钮或胶囊，视觉上不抢；
 *  · 每个可调项都能单独恢复默认，重置只在「已经改过」时出现，平时不占视线。
 *
 * 权限全部在这一屏内完成授权与检测 —— 不提示连接电脑、不要求 adb。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var hub: SessionHub
    private lateinit var prefs: Prefs

    // ---------------------------------------------------------------- 状态与权限
    private lateinit var statusBanner: TextView
    private lateinit var statusLine: TextView
    private lateinit var notificationState: TextView
    private lateinit var notificationButton: TextView
    private lateinit var notificationSettingsButton: TextView
    private lateinit var notificationDesc: TextView
    private lateinit var overlayState: TextView
    private lateinit var overlayButton: TextView
    private lateinit var overlayDesc: TextView
    private lateinit var editButton: TextView
    private lateinit var debugSwitch: Switch

    // ---------------------------------------------------------------- 更新
    private lateinit var updateState: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateAction: TextView

    /** 更新流程当前处在哪一步。界面与按钮文案都由它推导，避免多处各存一份状态。 */
    private enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED, DOWNLOAD_FAILED }

    private var updatePhase = UpdatePhase.IDLE
    private var updateMessage = ""

    /** 下载线程写、主线程读 */
    @Volatile
    private var updateProgress = -1
    private var updateRelease: Release? = null
    private var updateApk: File? = null

    /**
     * 各分区的「刷新自己的显示」回调。
     *
     * 设置页是纯代码布局，没有数据绑定；用一个刷新列表比给每个控件都存字段干净，
     * 也不会因为增删一项就漏掉同步。
     */
    private val uiRefreshers = mutableListOf<() -> Unit>()

    /** 应用图标缓存（32dp）。「按应用隐藏」列表与「播放器频道」对话框共用一份。 */
    private val appIconCache = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    /** 首次观察到「已授权未绑定」的时刻，用来判断自动恢复是否已经失败。 */
    private var firstUnboundAt = 0L

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollTicks = 0

    /**
     * 刚从哪一项权限的设置页回来。
     *
     * 只在真的发起过一次跳转后才有值。返回时若该项仍未授权，说明这条路在这台机器上
     * 走不通（DiLink5 把「通知使用权」拦成「多媒体不支持」），改为屏幕内给路径向导。
     */
    private var pendingPermission = PendingPermission.NONE
    private var pendingPermissionAt = 0L

    private val permissionProbe = Runnable { checkPendingPermission(fromResume = false) }

    private val poller = object : Runnable {
        override fun run() {
            refresh()
            if (hub.listenerState() == ListenerState.GRANTED) return
            if (pollTicks >= MAX_POLL_TICKS) return
            pollTicks++
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = SessionHub.get(this)
        prefs = hub.prefs()
        setContentView(buildContent(savedInstanceState))
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置开了无障碍回来：缓存必须失效，否则要等一会儿才变色。
        AccessibilityWatchdog.invalidate()
        // 无障碍被系统关掉时（HONOR/MagicOS 更新应用后的常见行为），有写权限就顺手恢复。
        // 没有权限时内部直接返回 false —— 不弹窗、不引导，绝不假装能做到。
        if (AccessibilityWatchdog.tryAutoRestore(this)) {
            AccessibilityWatchdog.invalidate()
            toast(getString(R.string.a11y_auto_restored))
        }
        refresh()
        if (hub.listenerState() != ListenerState.GRANTED && hub.tryRebind()) {
            startPolling(POLL_FIRST_DELAY_MS)
        }
        // 从设置页回来：授权成功了就当没事，没成功就得说清「为什么点了没反应」。
        checkPendingPermission(fromResume = true)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(poller)
        pollHandler.removeCallbacks(permissionProbe)
    }

    override fun onStop() {
        super.onStop()
        // 离开设置页就收起调参预览：回到播放器 / 导航后，窗口该由「按应用隐藏」正常决定。
        hub.setPreviewVisible(false)
        // 编辑态是唯一吃触摸的状态。用户从设置页离开（回桌面、切导航）时若还停在编辑态，
        // 那段窗口会继续拦截落在歌词框里的点击，现场看起来就是「悬浮窗挡着点不动」。
        // 旋转重建属于同一次操作，不该打断正在调的尺寸，这里跳过。
        if (!isChangingConfigurations && hub.overlay().isEditable()) {
            hub.overlay().setEditable(false)
        }
    }

    private fun startPolling(firstDelayMs: Long) {
        pollTicks = 0
        pollHandler.removeCallbacks(poller)
        pollHandler.postDelayed(poller, firstDelayMs)
    }

    // ---------------------------------------------------------------- 页面骨架

    /** 「刚跳去过哪一项权限的设置页」。只用于返回后的向导判定，不参与任何业务逻辑。 */
    private enum class PendingPermission { NONE, LISTENER, OVERLAY }

    /**
     * 五个标签页。
     *
     * 0.2.6 起这个页面是一屏到底，到 0.2.17 已经堆了 11 张卡 —— 车机上要翻很久才能
     * 找到想改的那一项（用户原话「放一页上要翻滚好久」）。按「现在怎么样 / 长什么样 /
     * 显示什么 / 怎么活下来 / 出问题看哪」拆成五页，每页最多三张卡，横屏双栏一屏放下。
     */
    private enum class SettingsTab(val labelRes: Int) {
        OVERVIEW(R.string.tab_overview),
        LOOK(R.string.tab_look),
        LYRICS(R.string.tab_lyrics),
        RUNTIME(R.string.tab_runtime),
        MORE(R.string.tab_more)
    }

    private var currentTab = SettingsTab.OVERVIEW

    /**
     * 每个标签页的 ScrollView。
     *
     * 页面**只建一次**，切页只改可见性：滚动位置、开关状态、输入框内容全都天然保留，
     * 也就不会出现「切回来发现某个开关被重置」这种鬼故事。
     */
    private val tabPages = LinkedHashMap<SettingsTab, ScrollView>()

    /** 每页上次的滚动位置（像素）。切走前存，切回来后恢复。 */
    private val tabScroll = HashMap<SettingsTab, Int>()

    private val tabLabels = LinkedHashMap<SettingsTab, TextView>()
    private val tabUnderlines = LinkedHashMap<SettingsTab, View>()

    private fun buildContent(savedInstanceState: Bundle?): View {
        uiRefreshers.clear()

        // 先把每张卡片建出来（同一段 View 只能有一个父容器），再决定放进哪一页。
        val status = buildStatusCard()
        val channel = buildChannelCard()
        val window = buildWindowCard()
        val look = buildLookCard()
        val colors = buildColorCard()
        val title = buildTitleCard()
        val behavior = buildBehaviorCard()
        val keepAlive = buildKeepAliveCard()
        val hideApps = buildHideAppsCard()
        val diag = buildDiagCard()
        val update = buildUpdateCard()
        val theme = buildThemeCard()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 页头与标签条常驻：翻内容时不用先滚回顶部才知道自己在哪一页。
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), 0)
        }
        top.addView(header())
        top.addView(tabBar())
        root.addView(top)

        // 左右滑动切页：横屏车机上比去点顶部标签条省事（手指不用抬到屏幕上方）。
        // 用自绘容器而不是 ViewPager2 —— 后者要配 Adapter 重建页面，与本页
        // 「页面只建一次」的约定冲突，滚动位置和控件状态全得自己搬。
        val content = SwipePager(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        // 横屏双栏的分法按页定：左栏是「摆哪儿、长什么样」，右栏是「读什么、怎么更新」。
        addPage(content, page(SettingsTab.OVERVIEW, listOf(status), listOf(channel)))
        addPage(content, page(SettingsTab.LOOK, listOf(window, look), listOf(theme, colors)))
        addPage(content, page(SettingsTab.LYRICS, listOf(title), listOf(behavior)))
        addPage(content, page(SettingsTab.RUNTIME, listOf(keepAlive), listOf(hideApps)))
        addPage(content, page(SettingsTab.MORE, listOf(diag), listOf(update)))
        root.addView(content)

        restoreTab(saved = savedInstanceState)
        return root
    }

    private fun addPage(container: FrameLayout, page: ScrollView) {
        tabPages[page.tag as SettingsTab] = page
        container.addView(page)
    }

    /**
     * 内容容器：左右滑动切页。
     *
     * 三条约束，都是真机上踩出来的：
     *  · **只在横向意图明确时才抢触摸**（横向位移 > 1.5 倍 slop 且明显大于纵向），
     *    否则页内纵向滚动会变得发黏；
     *  · 落点在胶囊条（HorizontalScrollView）、滑杆、输入框上时**完全不抢** ——
     *    这些控件自己就要吃横向手势，抢过来等于把它们废掉；
     *  · 跟手位移只走 35%：跟满会把下一层的空白背景扯出来，像界面被撕开。
     */
    private inner class SwipePager(context: Context) : FrameLayout(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val tracker = VelocityTracker.obtain()

        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var blocked = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                    blocked = hitsHorizontalControl(this, ev.x, ev.y)
                    tracker.clear()
                }

                MotionEvent.ACTION_MOVE -> if (!blocked && !dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.3f) {
                        dragging = true
                        tracker.clear()
                        tracker.addMovement(ev)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    tracker.clear()
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            tracker.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    tabPages[currentTab]?.translationX = (ev.x - downX) * FOLLOW_RATIO
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker.computeCurrentVelocity(1000)
                    val step = SwipeNav.decide(ev.x - downX, width, tracker.xVelocity)
                    tracker.clear()
                    // 先让当前页弹回原位再切：不弹回的话它会带着位移被 GONE，
                    // 下次切回来还在歪着。
                    tabPages[currentTab]?.animate()?.translationX(0f)?.setDuration(140)?.start()
                    dragging = false
                    if (step != 0) stepTab(step)
                }
            }
            return true
        }
    }

    /** 从触点往下找：有没有落在一个「自己会吃横向手势」的控件上。 */
    private fun hitsHorizontalControl(group: ViewGroup, px: Float, py: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val x = px + group.scrollX - child.left
            val y = py + group.scrollY - child.top
            if (x < 0f || y < 0f || x >= child.width || y >= child.height) continue
            if (child is HorizontalScrollView || child is SeekBar || child is EditText) return true
            if (child is ViewGroup && hitsHorizontalControl(child, x, y)) return true
        }
        return false
    }

    /** 滑到上一页 / 下一页；到头就什么也不做（不做回弹，避免误以为翻过去了）。 */
    private fun stepTab(step: Int) {
        val target = SettingsTab.values().getOrNull(currentTab.ordinal + step) ?: return
        selectTab(target, slide = step)
    }

    /**
     * 一页内容。
     *
     * 横屏且右栏有内容时并排两栏；否则按「左 → 右」顺序单栏排下去。
     * 每页自己滚动 —— 标签条不动，切换成本只有一次可见性变化。
     */
    private fun page(tab: SettingsTab, left: List<View>, right: List<View>): ScrollView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(24))
        }
        if (isWide() && right.isNotEmpty()) {
            val leftColumn = column()
            left.forEach { leftColumn.addView(it) }
            val rightColumn = column()
            right.forEach { rightColumn.addView(it) }
            inner.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        leftColumn,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginEnd = dp(6) }
                    )
                    addView(
                        rightColumn,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginStart = dp(6) }
                    )
                }
            )
        } else {
            (left + right).forEach { inner.addView(it) }
        }

        // 「关于」是页脚性质的一句话，只挂在最后一页底部，不单独占一张卡。
        if (tab == SettingsTab.MORE) {
            inner.addView(
                text(getString(R.string.settings_about), 11.5f, secondary = true).apply {
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
            )
        }

        return ScrollView(this).apply {
            tag = tab
            isFillViewport = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(inner)
        }
    }

    /**
     * 标签条。
     *
     * 五个标签平分宽度，竖屏也放得下，不需要横滑；选中态是「文字变蓝 + 2dp 下划线」，
     * 车机上扫一眼就能定位，比图标省矢量资源，也不必担心 ROM 把图标渲染歪。
     */
    private fun tabBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SettingsTab.values().forEach { tab ->
            val label = text(getString(tab.labelRes), 13.5f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(9))
            }
            val underline = View(this).apply {
                setBackgroundColor(colorAccent)
                visibility = View.GONE
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                addView(
                    label,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                // 下划线常驻占位（只改可见性），避免选中/未选中之间高度跳 2dp。
                addView(
                    underline,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(2).coerceAtLeast(1)
                    )
                )
                setOnClickListener {
                    // 点标签也走同一套滑入动效：方向按「往前还是往后」推，
                    // 与左右滑动的手感保持一致。
                    selectTab(tab, slide = (tab.ordinal - currentTab.ordinal).coerceIn(-1, 1))
                }
            }
            bar.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabLabels[tab] = label
            tabUnderlines[tab] = underline
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(
                View(this@SettingsActivity).apply {
                    setBackgroundColor(colorCardStroke)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1).coerceAtLeast(1)
                    )
                }
            )
        }
    }

    /**
     * 切页。
     *
     * 只改可见性，不重建页面；当前页的滚动位置在切走**之前**存下来，切回来再恢复。
     * 顺手把这一页记进 [Prefs.settingsTab]：下次打开设置页直接回到上次那页。
     */
    private fun selectTab(
        tab: SettingsTab,
        restoreScroll: Boolean = true,
        storeCurrent: Boolean = true,
        slide: Int = 0
    ) {
        if (storeCurrent) tabPages[currentTab]?.let { tabScroll[currentTab] = it.scrollY }
        currentTab = tab
        prefs.settingsTab = tab.name
        // 外观 / 歌词两页会改悬浮窗的样子：让窗口浮在页面上方做实时预览。
        // 其它页（状态、运行、更多）不预览 —— 没有可调的外观项，露着只会挡住列表。
        syncPreviewForTab()
        tabPages.forEach { (key, page) ->
            if (key == tab) {
                page.visibility = View.VISIBLE
            } else {
                page.visibility = View.GONE
                // 被切走的那页必须复位：滑动跟手时它带过位移，不复位下次回来是歪的。
                page.translationX = 0f
                page.alpha = 1f
            }
        }
        tabLabels.forEach { (key, label) ->
            label.setTextColor(if (key == tab) colorAccent else colorSecondaryText)
            label.setTypeface(null, if (key == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        tabUnderlines.forEach { (key, line) ->
            line.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        val page = tabPages[tab] ?: return

        // 滑入动效：新页从切换方向的一侧滑进来并淡入。
        // 用屏宽算位移而不是用 page.width —— 刚从 GONE 变 VISIBLE 的 View
        // 还没重新测量，这一帧宽度是 0，按它算等于没有动画。
        if (slide != 0) {
            page.translationX = slide * resources.displayMetrics.widthPixels * SLIDE_RATIO
            page.alpha = 0.45f
            page.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(SLIDE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        if (!restoreScroll) return
        // GONE 的 ScrollView 没有真实高度，必须等这一帧布局完成后再 scrollTo。
        page.post { page.scrollTo(0, tabScroll[tab] ?: 0) }
    }

    /**
     * 决定当前标签页要不要显示「调参预览」。
     *
     * 悬浮窗卡片（编辑按钮 + 宽高滑杆）就在外观页，所以调大小时窗口本来就浮在上面 ——
     * 看不见窗口滑滑杆等于盲调。窗口在预览态是 NOT_TOUCHABLE，触摸穿透到下面的滑杆，挡不住操作。
     *
     * 对话框打开期间会临时关掉（见 [showColorDialog]）：悬浮窗是 `TYPE_APPLICATION_OVERLAY`，
     * 永远在 Activity 的对话框之上，取色滑杆被压住就没法用了。
     */
    private fun syncPreviewForTab() {
        hub.setPreviewVisible(
            currentTab == SettingsTab.LOOK || currentTab == SettingsTab.LYRICS
        )
    }

    /**
     * 决定进来先看哪一页：旋转重建时以 [savedInstanceState] 为准（用户刚在调的尺寸被
     * 重新布局打断，位置不能跳），冷启动时用上次停留的那页。
     */
    private fun restoreTab(saved: Bundle?) {
        val name = saved?.getString(KEY_TAB) ?: prefs.settingsTab
        val tab = SettingsTab.values().firstOrNull { it.name == name } ?: SettingsTab.OVERVIEW
        saved?.getIntArray(KEY_SCROLL)?.let { values ->
            SettingsTab.values().forEachIndexed { index, key ->
                if (index < values.size) tabScroll[key] = values[index]
            }
        }
        currentTab = SettingsTab.OVERVIEW
        // 刚恢复出来的滚动位置不能被「切页前存一次」覆盖掉，所以这里不存。
        selectTab(tab, restoreScroll = saved != null, storeCurrent = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tabPages[currentTab]?.let { tabScroll[currentTab] = it.scrollY }
        outState.putString(KEY_TAB, currentTab.name)
        outState.putIntArray(
            KEY_SCROLL,
            SettingsTab.values().map { tabScroll[it] ?: 0 }.toIntArray()
        )
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun isWide(): Boolean = resources.configuration.screenWidthDp >= WIDE_SCREEN_DP

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), 0, dp(4), dp(16))
        addView(
            LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    text(getString(R.string.app_name), 26f, bold = true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    text(getString(R.string.settings_version, versionName()), 11.5f, secondary = true)
                        .apply {
                            background = pill(chipIdle, dp(9).toFloat())
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                        }
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            text(getString(R.string.settings_subtitle), 12f, secondary = true).apply {
                setPadding(0, dp(7), 0, 0)
            }
        )
    }

    // ---------------------------------------------------------------- 状态与权限

    private fun buildStatusCard(): View {
        statusBanner = text("", 15f, bold = true)
        statusLine = text("", 12f, secondary = true).apply { setPadding(0, dp(4), 0, 0) }

        notificationState = text("", 13f, bold = true)
        notificationButton = textButton(getString(R.string.perm_open)) { onNotificationAction() }
        // 「系统设置」入口必须常驻：自动重绑在部分 ROM 上无效，手工关开是最后的自救路径，
        // 不能因为换了个状态就把这个入口藏掉。
        notificationSettingsButton = textButton(getString(R.string.perm_review)) {
            openNotificationSettings()
        }

        overlayState = text("", 13f, bold = true)
        overlayButton = textButton(getString(R.string.perm_open)) { openOverlaySettings() }

        // 说明只在「还没授权」时出现：已经给过权限的人不需要再读一遍理由，
        // 而第一次进来的人必须知道这两项是干什么的。
        notificationDesc = text(getString(R.string.perm_notification_desc), 11.5f, secondary = true)
            .apply { setPadding(0, dp(3), 0, dp(2)) }
        overlayDesc = text(getString(R.string.perm_overlay_desc), 11.5f, secondary = true)
            .apply { setPadding(0, dp(3), 0, dp(2)) }

        return cardSection(getString(R.string.section_status), null, listOf(
            statusBanner,
            statusLine,
            divider(14, 12),
            text(getString(R.string.perm_notification_title), 14f),
            notificationDesc,
            row(notificationState, notificationButton),
            notificationSettingsButton,
            divider(10, 10),
            text(getString(R.string.perm_overlay_title), 14f),
            overlayDesc,
            row(overlayState, overlayButton)
        ))
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun buildWindowCard(): View {
        editButton = primaryButton(getString(R.string.display_edit)) { toggleEdit() }

        val rows = ArrayList<View>(12)
        rows += editButton
        rows += hint(getString(R.string.position_edit_hint))
        rows += spacer(12)

        // 宽高用滑杆调，不再靠拖四角：四角在真机上很难对准（车机手指更粗），
        // 而且调完说不出「现在到底多大」。滑杆 0 档是自适应，往右是固定像素。
        rows += sizeRow(
            label = getString(R.string.window_width),
            vertical = false,
            ratioMin = OverlayDefaults.WIDTH_MIN,
            ratioMax = OverlayDefaults.WIDTH_MAX,
            current = { prefs.style().widthRatio },
            onChange = { setWidthRatio(it) }
        )
        rows += sizeRow(
            label = getString(R.string.window_height),
            vertical = true,
            ratioMin = OverlayDefaults.HEIGHT_MIN,
            ratioMax = OverlayDefaults.HEIGHT_MAX,
            current = { prefs.style().heightRatio },
            onChange = { setHeightRatio(it) }
        )
        rows += hint(getString(R.string.window_size_hint))
        rows += divider()
        rows += switchRow(
            label = getString(R.string.position_handle),
            hintText = getString(R.string.position_handle_hint),
            current = { prefs.handleEnabled },
            onChange = { enabled ->
                prefs.handleEnabled = enabled
                hub.applyStyle()
            }
        )

        val chooser = displayChooser()
        if (chooser != null) {
            rows += divider()
            rows += chooser
        }

        rows += divider()
        rows += textButton(getString(R.string.position_reset)) { resetWindow() }
        rows += hint(getString(R.string.position_hint))

        return cardSection(getString(R.string.section_window), null, rows)
    }

    /**
     * 宽 / 高滑杆。
     *
     * 拖动过程**不走** [editStyle]：那里面会 `refresh()`，每次滚动都重刷整页状态、
     * 还会把滑杆位置按落盘值重设一遍，手感是「拇指被往回拽」。这里只写 prefs + 重排窗口，
     * 松手时再 refresh 一次对齐其它行。
     */
    private fun sizeRow(
        label: String,
        vertical: Boolean,
        ratioMin: Float,
        ratioMax: Float,
        current: () -> Float,
        onChange: (Float) -> Unit
    ): View {
        val valueView = text("", 13f, bold = true).apply { gravity = Gravity.END }

        fun bounds(): SizeBounds = hub.overlay().sizeBounds()

        /** 一次手势里只查一次可用区：`frame()` 要算 insets，60 次/秒纯属白花。 */
        var gestureBounds: SizeBounds? = null

        fun currentBounds(): SizeBounds = gestureBounds ?: bounds()

        fun pxOf(ratio: Float): Int {
            if (ratio <= 0f) return 0
            return (currentBounds().area(vertical) * ratio).toInt()
        }

        /** 比例 → 滑杆档位。0 比例单独占 0 档（自适应）。 */
        fun progressOf(ratio: Float): Int {
            if (ratio <= 0f) return SizeSlider.AUTO
            val b = currentBounds()
            return SizeSlider.progressOf(pxOf(ratio), b.min(vertical), b.max(vertical))
        }

        fun labelOf(ratio: Float): String = if (ratio <= 0f) {
            getString(R.string.window_size_auto)
        } else {
            getString(R.string.window_size_px, pxOf(ratio))
        }

        val seek = SeekBar(this).apply {
            max = SizeSlider.STEPS
            progress = progressOf(current())
            minimumHeight = dp(40)
            // 连续轨道：默认 splitTrack 会在 thumb 处断开，看着像两颗分离的点。
            splitTrack = false
            progressTintList = ColorStateList.valueOf(colorAccent)
            thumbTintList = ColorStateList.valueOf(colorAccent)
            // 未选中轨道要自己给色：某些 ROM 的默认值是近乎透明的黑，深色主题下等于看不见。
            progressBackgroundTintList = ColorStateList.valueOf(
                if (isNight) 0x33FFFFFF else 0x1F000000
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (progress == SizeSlider.AUTO) {
                        valueView.text = getString(R.string.window_size_auto)
                        onChange(0f)
                        return
                    }
                    val b = currentBounds()
                    val px = SizeSlider.pxOf(progress, b.min(vertical), b.max(vertical))
                    val ratio = (px.toFloat() / b.area(vertical).coerceAtLeast(1))
                        .coerceIn(ratioMin, ratioMax)
                    valueView.text = getString(R.string.window_size_px, px)
                    onChange(ratio)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    gestureBounds = bounds()
                }

                // 松手才整体刷新：拖动中刷新会重设滑杆位置，手感被打断。
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    gestureBounds = null
                    refresh()
                }
            })
        }
        valueView.text = labelOf(current())

        uiRefreshers += {
            val ratio = current()
            valueView.text = labelOf(ratio)
            val target = progressOf(ratio)
            // 程序化设置不会回调 fromUser，可以放心对齐，不必另设同步标志。
            if (seek.progress != target) seek.progress = target
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(2))
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        text(label, 14f),
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )
                    addView(valueView)
                }
            )
            addView(
                seek,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /** 滑杆的落盘：写比例 + 标记自定义预设，然后让窗口按新尺寸重排。 */
    private fun setWidthRatio(ratio: Float) {
        prefs.saveStyle(prefs.style().copy(widthRatio = ratio, preset = Preset.CUSTOM))
        hub.applyStyle()
    }

    private fun setHeightRatio(ratio: Float) {
        prefs.saveStyle(prefs.style().copy(heightRatio = ratio, preset = Preset.CUSTOM))
        hub.applyStyle()
    }

    /** 只有真有多块屏幕时才出现，避免手机上多一行没用的设置。 */
    private fun displayChooser(): View? {
        val manager = getSystemService(DisplayManager::class.java) ?: return null
        val displays = try {
            manager.displays
        } catch (_: Throwable) {
            null
        } ?: return null
        if (displays.size <= 1) return null

        return labeled(
            getString(R.string.position_display),
            chipRow(
                options = displays.map { it.name ?: it.displayId.toString() },
                current = { displays.indexOfFirst { it.displayId == prefs.displayId } },
                onPick = { index ->
                    prefs.displayId = displays[index].displayId
                    hub.applyStyle()
                }
            )
        )
    }

    private fun resetWindow() {
        hub.overlay().resetPosition()
        editStyle {
            it.copy(
                widthRatio = OverlayDefaults.WIDTH_RATIO,
                heightRatio = OverlayDefaults.HEIGHT_RATIO
            )
        }
        refresh()
    }

    // ---------------------------------------------------------------- 外观

    private fun buildLookCard(): View {
        val rows = ArrayList<View>(16)

        rows += labeled(
            getString(R.string.appearance_preset),
            chipRow(
                options = listOf(
                    getString(R.string.preset_stripe),
                    getString(R.string.preset_glass),
                    getString(R.string.preset_solid),
                    getString(R.string.preset_navi)
                ),
                current = { presetIndex() },
                onPick = { choosePreset(PRESET_ORDER[it]) }
            )
        )

        rows += spacer(12)
        rows += stepperRow(
            label = getString(R.string.appearance_font),
            value = { getString(R.string.display_font_value, prefs.style().fontSp.toInt().toString()) },
            isDefault = { prefs.style().fontSp == OverlayDefaults.FONT_SP },
            minus = { editStyle { it.copy(fontSp = it.fontSp - 2f) } },
            plus = { editStyle { it.copy(fontSp = it.fontSp + 2f) } },
            reset = { editStyle { it.copy(fontSp = OverlayDefaults.FONT_SP) } }
        )
        // 行数是**歌词总行数**：当前行折行也算在内，副行按剩余预算往下铺。
        val linesRow = stepperRow(
            label = getString(R.string.appearance_lines),
            value = { getString(R.string.appearance_lines_value, prefs.style().maxLines.toString()) },
            isDefault = { prefs.style().maxLines == OverlayDefaults.MAX_LINES },
            minus = { editStyle { it.copy(maxLines = it.maxLines - 1) } },
            plus = { editStyle { it.copy(maxLines = it.maxLines + 1) } },
            reset = { editStyle { it.copy(maxLines = OverlayDefaults.MAX_LINES) } }
        )
        rows += linesRow
        val linesHint = hint(getString(R.string.appearance_lines_hint))
        rows += linesHint
        // 单行滚动只用一行，行数调了也不生效 —— 整行藏起来，别让人以为设置丢了。
        uiRefreshers += {
            val hide = prefs.style().lineMode == LineMode.SCROLL
            linesRow.visibility = if (hide) View.GONE else View.VISIBLE
            linesHint.visibility = if (hide) View.GONE else View.VISIBLE
        }

        rows += spacer(12)
        rows += labeled(
            getString(R.string.appearance_overflow),
            chipRow(
                options = listOf(
                    getString(R.string.appearance_overflow_wrap),
                    getString(R.string.appearance_overflow_scroll)
                ),
                current = { if (prefs.style().lineMode == LineMode.SCROLL) 1 else 0 },
                onPick = { index ->
                    editStyle {
                        it.copy(
                            lineMode = if (index == 1) LineMode.SCROLL else LineMode.WRAP
                        )
                    }
                }
            )
        )
        rows += hint(getString(R.string.appearance_overflow_hint))

        rows += spacer(12)
        rows += labeled(
            getString(R.string.appearance_align),
            chipRow(
                options = listOf(
                    getString(R.string.align_start),
                    getString(R.string.align_center),
                    getString(R.string.align_end)
                ),
                current = { prefs.style().align.ordinal },
                onPick = { index -> editStyle { it.copy(align = ALIGN_ORDER[index]) } }
            )
        )

        rows += spacer(6)
        rows += stepperRow(
            label = getString(R.string.appearance_opacity),
            value = { getString(R.string.appearance_percent, (prefs.style().opacity * 100).toInt().toString()) },
            isDefault = { prefs.style().opacity == OverlayDefaults.OPACITY },
            minus = { editStyle { it.copy(opacity = it.opacity - 0.05f) } },
            plus = { editStyle { it.copy(opacity = it.opacity + 0.05f) } },
            reset = { editStyle { it.copy(opacity = OverlayDefaults.OPACITY) } }
        )
        rows += stepperRow(
            label = getString(R.string.appearance_corner),
            value = { getString(R.string.appearance_dp, prefs.style().cornerDp.toInt().toString()) },
            isDefault = { prefs.style().cornerDp == OverlayDefaults.CORNER_DP },
            minus = { editStyle { it.copy(cornerDp = it.cornerDp - 2f) } },
            plus = { editStyle { it.copy(cornerDp = it.cornerDp + 2f) } },
            reset = { editStyle { it.copy(cornerDp = OverlayDefaults.CORNER_DP) } }
        )
        // 上下间距之外再给一个「左右错位」：下一句整体右移，形成阶梯感。
        // 只在左对齐下生效，居中/右对齐时渲染器会忽略它。
        rows += stepperRow(
            label = getString(R.string.appearance_line_offset),
            value = {
                getString(R.string.appearance_dp, prefs.style().lineOffsetDp.toInt().toString())
            },
            isDefault = { prefs.style().lineOffsetDp == OverlayDefaults.LINE_OFFSET_DP },
            minus = { editStyle { it.copy(lineOffsetDp = it.lineOffsetDp - 4f) } },
            plus = { editStyle { it.copy(lineOffsetDp = it.lineOffsetDp + 4f) } },
            reset = { editStyle { it.copy(lineOffsetDp = OverlayDefaults.LINE_OFFSET_DP) } }
        )
        rows += hint(getString(R.string.appearance_line_offset_hint))

        rows += divider()
        rows += switchRow(
            label = getString(R.string.appearance_bold),
            hintText = null,
            current = { prefs.style().bold },
            onChange = { checked -> editStyle { it.copy(bold = checked) } }
        )
        rows += switchRow(
            label = getString(R.string.appearance_stroke),
            hintText = getString(R.string.appearance_stroke_hint),
            current = { prefs.style().stroke },
            onChange = { checked -> editStyle { it.copy(stroke = checked) } }
        )

        rows += divider()
        rows += textButton(getString(R.string.appearance_reset)) {
            prefs.resetStyle()
            hub.applyStyle()
            refresh()
        }

        return cardSection(getString(R.string.section_appearance), null, rows)
    }

    // ---------------------------------------------------------------- 主题

    /**
     * 界面明暗。
     *
     * 只有三项，用胶囊而不是开关：开关只能表达两态，而「跟随系统」是第三态，
     * 且是默认值 —— 少了它，用户想「先别管、跟系统走」就没有出口。
     *
     * 切换会重建页面（换 Context 才让 `values-night` 生效），重建前先拍一张旧界面，
     * 由 [ThemeSwitch] 在新界面第一帧淡出 —— 不然这一下白闪很难看。
     */
    private fun buildThemeCard(): View {
        val rows = ArrayList<View>(4)
        rows += labeled(
            getString(R.string.theme_mode),
            chipRow(
                options = ThemeMode.values().map { getString(themeLabel(it)) },
                current = { ThemeMode.values().indexOf(prefs.themeMode) },
                onPick = { index -> applyTheme(ThemeMode.values()[index]) }
            )
        )
        rows += hint(getString(R.string.theme_hint))
        return cardSection(getString(R.string.section_theme), null, rows)
    }

    private fun themeLabel(mode: ThemeMode): Int = when (mode) {
        ThemeMode.AUTO -> R.string.theme_auto
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

    private fun applyTheme(mode: ThemeMode) {
        if (mode == prefs.themeMode) return
        prefs.themeMode = mode
        // 先拍旧界面再重建：recreate 之后旧窗口就没了，拍不到。
        ThemeSwitch.capture(this)
        recreate()
    }

    // ---------------------------------------------------------------- 颜色

    private fun buildColorCard(): View {
        val rows = ArrayList<View>(8)

        // 四项颜色都走同一套「色块 + 十六进制 + 点开取色器」，不再摆一排固定色板。
        rows += colorRow(
            getString(R.string.appearance_color_primary),
            current = { prefs.style().primaryColor },
            default = OverlayDefaults.PRIMARY_COLOR,
            onPick = { color -> editStyle { it.copy(primaryColor = color) } }
        )
        rows += colorRow(
            getString(R.string.appearance_color_secondary),
            current = { prefs.style().secondaryColor },
            default = OverlayDefaults.SECONDARY_COLOR,
            onPick = { color -> editStyle { it.copy(secondaryColor = color) } }
        )
        rows += colorRow(
            getString(R.string.appearance_color_bg),
            current = { prefs.style().bgColor },
            default = OverlayDefaults.BG_COLOR,
            onPick = { color -> editStyle { it.copy(bgColor = color) } }
        )
        val strokeColorRow = colorRow(
            getString(R.string.appearance_color_stroke),
            current = { prefs.style().strokeColor },
            default = OverlayDefaults.STROKE_COLOR,
            onPick = { color -> editStyle { it.copy(strokeColor = color) } }
        )
        rows += strokeColorRow
        // 描边关闭时描边色无效：整行隐藏，别留着让人以为改了有用。
        uiRefreshers += {
            strokeColorRow.visibility = if (prefs.style().stroke) View.VISIBLE else View.GONE
        }

        return cardSection(
            getString(R.string.section_colors),
            getString(R.string.section_colors_hint),
            rows
        )
    }

    // ---------------------------------------------------------------- 标题

    private fun buildTitleCard(): View {
        val rows = ArrayList<View>(8)

        rows += switchRow(
            label = getString(R.string.title_show),
            hintText = getString(R.string.title_show_hint),
            current = { prefs.style().titleEnabled },
            onChange = { checked -> editStyle { it.copy(titleEnabled = checked) } }
        )

        // 标题关闭时，位置 / 字号 / 颜色都是无效配置：整组隐藏，不留空行。
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(divider())
            addView(
                labeled(
                    getString(R.string.title_position),
                    chipRow(
                        options = listOf(
                            getString(R.string.title_pos_top),
                            getString(R.string.title_pos_bottom)
                        ),
                        current = { if (prefs.style().titlePos == TitlePos.TOP) 0 else 1 },
                        onPick = { index ->
                            editStyle {
                                it.copy(
                                    titlePos = if (index == 0) TitlePos.TOP else TitlePos.BOTTOM
                                )
                            }
                        }
                    )
                )
            )
            addView(spacer(6))
            addView(
                stepperRow(
                    label = getString(R.string.title_font),
                    value = {
                        getString(
                            R.string.display_font_value,
                            prefs.style().titleFontSp.toInt().toString()
                        )
                    },
                    isDefault = { prefs.style().titleFontSp == OverlayDefaults.TITLE_FONT_SP },
                    minus = { editStyle { it.copy(titleFontSp = it.titleFontSp - 1f) } },
                    plus = { editStyle { it.copy(titleFontSp = it.titleFontSp + 1f) } },
                    reset = { editStyle { it.copy(titleFontSp = OverlayDefaults.TITLE_FONT_SP) } }
                )
            )
            addView(
                colorRow(
                    getString(R.string.title_color),
                    current = { prefs.style().titleColor },
                    default = OverlayDefaults.TITLE_COLOR,
                    onPick = { color -> editStyle { it.copy(titleColor = color) } }
                )
            )
        }
        rows += details
        uiRefreshers += {
            details.visibility = if (prefs.style().titleEnabled) View.VISIBLE else View.GONE
        }

        return cardSection(getString(R.string.section_title), null, rows)
    }

    // ---------------------------------------------------------------- 行为

    private fun buildBehaviorCard(): View {
        val rows = ArrayList<View>(8)

        rows += switchRow(
            label = getString(R.string.appearance_fade),
            hintText = null,
            current = { prefs.style().fadeMs > 0 },
            onChange = { checked ->
                editStyle { it.copy(fadeMs = if (checked) OverlayDefaults.FADE_MS else 0) }
            }
        )
        rows += switchRow(
            label = getString(R.string.appearance_hide_empty),
            hintText = getString(R.string.appearance_hide_empty_hint),
            current = { prefs.hideWhenNoLyrics },
            onChange = { checked ->
                prefs.hideWhenNoLyrics = checked
                hub.applyStyle()
            }
        )
        rows += divider()
        // 在线补词默认关闭：打开后本地通道全 miss 才会联网找一份（歌词链路里唯一的联网出口）。
        rows += switchRow(
            label = getString(R.string.behavior_online_lyrics),
            hintText = getString(R.string.behavior_online_lyrics_hint),
            current = { prefs.onlineLyrics },
            onChange = { checked ->
                prefs.onlineLyrics = checked
                hub.onOnlineLyricsChanged()
            }
        )

        return cardSection(getString(R.string.section_behavior), null, rows)
    }

    // ---------------------------------------------------------------- 开机与后台

    /**
     * 开机自启 + 后台保活。
     *
     * 两项都默认关闭：通知使用权多数 ROM 重启后会自动恢复，白名单也有副作用
     * （常驻后台省不了多少电反而容易被用户当成流氓行为）。这里只提供入口，
     * 状态永远以系统为准 —— 开关只是「申请」按钮，不是权威事实。
     */
    private fun buildKeepAliveCard(): View {
        val rows = ArrayList<View>(6)

        rows += switchRow(
            label = getString(R.string.autostart_boot),
            hintText = getString(R.string.autostart_boot_hint),
            current = { prefs.bootAutostart },
            onChange = { checked -> prefs.bootAutostart = checked }
        )
        rows += divider()
        rows += switchRow(
            label = getString(R.string.autostart_keepalive),
            hintText = getString(R.string.autostart_keepalive_hint),
            current = { batteryIgnoring() },
            onChange = { checked ->
                // 只处理「打开」：白名单没有可编程的撤销通道，关掉时引导用户去系统设置
                if (checked && !batteryIgnoring()) requestIgnoreBatteryOptimizations()
            }
        )
        rows += divider()
        // 看门狗：只负责「服务被静默杀掉后重新绑定」，不常驻、不持锁。
        rows += switchRow(
            label = getString(R.string.autostart_watchdog),
            hintText = getString(R.string.autostart_watchdog_hint),
            current = { prefs.watchdogEnabled },
            onChange = { checked ->
                prefs.watchdogEnabled = checked
                ServiceWatchdog.sync(this)
            }
        )

        return cardSection(getString(R.string.section_autostart), null, rows)
    }

    // ---------------------------------------------------------------- 按应用隐藏

    /**
     * 按应用隐藏（0.2.11）。
     *
     * 三行：总开关 → 选择应用 → 无障碍服务状态。
     * 开关关掉时后两行整组 GONE —— 它们此刻完全无效，显示出来只会让人以为「设了没用」
     * （与「标题」「描边」两张卡的联动显隐同一套约定）。
     */
    private fun buildHideAppsCard(): View {
        val rows = ArrayList<View>(4)

        rows += switchRow(
            label = getString(R.string.hide_apps_enable),
            hintText = getString(R.string.hide_apps_enable_hint),
            current = { prefs.hideByAppEnabled },
            onChange = { checked ->
                prefs.hideByAppEnabled = checked
                // 用户主动开过 → 以后被系统关掉时才有依据尝试自动写回
                if (checked) prefs.accessibilityRequested = true
                hub.refreshHideByApp()
                // 只提醒、不自动跳转：用户可能想先把名单勾好再一起去开权限。
                // 但必须说清楚，否则表现就是「勾了、也开了，却完全不生效」。
                if (checked && !accessibilityGranted()) {
                    toast(getString(R.string.hide_apps_need_accessibility))
                }
            }
        )

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        detail.addView(divider())
        detail.addView(
            clickableValueRow(
                label = getString(R.string.hide_apps_pick),
                value = {
                    val count = prefs.hiddenApps.size
                    if (count == 0) getString(R.string.hide_apps_pick_none)
                    else getString(R.string.hide_apps_pick_value, count.toString())
                },
                onClick = { showHideAppsDialog() }
            )
        )
        detail.addView(divider())
        // 实时前台：回答「切过去时前台到底是哪个包」。车机上勾的名字与真实包名对不上时，
        // 这是唯一能当场看到证据的地方；列表里可以直接把那个包加进名单。
        detail.addView(
            clickableValueRow(
                label = getString(R.string.hide_apps_recent),
                value = { recentForegroundLabel() },
                onClick = { showRecentForegroundDialog() }
            )
        )
        detail.addView(divider())
        detail.addView(
            clickableValueRow(
                label = getString(R.string.hide_apps_access),
                value = {
                    when {
                        !accessibilityGranted() -> getString(R.string.hide_apps_access_off)
                        !ForegroundApps.serviceConnected -> getString(R.string.hide_apps_access_stale)
                        else -> getString(R.string.hide_apps_access_on)
                    }
                },
                // 未连接就是「这个功能现在完全不起作用」，必须比普通文字更扎眼
                valueColor = {
                    if (accessibilityGranted() && ForegroundApps.serviceConnected) null
                    else colorBad
                },
                onClick = { openAccessibilitySettings() }
            )
        )
        rows += detail

        uiRefreshers += {
            val visible = prefs.hideByAppEnabled
            detail.visibility = if (visible) View.VISIBLE else View.GONE
        }

        return cardSection(getString(R.string.section_hide_apps), null, rows)
    }

    /**
     * 「标签 …… 值」的可点行：整行都是触摸目标，值随状态刷新。
     *
     * 值用 secondary 色、标签用主色 —— 车机上扫一眼就能分出「这是什么」与「现在怎样」。
     */
    private fun clickableValueRow(
        label: String,
        value: () -> String,
        valueColor: (() -> Int?)? = null,
        onClick: () -> Unit
    ): View {
        // 值占剩余宽度并允许两行：权限那一行的文案很长（「未开启 —— 点击去系统设置开启」），
        // 双栏下车机卡片只有 300dp 左右，挤成一行会把左边的标签压没。
        val valueView = text(value(), 12.5f, secondary = true).apply {
            maxLines = 2
            gravity = Gravity.END
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            // 带 weight 的行必须显式 MATCH_PARENT：父容器若按 wrap_content 首测，
            // 部分 ROM 会把权重子项算成 0 宽，整行直接空白（项目里踩过这个坑）。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 触摸反馈：没有它，车机上按下去完全没反应，用户会以为点了没生效
            setOnClickListener {
                onClick()
                refresh()
            }
            addView(text(label, 14f))
            addView(
                valueView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        uiRefreshers += {
            valueView.text = value()
            // 需要警示时染色（例如无障碍没开）：这行的状态如果和普通文字一个颜色，
            // 用户根本不会注意到「按应用隐藏其实早就失效了」。
            valueView.setTextColor(valueColor?.invoke() ?: colorSecondaryText)
        }
        return container
    }

    /**
     * 无障碍服务是否已在系统设置里被勾选（只说明「勾过」，不代表此刻连着）。
     *
     * 缓存与失效都在 [AccessibilityWatchdog] 里做：状态卡每秒刷一次，
     * 而读 `Settings.Secure` 是一次 Binder 调用 —— 每个刷新周期都去问系统纯属白花。
     */
    private fun accessibilityGranted(): Boolean = AccessibilityWatchdog.isGranted(this)

    /**
     * 打开系统的无障碍页面。
     *
     * 走通用的 [openSystemSettings]：**列表页排第一**，详情页不排进来 ——
     * 2026-11-12 真机教训（见该文件「打开系统设置」段）：`ACTION_ACCESSIBILITY_DETAILS_SETTINGS`
     * 这类 DETAIL 页在部分 ROM 上「有页面、startActivity 不抛异常，但什么都没打开」，
     * 失败得极其安静，一旦排第一就再也走不到能用的兜底。
     */
    private fun openAccessibilitySettings() {
        openSystemSettings(
            // 无障碍不在「返回后向导」的覆盖范围：它有自己的开关页与提示（见 showA11yGuide）。
            kind = PendingPermission.NONE,
            candidates = listOf(
                "无障碍列表" to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                "应用详情页" to appDetailsIntent(),
                "设置首页" to Intent(Settings.ACTION_SETTINGS)
            ),
            manualHint = getString(R.string.perm_accessibility_manual)
        )
    }

    // ---------------------------------------------------------------- 应用选择对话框

    /** 应用列表条目。label 只用于显示，pkg 才是身份。 */
    private data class AppEntry(val pkg: String, val label: String)

    /**
     * 「在哪些应用里隐藏」选择器。
     *
     * 三条真机经验（来自 WindTone 同名对话框）：
     *  1. **名单里已有、但不是可启动应用**的包必须列出来 —— 车机上有些「影像 / 桌面挂件」
     *     不注册 LAUNCHER 入口，从列表里根本点不到；不显示就永远取消不掉。
     *  2. **允许直接粘包名**：列表里找不到时，输入完整包名即可添加（排障时最省事）。
     *  3. 勾选**即时生效**，不设「保存」：用户在桌面与设置之间来回切着试，
     *     多点一次确认只会让人以为没生效。
     *
     * 列表在后台线程枚举：车机装几百个应用时，`queryIntentActivities` 放主线程会卡住输入。
     */
    private fun showHideAppsDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }

        val search = EditText(this).apply {
            hint = getString(R.string.hide_apps_search)
            textSize = 14f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(
            search,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        // 高度取屏高一半（封顶 420dp）：车机横屏本来就不高，给太多会把标题和搜索框顶掉。
        val listHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            .coerceAtMost(dp(420))
        root.addView(
            list,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listHeight)
        )

        val adapter = HideAppsAdapter()
        list.adapter = adapter

        var all: List<AppEntry> = emptyList()

        fun refilter() {
            val query = search.text?.toString()?.trim().orEmpty()
            val filtered = if (query.isEmpty()) {
                all
            } else {
                all.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.pkg.contains(query, ignoreCase = true)
                }
            }
            adapter.submit(filtered, manualPackageCandidate(query, all))
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refilter()
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hide_apps_dialog_title))
            .setView(root)
            .setPositiveButton(getString(R.string.hide_apps_close), null)
            // 关掉对话框后刷新卡片上的「已选 N 个」：勾选是即时生效的，汇总要跟上。
            // 界面已经销毁时不再碰 View（例如用户点了通知栏的返回、Activity 先走了）。
            .setOnDismissListener { if (!isFinishing && !isDestroyed) refresh() }
            .show()

        loadLaunchableApps { entries ->
            if (isFinishing) return@loadLaunchableApps
            all = entries
            refilter()
        }
    }

    /**
     * 卡片里「实时前台」那一行的值。
     *
     * 直接给应用名（查不到给包名）：车机上应用名常与真实包名对不上，
     * 这一行存在的意义就是让用户当场看到「刚才切过去时系统报的是谁」。
     */
    private fun recentForegroundLabel(): String {
        val pkg = ForegroundApps.currentPkg ?: return getString(R.string.hide_apps_recent_none)
        return appLabel(pkg)
    }

    /** 包名 → 应用名。带缓存：这行每秒会被 refresh 读到，不该每次都问 PackageManager。 */
    private fun appLabel(pkg: String): String = AppLabels.of(this, pkg)

    /**
     * 最近切过的应用（0.2.12）。
     *
     * 这是 WindTone 那套「前台应用记录」的轻量版：**不落盘**，只保留进程内的最近 20 条。
     * 用法很直接 —— 切到目标应用 → 回到这里 → 列表里就有它的真实包名，点一下即加入名单。
     *
     * 空列表本身就是证据：说明无障碍服务一个事件都没收到，此时该去查权限而不是查名单。
     */
    private fun showRecentForegroundDialog() {
        val entries = ForegroundApps.recent().asReversed()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.hide_apps_recent_title))
                .setMessage(
                    if (ForegroundApps.serviceConnected) {
                        getString(R.string.hide_apps_recent_empty)
                    } else {
                        getString(R.string.hide_apps_recent_no_service)
                    }
                )
                .setPositiveButton(getString(R.string.hide_apps_close), null)
                .show()
            return
        }

        val current = ForegroundApps.currentPkg
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (entry in entries) {
            val check = CheckBox(this).apply {
                isChecked = entry.pkg in prefs.hiddenApps
                // 整行可点：勾选框只负责显示状态，不接点击，否则会出现「点字没反应」
                isClickable = false
                isFocusable = false
            }
            val title = text(appLabel(entry.pkg), 15f).apply {
                if (entry.pkg == current) setTextColor(colorAccent)
            }
            val sub = text(entry.pkg, 11f, secondary = true)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(2), dp(4), dp(2))
                addView(
                    LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(title)
                        addView(sub)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(check)
                setOnClickListener {
                    toggleHiddenApp(entry.pkg)
                    check.isChecked = entry.pkg in prefs.hiddenApps
                    // 卡片上的「已选 N 个」要跟着变；对话框本身不用重建
                    refresh()
                }
            }
            list.addView(row)
            list.addView(divider(6, 6))
        }

        val scroll = ScrollView(this).apply { addView(list) }
        val listHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            .coerceAtMost(dp(460))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    listHeight
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hide_apps_recent_title))
            .setView(root)
            .setPositiveButton(getString(R.string.hide_apps_close), null)
            .setOnDismissListener { if (!isFinishing && !isDestroyed) refresh() }
            .show()
    }

    /** 后台枚举可启动应用，并补上「名单里已有但不可启动」的包。 */
    private fun loadLaunchableApps(onLoaded: (List<AppEntry>) -> Unit) {
        val appContext = applicationContext
        Thread({
            val pm = appContext.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchable = runCatching {
                pm.queryIntentActivities(main, 0)
                    .asSequence()
                    .mapNotNull { it.activityInfo?.applicationInfo }
                    .distinctBy { it.packageName }
                    // 自己不进列表：AnyLyric 的页面始终隐藏悬浮窗，不需要也不该让用户勾自己。
                    .filter { it.packageName != appContext.packageName }
                    .map { info ->
                        val label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName)
                        AppEntry(info.packageName, label)
                    }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
                    .toList()
            }.getOrDefault(emptyList())

            val known = launchable.mapTo(HashSet()) { it.pkg }
            val extra = prefs.hiddenApps.asSequence()
                .filter { it !in known }
                .map { pkg ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    AppEntry(pkg, label)
                }
                .sortedBy { it.label }
                .toList()

            val merged = launchable + extra
            runOnUiThread { onLoaded(merged) }
        }, "anylyric-hide-apps").apply { isDaemon = true }.start()
    }

    /** 搜索串看起来像包名、且列表里没有 → 允许直接添加。 */
    private fun manualPackageCandidate(query: String, all: List<AppEntry>): String? {
        if (query.length < 5 || query.count { it == '.' } < 2) return null
        if (query.any { it.isWhitespace() }) return null
        if (!query.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
        return if (all.any { it.pkg.equals(query, ignoreCase = true) }) null else query
    }

    private fun toggleHiddenApp(pkg: String) {
        val current = prefs.hiddenApps
        prefs.hiddenApps = if (pkg in current) current - pkg else current + pkg
        hub.refreshHideByApp()
    }

    /**
     * 应用列表适配器。
     *
     * 图标走 [LruCache]：一次滚动最多同时看到七八行，缓存 48 个足够来回翻，
     * 又不会像「全量缓存几百个图标」那样在车机的小内存里常驻十几 MB。
     * 缓存挂在页面上（[appIconCache]）而不是适配器里：频道对话框也要用同一份。
     */
    private inner class HideAppsAdapter : BaseAdapter() {
        private var items: List<AppEntry> = emptyList()
        private var manualPkg: String? = null

        fun submit(list: List<AppEntry>, manual: String?) {
            items = list
            manualPkg = manual
            notifyDataSetChanged()
        }

        /** 手动包名那条占第 0 位，其余整体后移一位。 */
        private fun offset(): Int = if (manualPkg != null) 1 else 0

        override fun getCount(): Int = items.size + offset()

        override fun getItem(position: Int): Any? = items.getOrNull(position - offset())

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val icon = row.getChildAt(0) as ImageView
            val label = row.getChildAt(1) as TextView
            val check = row.getChildAt(2) as CheckBox

            val manual = manualPkg
            if (manual != null && position == 0) {
                icon.setImageDrawable(null)
                label.text = getString(R.string.hide_apps_add_package, manual)
                check.isChecked = manual in prefs.hiddenApps
                row.setOnClickListener {
                    toggleHiddenApp(manual)
                    notifyDataSetChanged()
                }
                return row
            }

            val item = items.getOrNull(position - offset()) ?: return row
            label.text = item.label
            check.isChecked = item.pkg in prefs.hiddenApps
            icon.setImageDrawable(appIcon(item.pkg))
            row.setOnClickListener {
                toggleHiddenApp(item.pkg)
                notifyDataSetChanged()
            }
            return row
        }

        private fun buildRow(): LinearLayout = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(
                ImageView(this@SettingsActivity),
                LinearLayout.LayoutParams(dp(32), dp(32))
            )
            addView(
                TextView(this@SettingsActivity).apply {
                    textSize = 15f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, dp(8), 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(CheckBox(this@SettingsActivity).apply {
                // 整行可点：勾选框自己不再接管点击，否则会出现「点字没反应、点框才有」。
                isClickable = false
                isFocusable = false
            })
        }
    }

    // ---------------------------------------------------------------- 播放器频道

    /**
     * 「播放器频道」（0.2.17）。
     *
     * 车机上音乐一般固定走同一个播放器，但自动挑选在三种场景会跳：导航语音临时发布
     * 会话、上一首的残留会话、某个 App 抢了前台。锁定后只认那一个包，**目标没有会话
     * 时宁可不出歌词**，也不读别人的 —— 这是用户明确要的语义。
     */
    private fun buildChannelCard(): View {
        val rows = ArrayList<View>(3)
        rows += clickableValueRow(
            label = getString(R.string.channel_mode),
            value = {
                if (prefs.sessionPinned) {
                    getString(
                        R.string.channel_mode_pinned,
                        appLabel(prefs.pinnedSessionPackage)
                    )
                } else {
                    getString(R.string.channel_mode_auto)
                }
            },
            onClick = { showChannelDialog() }
        )
        rows += hint(getString(R.string.channel_hint))
        return cardSection(getString(R.string.section_channel), null, rows)
    }

    /**
     * 频道选择对话框。
     *
     * 会话列表在这里**现查一次**（`getActiveSessions` + 每个会话读元数据都是跨进程调用），
     * 不放进每秒刷新：设置页开着的时候没必要一直问系统。
     */
    private fun showChannelDialog() {
        val sessions = hub.activeSessionInfos()

        // 每一行登记一个「按当前选中项重画自己」的闭包：点一下立刻换勾与底色，
        // 不重建对话框 —— 重建会闪一下，车机上还容易丢滚动位置。
        val repainters = ArrayList<() -> Unit>()

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun addRow(pkg: String, name: String, subtitle: CharSequence, icon: Drawable?) {
            // 勾自己做，不用平台 RadioButton：它的圆点是系统蓝，与强调色不一致，
            // 而且会自己吃点击（出现「点字没反应、点圈才有」）。
            val mark = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(name, 15f))
                addView(
                    text(subtitle, 11.5f, secondary = true).apply { setPadding(0, dp(2), 0, 0) }
                )
            }
            val appIconView = if (icon != null) {
                ImageView(this).apply { setImageDrawable(icon) }
            } else {
                // 没有图标的那一行（自动选择）留同样的空位，文字才对得齐。
                View(this)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                addView(appIconView, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(12) }
                )
                addView(mark, LinearLayout.LayoutParams(dp(24), dp(24)))
                setOnClickListener {
                    if (prefs.pinnedSessionPackage == pkg) return@setOnClickListener
                    prefs.pinnedSessionPackage = pkg
                    hub.onSessionPinnedChanged()
                    repainters.forEach { it() }
                }
            }

            repainters += {
                val selected = prefs.pinnedSessionPackage == pkg
                row.background = if (selected) pill(tint(colorAccent, 0x1A), dp(12).toFloat()) else null
                if (selected) {
                    mark.text = "✓"
                    mark.background = pill(colorAccent, dp(12).toFloat())
                } else {
                    mark.text = ""
                    mark.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1).coerceAtLeast(1), colorCardStroke)
                    }
                }
            }

            list.addView(row)
        }

        addRow("", getString(R.string.channel_auto), getString(R.string.channel_auto_hint), null)
        list.addView(divider(6, 6))

        if (sessions.isEmpty()) {
            list.addView(
                text(getString(R.string.channel_no_session), 12.5f, secondary = true).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(10))
                }
            )
        } else {
            sessions.forEach { info ->
                val state = getString(
                    if (info.playing) R.string.channel_playing else R.string.channel_paused
                )
                // 包名与状态同一行，状态单独染色：正在播放绿、已暂停次要灰。
                // 列表已按包名去重（见 SessionPin.collapse），不会出现同一个应用两行。
                val subtitle = SpannableString(
                    getString(R.string.channel_subtitle, info.packageName, state)
                ).apply {
                    val start = indexOf(state)
                    if (start >= 0) {
                        setSpan(
                            ForegroundColorSpan(if (info.playing) colorOk else colorSecondaryText),
                            start,
                            start + state.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                addRow(
                    info.packageName,
                    appLabel(info.packageName),
                    subtitle,
                    appIcon(info.packageName)
                )
            }
        }

        repainters.forEach { it() }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(10))
            addView(text(getString(R.string.channel_dialog_title), 19f, bold = true))
            addView(
                text(getString(R.string.channel_dialog_desc), 11.5f, secondary = true).apply {
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

        // 内容多高就多高，最多 58% 屏高 —— 原来固定写死 50% 屏高，
        // 只有三四个会话时底下白着一大片（用户截图里的样子）。
        val scroll = CappedScrollView(
            this,
            (resources.displayMetrics.heightPixels * 0.58f).toInt()
        ).apply { addView(list) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll)
        }

        AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton(getString(R.string.channel_done), null)
            .setOnDismissListener { if (!isFinishing && !isDestroyed) refresh() }
            .show()
    }

    /**
     * 包名 → 32dp 图标（LRU）。
     *
     * 列表与频道路由共用一份缓存：同一个应用在「按应用隐藏」和「播放器频道」
     * 里各画一张 192px 原图，是白白多出来的几 MB。
     */
    private fun appIcon(pkg: String): Drawable? {
        appIconCache.get(pkg)?.let { return it }
        val size = dp(32)
        val source = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        source.setBounds(0, 0, size, size)
        source.draw(Canvas(bitmap))
        return BitmapDrawable(resources, bitmap).also { appIconCache.put(pkg, it) }
    }

    /** ScrollView 没有 maxHeight：内容矮时按内容，超过上限才滚。 */
    private inner class CappedScrollView(context: Context, private val maxPx: Int) :
        ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
            )
        }
    }

    /** 按透明度给颜色做一层淡底（选中行的背景）。 */
    private fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** 本应用是否已在电池优化白名单里。ROM 裁掉该服务时按「不在」处理。 */
    private fun batteryIgnoring(): Boolean = try {
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) ?: false
    } catch (_: Throwable) {
        false
    }

    private fun requestIgnoreBatteryOptimizations() {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(request)
            return
        } catch (_: Throwable) {
            // 个别 ROM 裁掉了这个 action：退回应用详情页，用户自己找「电池」入口
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            // 连详情页都没有：什么都不做，开关会被 refresh 复位成真实状态
        }
    }

    // ---------------------------------------------------------------- 诊断

    private fun buildDiagCard(): View {
        debugSwitch = Switch(this).apply {
            text = getString(R.string.diag_debug_toggle)
            textSize = 15f
            isChecked = prefs.debugEnabled
            minHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> hub.setDebugEnabled(checked) }
        }
        return cardSection(getString(R.string.section_probe), null, listOf(
            debugSwitch,
            hint(getString(R.string.diag_debug_hint)),
            spacer(6),
            textButton(getString(R.string.diag_open)) {
                startActivity(Intent(this@SettingsActivity, DiagActivity::class.java))
            }
        ))
    }

    // ---------------------------------------------------------------- 通用控件

    private fun labeled(label: String, control: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 12f, secondary = true).apply { setPadding(0, 0, 0, dp(6)) })
            addView(control)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun stepButton(symbol: String, action: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 18f
        gravity = Gravity.CENTER
        minWidth = dp(38)
        minHeight = dp(38)
        setTextColor(if (isNight) 0xFFE6E6E6.toInt() else 0xFF2C2C2A.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener {
            action()
            refresh()
        }
    }

    /**
     * 加减步进行：`标签 … [− 值 +] 重置`。
     *
     * 重置只在「已经偏离默认」时出现（INVISIBLE 占位，避免整行跳动）：
     * 默认状态下满屏的「重置」只会让人以为每一项都得动一下。
     */
    private fun stepperRow(
        label: String,
        value: () -> String,
        isDefault: () -> Boolean,
        minus: () -> Unit,
        plus: () -> Unit,
        reset: () -> Unit
    ): View {
        val valueView = text("", 14f, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(52)
            minHeight = dp(38)
        }
        val pillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(chipIdle, dp(10).toFloat())
            addView(stepButton("−", minus))
            addView(valueView)
            addView(stepButton("+", plus))
        }
        val resetView = textButton(getString(R.string.appearance_reset_short)) {
            reset()
            refresh()
        }

        uiRefreshers += {
            valueView.text = value()
            resetView.visibility = if (isDefault()) View.INVISIBLE else View.VISIBLE
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(
                text(label, 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(pillView)
            addView(resetView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun switchRow(
        label: String,
        hintText: String?,
        current: () -> Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        // 程序化同步开关时不能回调 onChange：Android 的 setChecked 也会触发监听。
        // 否则 refresh 里「把控件对齐真实值」会被当成用户操作 → editStyle → preset 被标成
        // CUSTOM。真机表现就是「点了预设、悬浮窗样式变了，但选中态没留住，要再点一次」。
        var syncing = false
        val control = Switch(this).apply {
            isChecked = current()
            minHeight = dp(40)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    onChange(checked)
                    refresh()
                }
            }
        }
        uiRefreshers += {
            val target = current()
            if (control.isChecked != target) {
                syncing = true
                control.isChecked = target
                syncing = false
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(row(text(label, 14f), control))
            if (!hintText.isNullOrBlank()) {
                addView(
                    text(hintText, 11.5f, secondary = true).apply {
                        setPadding(0, 0, dp(8), dp(2))
                    }
                )
            }
        }
    }

    private fun chipRow(
        options: List<String>,
        current: () -> Int,
        onPick: (Int) -> Unit
    ): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = ArrayList<TextView>(options.size)

        options.forEachIndexed { index, label ->
            val cell = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                val padH = dp(13)
                val padV = dp(8)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onPick(index)
                    refresh()
                }
            }
            holder.addView(
                cell,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
            cells += cell
        }

        uiRefreshers += {
            val selected = current()
            cells.forEachIndexed { index, cell -> styleChip(cell, index == selected) }
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(holder)
        }
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.background = pill(
            if (selected) colorAccent else chipIdle,
            dp(10).toFloat()
        )
        view.setTextColor(
            when {
                selected -> Color.WHITE
                isNight -> 0xFFE2E2E2.toInt()
                else -> 0xFF2C2C2A.toInt()
            }
        )
    }

    /**
     * 颜色项：色块 + 名称 + 十六进制，整行可点。
     *
     * 把「可点区域」从一个小小的文字按钮扩到整行，车机上少点偏一半。
     */
    private fun colorRow(
        label: String,
        current: () -> Int,
        default: Int,
        onPick: (Int) -> Unit
    ): View {
        val swatch = View(this).apply {
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
        }
        val hex = text("", 12f, secondary = true)

        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            isClickable = true
            isFocusable = true
            addView(swatch)
            addView(
                text(label, 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(hex)
            setOnClickListener { showColorDialog(current(), default, onPick) }
        }

        uiRefreshers += {
            val color = current()
            swatch.background = pill(color, dp(7).toFloat(), colorCardStroke)
            hex.text = String.format(Locale.US, "#%06X", color and 0xFFFFFF)
        }
        return holder
    }

    /**
     * 取色器：色相 / 饱和度 / 亮度三根滑杆 + 实时预览。
     *
     * 为什么是 HSV 而不是 RGB 三条：同样是「调到你想要的那个色」，HSV 的色相滑杆
     * 一条就能扫过整个色环，RGB 得三条一起试。车机上点按次数越少越好。
     */
    private fun showColorDialog(initial: Int, default: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(8) }
        }
        val hex = text("", 13f, bold = true).apply { setPadding(0, 0, 0, dp(4)) }

        fun render() {
            val color = Color.HSVToColor(hsv)
            preview.background = pill(color, dp(10).toFloat(), colorCardStroke)
            hex.text = String.format(Locale.US, "#%06X", color and 0xFFFFFF)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(4))
        }
        body.addView(preview)
        body.addView(hex)
        body.addView(colorSlider(getString(R.string.color_hue), 360, hsv[0].toInt()) {
            hsv[0] = it.toFloat()
            render()
        })
        body.addView(colorSlider(getString(R.string.color_saturation), 100, (hsv[1] * 100).toInt()) {
            hsv[1] = it / 100f
            render()
        })
        body.addView(colorSlider(getString(R.string.color_brightness), 100, (hsv[2] * 100).toInt()) {
            hsv[2] = it / 100f
            render()
        })
        render()

        // 取色期间收起预览：悬浮窗永远盖在对话框上方，会压住滑杆。
        // 关掉对话框后再放回来，用户正好看到新颜色。
        hub.setPreviewVisible(false)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.appearance_color_custom).removeSuffix("…"))
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(getString(R.string.color_default)) { _, _ ->
                onPick(default)
                refresh()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onPick(Color.HSVToColor(hsv))
                refresh()
            }
            .setOnDismissListener { if (!isFinishing && !isDestroyed) syncPreviewForTab() }
            .show()
    }

    private fun colorSlider(label: String, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val title = text(label, 12f, secondary = true)
        val seek = SeekBar(this).apply {
            this.max = max
            progress = value.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(seek)
        }
    }

    private val chipIdle: Int
        get() = if (isNight) 0xFF2C2C2C.toInt() else 0xFFE9E9E9.toInt()

    // ---------------------------------------------------------------- 关于与更新
    //
    // 更新源是公共分发仓库里的**静态文件**（`LuckyLearning/Release` 下的 `AnyLyric/latest.json`），
    // 不走 GitHub API：不需要鉴权，raw 是 CDN，也不占每小时 60 次的 API 配额。
    //
    // 检查与下载都在工作线程跑，结果回主线程。失败一律翻译成「能照着做」的一句话，
    // 同时写进探测报告 —— 车机上没法看 logcat，屏幕里能自证才有意义。
    //
    // 只有「检查更新 / 下载」会联网。歌词链路仍然不联网、不读文件，这是一期就定死的边界。

    private fun buildUpdateCard(): View {
        updateState = text("", 13f, secondary = true).apply { setPadding(0, dp(8), 0, dp(2)) }
        updateNotes = text("", 12f, secondary = true).apply {
            setPadding(0, dp(8), 0, dp(2))
            visibility = View.GONE
        }
        updateAction = textButton(getString(R.string.update_check)) { onUpdateAction() }
        val routeHint = text(getString(prefs.updateRoute.hintRes), 11f, secondary = true).apply {
            setPadding(0, dp(2), 0, 0)
        }
        val routeRow = labeled(
            getString(R.string.update_route),
            chipRow(
                options = DownloadRoute.values().map { getString(it.labelRes) },
                current = { prefs.updateRoute.ordinal },
                onPick = { index ->
                    prefs.updateRoute = DownloadRoute.values()[index]
                    routeHint.text = getString(prefs.updateRoute.hintRes)
                }
            )
        )

        val rows = ArrayList<View>(10)
        rows += text(getString(R.string.update_version_line, versionName()), 14f, bold = true)
        rows += updateState
        rows += updateNotes
        rows += row(
            updateAction,
            textButton(getString(R.string.update_open_page)) { openReleasePage() }
        )
        rows += divider()
        rows += routeRow
        rows += routeHint

        renderUpdate()
        return cardSection(getString(R.string.section_about), null, rows)
    }

    private fun onUpdateAction() {
        when (updatePhase) {
            UpdatePhase.AVAILABLE -> startDownload()
            UpdatePhase.READY -> installDownloaded()
            UpdatePhase.CHECKING -> toast(getString(R.string.update_checking))
            UpdatePhase.DOWNLOADING -> toast(getString(R.string.update_downloading_unknown))
            else -> startCheck()
        }
    }

    private fun startCheck() {
        updatePhase = UpdatePhase.CHECKING
        updateMessage = ""
        updateProgress = -1
        updateRelease = null
        updateApk = null
        renderUpdate()

        val currentName = versionName()
        val currentCode = versionCode()
        val route = prefs.updateRoute
        val startedAt = SystemClock.elapsedRealtime()
        Thread({
            val result = UpdateChecker.check(currentName, currentCode, route)
            val cost = SystemClock.elapsedRealtime() - startedAt
            pollHandler.post {
                if (isFinishing || isDestroyed) return@post
                // 车机上没法看 logcat：把「花了多久、走的哪条线路、失败原因」写进报告，
                // 下次贴报告就能直接看出是缓存旧版、DNS 还是超时。
                Probe.write(
                    "更新",
                    "检查完成 ${cost}ms route=${route.name} " + when (result) {
                        is CheckResult.Available ->
                            "有新版 ${result.release.version}（code ${result.release.versionCode}）"
                        is CheckResult.UpToDate -> "已是最新 ${result.latestVersion}"
                        is CheckResult.Failed ->
                            "失败 ${result.kind} http=${result.httpCode} ${result.detail}"
                    }
                )
                applyCheckResult(result)
            }
        }, "anylyric-update-check").start()
    }

    private fun applyCheckResult(result: CheckResult) {
        when (result) {
            is CheckResult.Available -> {
                updateRelease = result.release
                updatePhase = UpdatePhase.AVAILABLE
                updateMessage = ""
                Probe.write(
                    "更新",
                    "发现新版本 ${result.release.version}（code ${result.release.versionCode}，" +
                        "${result.release.apkName}）"
                )
            }

            is CheckResult.UpToDate -> {
                updatePhase = UpdatePhase.UP_TO_DATE
                updateMessage = result.latestVersion
                Probe.write("更新", "已是最新（${result.latestVersion}）")
            }

            is CheckResult.Failed -> {
                updatePhase = UpdatePhase.FAILED
                updateMessage = failureText(result.kind, result.httpCode)
                Probe.write("更新", "检查失败 ${result.kind} ${result.httpCode} ${result.detail}")
            }
        }
        renderUpdate()
    }

    private fun startDownload() {
        val release = updateRelease ?: return
        val route = prefs.updateRoute
        updatePhase = UpdatePhase.DOWNLOADING
        updateProgress = 0
        renderUpdate()

        val dir = ApkProvider.dir(this)
        // 长跑资源（0.2.7）：安装包目录只增不清。旧安装包的使命（被安装器读一次）
        // 早就结束了，留着只会把 cacheDir 越养越大 —— 每次开始下载新版本前清一次。
        dir.listFiles()?.forEach { it.delete() }
        Thread({
            try {
                val apk = UpdateChecker.download(release, dir, route) { done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                    // 只在百分比变化时回主线程，别为了几个字节刷爆消息队列
                    if (percent != updateProgress) {
                        updateProgress = percent
                        pollHandler.post { if (!isFinishing && !isDestroyed) renderUpdate() }
                    }
                }
                pollHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    updateApk = apk
                    updatePhase = UpdatePhase.READY
                    Probe.write("更新", "下载完成 ${apk.name}（${apk.length()} 字节，大小与摘要已校验，route=${route.name}）")
                    renderUpdate()
                    installDownloaded()
                }
            } catch (t: Throwable) {
                val kind = (t as? com.anylyric.update.UpdateException)?.kind ?: FailureKind.NETWORK
                pollHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    updatePhase = UpdatePhase.DOWNLOAD_FAILED
                    updateMessage = failureText(kind, 0)
                    Probe.write("更新", "下载失败 $kind ${t.message}")
                    renderUpdate()
                }
            }
        }, "anylyric-update-download").start()
    }

    /**
     * 拉起系统安装器。
     *
     * 「安装未知应用」是 Android 8 起必须由用户手动开一次的独立开关，
     * 没开时系统会静默拒掉安装 —— 所以先查、先把用户送到授权页，不要等他点完再失败。
     */
    private fun installDownloaded() {
        val apk = updateApk ?: return
        if (!ApkInstaller.canInstall(this)) {
            toast(getString(R.string.update_allow_install))
            try {
                startActivity(ApkInstaller.unknownSourcesIntent(this))
            } catch (t: Throwable) {
                Probe.write("更新", "打不开「安装未知应用」页 ${t.javaClass.simpleName}")
            }
            return
        }
        ApkInstaller.install(this, apk).onFailure { t ->
            Probe.write("更新", "拉起安装器失败 ${t.javaClass.simpleName} ${t.message}")
            toast(getString(R.string.update_install_failed, t.javaClass.simpleName))
        }
    }

    private fun openReleasePage() {
        val url = UpdateChecker.releasePageUrl
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            // 车机常没有浏览器：那就把地址复制到剪贴板，用户至少能带走
            Probe.write("更新", "打不开发布页 ${t.javaClass.simpleName}")
            copyToClipboard(url)
        }
    }

    private fun renderUpdate() {
        if (!::updateState.isInitialized) return

        val notes = updateRelease?.notes.orEmpty()
        updateNotes.visibility = if (notes.isNotBlank()) View.VISIBLE else View.GONE
        updateNotes.text = notes

        updateState.text = when (updatePhase) {
            UpdatePhase.IDLE -> getString(R.string.update_check)
            UpdatePhase.CHECKING -> getString(R.string.update_checking)
            UpdatePhase.UP_TO_DATE -> getString(R.string.update_up_to_date, updateMessage)
            UpdatePhase.AVAILABLE ->
                getString(R.string.update_available, updateRelease?.version.orEmpty())

            UpdatePhase.DOWNLOADING ->
                if (updateProgress >= 0) getString(R.string.update_downloading, updateProgress)
                else getString(R.string.update_downloading_unknown)

            UpdatePhase.READY ->
                getString(
                    R.string.update_install_ready,
                    UpdateChecker.formatSize(updateApk?.length() ?: 0L)
                )

            UpdatePhase.FAILED -> getString(R.string.update_check_failed, updateMessage)
            UpdatePhase.DOWNLOAD_FAILED -> getString(R.string.update_download_failed, updateMessage)
        }
        updateState.setTextColor(
            when (updatePhase) {
                UpdatePhase.UP_TO_DATE, UpdatePhase.READY -> colorOk
                UpdatePhase.AVAILABLE -> colorAccent
                UpdatePhase.FAILED, UpdatePhase.DOWNLOAD_FAILED -> colorBad
                else -> colorSecondaryText
            }
        )

        val busy = updatePhase == UpdatePhase.CHECKING || updatePhase == UpdatePhase.DOWNLOADING
        updateAction.text = getString(
            when (updatePhase) {
                UpdatePhase.AVAILABLE -> R.string.update_download
                UpdatePhase.READY -> R.string.update_install
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.DOWNLOADING -> R.string.update_downloading_unknown
                else -> R.string.update_check
            }
        )
        updateAction.isEnabled = !busy
        updateAction.alpha = if (busy) 0.45f else 1f
    }

    /** 失败类别 → 用户能看懂的一句话。 */
    private fun failureText(kind: FailureKind, httpCode: Int): String = when (kind) {
        FailureKind.DNS -> getString(R.string.update_reason_dns)
        FailureKind.TIMEOUT -> getString(R.string.update_reason_timeout)
        FailureKind.HTTP ->
            if (httpCode > 0) getString(R.string.update_reason_http, httpCode)
            else getString(R.string.update_reason_network)

        FailureKind.NOT_FOUND -> getString(R.string.update_reason_not_found)
        FailureKind.BAD_MANIFEST -> getString(R.string.update_reason_bad_manifest)
        FailureKind.NO_APK -> getString(R.string.update_reason_no_apk)
        FailureKind.VERIFY -> getString(R.string.update_reason_verify)
        FailureKind.NETWORK -> getString(R.string.update_reason_network)
    }

    // ---------------------------------------------------------------- 状态刷新

    private fun refresh() {
        val status = hub.currentStatus()
        val listener = status.listenerState

        // 「已授权未绑定」超过时限仍未恢复 → 自动重绑在这台机器上不奏效，
        // 继续显示「正在等待」只会让人干等，必须改指手工关开。
        if (listener == ListenerState.GRANTED_NOT_BOUND) {
            if (firstUnboundAt == 0L) firstUnboundAt = SystemClock.elapsedRealtime()
        } else {
            firstUnboundAt = 0L
        }
        val timedOut = listener == ListenerState.GRANTED_NOT_BOUND &&
            firstUnboundAt != 0L &&
            SystemClock.elapsedRealtime() - firstUnboundAt >= UNBOUND_TIMEOUT_MS

        // 「已授权未绑定」不等于不能用：getActiveSessions 的边界是授权列表，不要求服务实例连着。
        // 已经拿到歌词时，这一状态就是**降级运行**（通知推送换成了低频查询），
        // 再显示成黄色「正在等待系统连接」只会让人以为坏了。
        val degradedWorking = listener == ListenerState.GRANTED_NOT_BOUND &&
            status.hitSource != null

        // 没有通知使用权，但车机直连通道已经拿到歌词：这是**可用**状态。
        // 车厂把授权页拦掉时（DiLink5 →「多媒体不支持」），用户无论怎么点都开不了；
        // 此时还挂着红色「通知使用权未开启」等于让人去修一个修不好的开关。
        val externalLabel = hub.externalStatusLabel()
        val externalWorking = listener == ListenerState.NOT_GRANTED &&
            externalLabel != null &&
            status.hitSource != null

        statusBanner.text = getString(
            when (listener) {
                ListenerState.GRANTED -> R.string.status_listener_connected
                ListenerState.GRANTED_NOT_BOUND -> when {
                    degradedWorking -> R.string.status_listener_degraded_ok
                    timedOut -> R.string.status_listener_unbound_failed
                    else -> R.string.status_listener_unbound
                }

                ListenerState.NOT_GRANTED ->
                    if (externalWorking) R.string.status_external_ok
                    else R.string.status_listener_disconnected
            }
        )
        statusBanner.setTextColor(
            when (listener) {
                ListenerState.GRANTED -> colorOk
                ListenerState.GRANTED_NOT_BOUND -> when {
                    degradedWorking -> colorOk
                    timedOut -> colorBad
                    else -> colorWarn
                }

                ListenerState.NOT_GRANTED ->
                    if (externalWorking) colorOk else colorBad
            }
        )

        statusLine.text = when (listener) {
            ListenerState.NOT_GRANTED ->
                if (externalWorking) {
                    getString(R.string.perm_external_ok_hint, externalLabel)
                } else {
                    // 车厂拦掉授权页时，第二条不依赖权限的路是「无障碍读媒体通知」。
                    // 只在确实拿不到那条权限时才提，普通手机上这句是噪音。
                    getString(R.string.perm_required_hint) + "\n\n" +
                        getString(R.string.perm_a11y_fallback_hint)
                }
            ListenerState.GRANTED_NOT_BOUND -> getString(
                when {
                    degradedWorking -> R.string.perm_unbound_working_hint
                    timedOut -> R.string.perm_unbound_failed_hint
                    else -> R.string.perm_unbound_hint
                }
            )

            ListenerState.GRANTED -> when {
                // 锁定模式读不到目标会话：这不是「没有音乐」，而是「锁定的那个没在放」，
                // 直接说清楚，否则用户会以为歌词功能坏了。
                prefs.sessionPinned && status.chosenPackage == null ->
                    getString(
                        R.string.status_pinned_no_session,
                        appLabel(prefs.pinnedSessionPackage)
                    )

                status.sessionCount == 0 -> getString(R.string.status_no_session)

                else -> {
                    // TITLE 与歌词行相同时（伪歌名漏网 / 判定尚未成立）不能把歌词当歌名展示，
                    // 明说「TITLE 被播放器当歌词用」比展示一句歌词诚实。
                    val titleText = if (status.titleIsLyric) {
                        getString(R.string.status_title_is_lyric)
                    } else {
                        status.title ?: "-"
                    }
                    getString(
                        R.string.status_session,
                        titleText,
                        status.artist ?: "-"
                    ) + "\n" + getString(R.string.status_channel, channelLabel(status.hitSource))
                }
            }
        }

        applyListenerState(listener, degradedWorking, externalWorking)
        applyPermissionState(overlayState, status.overlayGranted)
        notificationDesc.visibility =
            if (listener == ListenerState.GRANTED || degradedWorking || externalWorking) {
                View.GONE
            } else {
                View.VISIBLE
            }
        overlayDesc.visibility = if (status.overlayGranted) View.GONE else View.VISIBLE
        overlayButton.text = getString(
            if (status.overlayGranted) R.string.perm_review else R.string.perm_open
        )

        uiRefreshers.forEach { it() }
        renderUpdate()
        editButton.text = getString(
            if (hub.overlay().isEditable()) R.string.display_edit_exit
            else R.string.display_edit
        )
        // 只在状态真的变了时换底色：refresh 会被轮询反复调到，每次新建一个 Drawable 只是喂 GC
        val editing = hub.overlay().isEditable()
        if (editButton.tag != editing) {
            editButton.tag = editing
            editButton.background = pill(
                if (editing) colorWarn else colorAccentStrong,
                dp(12).toFloat()
            )
        }
        if (debugSwitch.isChecked != prefs.debugEnabled) {
            debugSwitch.isChecked = prefs.debugEnabled
        }
    }

    // ---------------------------------------------------------------- 样式操作

    private fun presetIndex(): Int {
        val preset = prefs.style().preset
        return PRESET_ORDER.indexOf(preset).takeIf { it >= 0 } ?: -1
    }

    private fun choosePreset(preset: Preset) {
        prefs.saveStyle(OverlayDefaults.preset(preset, prefs.style()))
        hub.applyStyle()
        refresh()
    }

    /**
     * 改任意一项样式。
     *
     * 改完就把预设标记为 CUSTOM：否则界面还高亮着「玻璃胶囊」，实际参数早已不是它，
     * 用户下次点同一颗按钮会以为没生效。
     */
    private fun editStyle(block: (OverlayStyle) -> OverlayStyle) {
        val next = block(prefs.style())
        prefs.saveStyle(next.copy(preset = Preset.CUSTOM))
        hub.applyStyle()
        refresh()
    }

    // ---------------------------------------------------------------- 权限交互

    /**
     * 通知使用权那一行的状态。
     *
     * [degradedWorking] 为真时是「已授权、服务未绑，但歌词照常出」——
     * 这是**正常工作状态**（降级运行），不能染成警告色，否则用户会一遍遍去系统设置里
     * 关开那个开关，而那里显示的本来就是「已开启」。
     */
    private fun applyListenerState(
        state: ListenerState,
        degradedWorking: Boolean,
        externalWorking: Boolean = false
    ) {
        // 只有两态：能用 / 要处理。
        //
        // 之前这一行有四种说法（已授予 / 未授予 / 已授权未连接 / 已授权降级运行），
        // 用户要在脑子里做一次翻译才知道「所以现在能不能用」。「已授权未连接」这类
        // 中间态属于**原因**，上面那行状态横幅已经整句说清楚了；这一行只回答能不能用。
        val usable = state == ListenerState.GRANTED || degradedWorking || externalWorking
        notificationState.text = getString(
            if (usable) R.string.perm_state_ok else R.string.perm_state_todo
        )
        notificationState.setTextColor(if (usable) colorOk else colorBad)
        // 能用时不给按钮：那一行不留动作，避免用户以为「还得再点一下才生效」。
        notificationButton.visibility = if (usable) View.GONE else View.VISIBLE
        notificationButton.text = getString(
            // 已授权但没连上：先重试绑定（多数情况一下就回来了），仍失败才跳系统设置。
            if (state == ListenerState.GRANTED_NOT_BOUND) R.string.perm_retry
            else R.string.perm_open
        )
    }

    private fun onNotificationAction() {
        when (hub.listenerState()) {
            ListenerState.NOT_GRANTED -> openNotificationSettings()

            ListenerState.GRANTED_NOT_BOUND -> {
                if (hub.tryRebind(force = true)) {
                    toast(getString(R.string.perm_rebind_requested))
                    startPolling(POLL_FIRST_DELAY_MS)
                } else {
                    openNotificationSettings()
                }
            }

            ListenerState.GRANTED -> openNotificationSettings()
        }
        refresh()
    }

    private fun applyPermissionState(view: TextView, granted: Boolean) {
        // 与通知那一行同一套词：能用 / 要处理。两处说法必须一致，
        // 否则同一个「已开启」在上下两行会显示成不同的字，看着像两个状态。
        view.text = getString(
            if (granted) R.string.perm_state_ok else R.string.perm_state_todo
        )
        view.setTextColor(if (granted) colorOk else colorBad)
    }

    private fun channelLabel(source: String?): String = when (source) {
        "L0" -> getString(R.string.channel_l0)
        "L0b" -> getString(R.string.channel_l0b)
        "L1" -> getString(R.string.channel_l1)
        "L2" -> getString(R.string.channel_l2)
        "ONLINE" -> getString(R.string.channel_online)
        else -> getString(R.string.channel_no_session)
    }

    private fun toggleEdit() {
        val next = !hub.overlay().isEditable()
        // 从前这里要求「先播放音乐」—— 0.2.19 起没有会话也能进编辑态：窗口会显示
        // 一句示例歌词供拖动，样式调好了等有歌时直接生效。真正的前提只有悬浮窗权限。
        if (next && !Settings.canDrawOverlays(this)) {
            toast(getString(R.string.edit_needs_overlay))
            return
        }
        hub.overlay().setEditable(next)
        // 设置页本身会让悬浮窗让位；进编辑态必须立刻把窗口放回来，否则用户拖的是空气。
        hub.refreshHideState()
        toast(getString(if (next) R.string.edit_entered else R.string.edit_exited))
        refresh()
    }

    // ---------------------------------------------------------------- 打开系统设置
    //
    // 2026-11-12 真机反馈：「去开启」和「系统设置」两个按钮点了都没反应。
    // 根因链有两层，第二层才是真凶：
    //   ① 老写法 tryStart 把异常全吞成 false，失败原因当场就丢了；
    //   ② 5f2d207 为了「少让用户翻列表」，把
    //      Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS 排到了第一位。
    //      这台荣耀设备上该 action **有注册的 Activity，但页面打不开**，而且
    //      startActivity 既不抛异常也不返回失败 —— 代码认为成功就 return，
    //      永远走不到后面的列表页。首版用的是列表页，所以一直是好的。
    //      「什么都没打开」且不弹失败说明，正是这个静默失败的样子。
    //   ⇒ **不要再让 DETAIL 页排第一**：它失败得太安静，既检测不到也没法兜底。
    //
    // 现在：
    //  · 列表页（首版验证可用）永远排第一，后面才是应用详情页与设置首页；
    //  · 点击先给一句 toast —— 让用户能区分「点击没生效」和「跳转被拒」；
    //  · 全部抛异常才弹对话框，逐条列出真实原因，可一键复制；
    //  · 对话框里直接写出该设备的**手工路径**。
    // 仍然不依赖 adb：失败原因就显示在这块屏幕上。

    private fun openNotificationSettings() {
        openSystemSettings(
            kind = PendingPermission.LISTENER,
            candidates = listOf(
                // 直达「本应用」那一页（API 30+）。车机常把**列表页**拦掉，
                // 但对单应用的开关页放行，所以它排第一 —— DiLink5 上列表页就是死路。
                "本应用通知使用权" to Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS
                ).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(packageName, Permissions.LISTENER_CLASS)
                ),
                "通知使用权列表" to Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                "应用详情页" to appDetailsIntent(),
                "设置首页" to Intent(Settings.ACTION_SETTINGS)
            ),
            manualHint = getString(R.string.perm_notification_manual)
        )
    }

    private fun openOverlaySettings() {
        openSystemSettings(
            kind = PendingPermission.OVERLAY,
            candidates = listOf(
                "悬浮窗权限页" to Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                "特殊应用权限列表" to Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                "应用详情页" to appDetailsIntent(),
                "设置首页" to Intent(Settings.ACTION_SETTINGS)
            ),
            manualHint = getString(R.string.perm_overlay_manual)
        )
    }

    /**
     * 跳转后回来，检查「该给的是不是还是没给」。
     *
     * 两条触发路径，缺一不可：
     *  · [fromResume] —— 正常往返（设置页把我们盖住又退回来）；
     *  · 延迟探测 —— 有些 ROM 只弹一句「多媒体不支持」就当处理完了，我们不 pause 也不 resume，
     *    没有这条就永远等不到检查，用户看到的是「按钮点了没反应」。
     */
    private fun checkPendingPermission(fromResume: Boolean) {
        val pending = pendingPermission
        if (pending == PendingPermission.NONE) return
        // 延迟探测时人还在本页才算「没跳走」；已被设置页盖住就等 onResume 再判。
        if (!fromResume && !hasWindowFocus()) return

        val granted = when (pending) {
            PendingPermission.LISTENER ->
                Permissions.isListenerGranted(this) ?: return
            PendingPermission.OVERLAY -> Settings.canDrawOverlays(this)
            PendingPermission.NONE -> return
        }
        val waited = SystemClock.elapsedRealtime() - pendingPermissionAt
        if (PermissionGate.shouldGuide(waited, granted)) {
            pendingPermission = PendingPermission.NONE
            Probe.write("权限向导", "${pending.name} 返回后仍未授权（${waited}ms）")
            showPermissionGuide(pending)
        } else if (granted) {
            // 已授权：任务完成，别再挂着等下一次 resume。
            pendingPermission = PendingPermission.NONE
        }
    }

    /**
     * 屏幕内的路径向导。
     *
     * 车机上跳转被拦时，用户唯一能走的就是自己翻设置，所以这里必须写清路径，
     * 并且对「多媒体不支持」给出解释 —— 否则他会以为是本应用坏了。
     */
    private fun showPermissionGuide(kind: PendingPermission) {
        val listener = kind == PendingPermission.LISTENER
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (listener) R.string.perm_guide_listener_title
                    else R.string.perm_guide_overlay_title
                )
            )
            .setMessage(
                getString(
                    if (listener) R.string.perm_guide_listener_body
                    else R.string.perm_guide_overlay_body
                )
            )
            .setPositiveButton(getString(R.string.perm_guide_retry)) { _, _ ->
                if (listener) openNotificationSettings() else openOverlaySettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /**
     * 逐个尝试候选设置页；全部抛异常时把失败明细摆到用户面前。
     *
     * 两条经验写在这里，避免以后又"优化"回去：
     *  · **不用** resolveActivity 做前置门禁：包可见性、ROM 裁剪、动态组件都会让它
     *    与真实结果不一致，唯一可判的是 startActivity 有没有抛异常。
     *  · **startActivity 不抛异常也不代表页面真能打开**（DETAIL 页就是这么坑的），
     *    所以候选顺序只按真机实测结果排，不按"理论上体验更好"排。
     */
    private fun openSystemSettings(
        kind: PendingPermission,
        candidates: List<Pair<String, Intent>>,
        manualHint: String
    ) {
        toast(getString(R.string.perm_opening_settings))
        val failures = StringBuilder()
        for ((label, intent) in candidates) {
            try {
                startActivity(intent)
                Probe.write("设置跳转", "$label 已启动（${intent.action}）")
                // 「已启动」只代表 Intent 没抛异常，不代表页面真的打开了。
                // 记下这一笔，回来时若权限仍未授予，改为屏幕内给路径（见 checkPendingPermission）。
                pendingPermission = kind
                pendingPermissionAt = SystemClock.elapsedRealtime()
                pollHandler.removeCallbacks(permissionProbe)
                pollHandler.postDelayed(permissionProbe, PermissionGate.PROBE_DELAY_MS)
                return
            } catch (t: Throwable) {
                val reason = t.javaClass.simpleName + (t.message?.let { "：$it" } ?: "")
                failures.append("· ").append(label).append(" → ").append(reason).append('\n')
                Probe.write("设置跳转", "$label 失败 $reason")
            }
        }

        val detail = failures.toString().trim().ifBlank { getString(R.string.perm_open_failed_none) }
        val body = getString(R.string.perm_open_failed_body, manualHint, detail)
        Probe.write("设置跳转", "全部候选失败，改为屏幕内说明")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.perm_open_failed_title))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(getString(R.string.perm_copy_detail)) { _, _ ->
                copyToClipboard(body)
            }
            .show()
    }

    private fun copyToClipboard(value: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.perm_copy_label), value)
            )
            toast(getString(R.string.perm_copied))
        } catch (t: Throwable) {
            Probe.write("设置跳转", "复制失败 ${t.javaClass.simpleName}")
            toast(getString(R.string.perm_copy_failed))
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) {
        "-"
    }

    /** 安装包的 versionCode。更新判断以它为准，避免只看版本名带来的「看不见较低版本」死角。 */
    private fun versionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    } catch (_: Throwable) {
        0
    }

    companion object {
        private val PRESET_ORDER = listOf(
            Preset.STRIPE, Preset.GLASS, Preset.SOLID, Preset.NAVI
        )

        /** 应用列表图标缓存条数：够来回滚，又不会在车机小内存里常驻十几 MB。 */
        private const val ICON_CACHE_SIZE = 48
        private val ALIGN_ORDER = listOf(Align.START, Align.CENTER, Align.END)

        /** 从「自适应」起步时的基准比例：宽 60% / 高 14%，都是车机上常见的合理值。 */
        /** 超过这个宽度就摆双栏。车机横屏（约 640dp+）受益最大，手机竖屏仍是单栏。 */
        private const val WIDE_SCREEN_DP = 600

        /** 首次刷新的延迟：给系统一点绑定时间，免得白跑一轮。 */
        private const val POLL_FIRST_DELAY_MS = 400L

        /** 空窗期轮询间隔。只读授权列表与内存标志，代价可忽略。 */
        private const val POLL_INTERVAL_MS = 1_200L

        /** 最多轮询次数（约 10 秒）。系统不重绑时不再空转，改为提示手工重开。 */
        private const val MAX_POLL_TICKS = 8

        private const val UNBOUND_TIMEOUT_MS = 9_000L

        /** 滑动切页的长度：短到不挡下一步操作，长到能看出「换了一页」。 */
        private const val SLIDE_MS = 220L

        /** 滑入时的起始横向位移 = 屏宽的 18%，约等于一页宽度的六分之一。 */
        private const val SLIDE_RATIO = 0.18f

        /** 手指跟手位移的折减系数：跟满会露出底下空白，像界面被撕开。 */
        private const val FOLLOW_RATIO = 0.35f

        /** 旋转重建时保存当前标签页与各页滚动位置（Int 快照，见项目红线）。 */
        private const val KEY_TAB = "settings_tab"
        private const val KEY_SCROLL = "settings_tab_scroll"
    }
}
