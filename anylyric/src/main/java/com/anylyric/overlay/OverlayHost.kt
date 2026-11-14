package com.anylyric.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.anylyric.R
import com.anylyric.data.Prefs
import kotlin.math.abs

/**
 * 悬浮层的唯一出入口。
 *
 * 关键约定：
 *  · 歌词窗默认 `FLAG_NOT_TOUCHABLE`，完全不拦截驾驶操作；只有编辑态才临时打开触摸；
 *  · **收起把手是独立小窗口**（[CollapseHandle]）：只有那条窄条吃触摸，歌词区照旧穿透；
 *  · 位置存**比例**，拖动时先钳制像素再换算回比例，因此永远不可能把窗口拖出屏幕；
 *  · add / remove / update 全部吞掉异常：车厂 ROM 的窗口策略差异会导致
 *    BadTokenException / SecurityException，不能让悬浮窗把进程带崩。
 */
class OverlayHost(private val context: Context, private val prefs: Prefs) {

    private val density = context.resources.displayMetrics.density

    private var windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var view: LyricRenderer? = null
    private var params: WindowManager.LayoutParams? = null

    private var handle: CollapseHandle? = null
    private var handleParams: WindowManager.LayoutParams? = null

    /**
     * 把手当前是否「塞进歌词窗左侧内衬」。
     *
     * 常规情况把手挂在窗外，不与窗口有任何重叠；歌词窗被拖到屏幕最左边时窗外没地方了，
     * 只能塞进窗口让出的左侧内衬里。这个标记既是布局结果，也是下一次判断的迟滞输入。
     */
    private var handleInside = false

    private var editable = false
    private var menuOpen = false

    /**
     * 设置页的「调参预览」态（0.2.19）。
     *
     * 用户反馈「修改悬浮窗时看不到它」：设置页本身会让悬浮窗让位，于是调字号、调宽度
     * 全是盲调。预览态下即使本应用界面在前台也照常显示（窗口仍是 NOT_TOUCHABLE，
     * 不挡任何点击），只在外观 / 歌词这两个真正会改样子的标签页里打开。
     */
    private var previewing = false

    /**
     * 本应用设置页是否在前台。
     *
     * 只用来决定「编辑态要不要自动超时」：用户正在设置页里慢慢调尺寸时，
     * 12 秒一到框就消失，比什么都不做更让人恼火。离开设置页时页面自己会退出编辑态。
     */
    private var hostUiVisible = false

    /** 收起态：歌词窗被移除，只留左侧把手。 */
    private var collapsed = false

    /**
     * 按应用隐藏的临时态（0.2.11）：命中名单时歌词与把手一起不显示。
     *
     * 与 [collapsed] 独立：用户收起是主动态，这里跟着前台应用走。恢复时只清本字段。
     */
    private var suppressed = false

    /** 歌词窗最后一次的窗口矩形（收起后把手靠它留在原位）。 */
    private val lastRect = Rect()

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragging = false

    /** 拖角改尺寸：-1 表示没在拖角。0 左上 / 1 右上 / 2 左下 / 3 右下。 */
    private var resizeCorner = -1
    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizing = false
    private var resizeW = 0
    private var resizeH = 0
    private val resizeOrigin = Rect()

    /**
     * 按下瞬间的可用矩形。
     *
     * 拖动与拖角过程中屏幕范围不会变（方向变了系统会先发 CANCEL），
     * 而在 MOVE 里每秒查六十次 `currentWindowMetrics` + insets 纯属白花 —— 按下时算一次即可。
     */
    private var dragFrame = OverlayGeometry.Frame(0, 0, 0, 0)

    private var hiddenUntil = 0L
    private var attachedDisplayId = Prefs.DEFAULT_DISPLAY_ID

    private var lastPrimary = ""
    private var lastSecondary: List<String> = emptyList()
    private var lastTitle: String? = null
    private var lastMode = LyricRenderer.Mode.CONTENT

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 复用的「排一次重新布局」任务。
     *
     * 每句歌词都会走一次，以前每走一次就 new 一个 Runnable；复用同一个没有副作用
     * （同一个 Looper 队列里本来就只会有一个待执行实例）。
     */
    private val placeTask = Runnable { place(update = true) }

    /** 上层重渲染入口：菜单关闭、隐藏到期、编辑态需要重建窗口时回调。 */
    var onRequestRender: (() -> Unit)? = null

    /** 打开设置页。用回调而不是直接 startActivity，避免 overlay 反向依赖 ui 包。 */
    var onOpenSettings: (() -> Unit)? = null

    /**
     * 编辑态进出后的通知。上层据此重算「本应用界面要不要让位」——
     * 否则从长按菜单点「完成」退出编辑后，窗口会一直浮在设置页上，直到下一次刷新。
     */
    var onEditStateChanged: ((Boolean) -> Unit)? = null

    private val exitEdit = Runnable { setEditable(false) }

    private val longPress = Runnable {
        if (editable && !dragging && !menuOpen && resizeCorner < 0) openMenu()
    }

    private val hideExpired = Runnable {
        hiddenUntil = 0L
        onRequestRender?.invoke()
    }

    fun isAttached(): Boolean = view?.isAttachedToWindow == true

    /** 窗口当前像素尺寸；还没量到时为 0×0。 */
    fun windowSize(): Pair<Int, Int> = lastSurfaceW to lastSurfaceH

    /** 进程启动以来窗口尺寸变化次数（诊断用，见 [onSurfaceSizeChanged]）。 */
    fun resizeCount(): Int = windowResizes

    private var lastSurfaceW = 0
    private var lastSurfaceH = 0
    private var windowResizes = 0

    /**
     * 记录一次真实的尺寸变化。
     *
     * 「从 0 到有」不算 resize —— 那是窗口第一次挂上来的正常测量，
     * 把它算进去会让计数永远多 1，看趋势时容易误判。
     */
    private fun onSurfaceSizeChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (lastSurfaceW > 0 && lastSurfaceH > 0 &&
            (w != lastSurfaceW || h != lastSurfaceH)
        ) {
            windowResizes++
        }
        lastSurfaceW = w
        lastSurfaceH = h
    }

    /**
     * 歌词窗当前是否存在。
     *
     * 上层用它决定「同一句能不能跳过推送」：窗口被移除（收起、退出编辑、隐藏到期）
     * 之后 view 为 null，这时候跳过推送就再也没人去建窗口了。
     */
    fun hasWindow(): Boolean = view != null

    fun isMeasured(): Boolean {
        val v = view ?: return false
        return v.width > 0 && v.height > 0
    }

    fun isEditable(): Boolean = editable

    fun showContent(primary: String, secondary: List<String>, title: String?) =
        show(primary, secondary, title, LyricRenderer.Mode.CONTENT)

    /** 空状态提示：小一号、降透明度，和真歌词区分开。 */
    fun showPrompt(primary: String, secondary: List<String>) =
        show(primary, secondary, null, LyricRenderer.Mode.PROMPT)

    private fun show(
        primary: String,
        secondary: List<String>,
        title: String?,
        mode: LyricRenderer.Mode
    ) {
        // 「隐藏 10 分钟」期间的任何刷新都不该把窗口又拉回来
        if (SystemClock.elapsedRealtime() < hiddenUntil) return

        val changed = primary != lastPrimary ||
            secondary != lastSecondary ||
            title != lastTitle ||
            mode != lastMode
        lastPrimary = primary
        lastSecondary = secondary
        lastTitle = title
        lastMode = mode

        // 收起态 / 按应用隐藏：窗口与 View 都保留，只让渲染器不画。
        // 内容照记到 View（不重建），恢复时直接重画 —— 隐藏期间不能把窗口又建出来，
        // 否则在名单应用里会闪一下歌词。
        if (collapsed || suppressed) {
            view?.setContent(primary, secondary, title, mode)
            return
        }

        // 菜单打开期间，播放回调仍会每几百毫秒推一次内容。只更新「待恢复的内容」，
        // 不动当前界面 —— 否则菜单会被下一句歌词顶掉，用户根本点不到。
        if (menuOpen) return

        val current = view
        if (current == null) {
            addView(primary, secondary, title, mode)
            return
        }
        // 内容没变就不重绘、不重新排位置：播放器每 250ms 推一次状态，
        // 每句歌词能触发三四次刷新，白跑一次 layout 就是白烧一次车机 GPU。
        if (!changed) return
        current.setContent(primary, secondary, title, mode)
        current.post(placeTask)
    }

    private fun addView(
        primary: String,
        secondary: List<String>,
        title: String?,
        mode: LyricRenderer.Mode
    ): Boolean {
        refreshWindowManager()

        val renderer = LyricRenderer(context).apply {
            setMenuLabels(menuLabels())
            setHint(context.getString(R.string.edit_hint_bar))
            applyStyle(prefs.style())
            setEditing(editable)
            setContent(primary, secondary, title, mode)
        }
        applyLayoutLimits(renderer)
        // 在 layout 期间直接改窗口位置不安全，延到下一轮消息再摆
        // 窗口尺寸变化要计数：自适应宽度下每句歌词长度不同都会触发一次 resize，
        // 而 Surface 的 GPU buffer 是按窗口面积分配的 —— 这是 graphics 偏高的直接嫌疑。
        renderer.sizeListener = { w, h ->
            onSurfaceSizeChanged(w, h)
            renderer.post(placeTask)
        }

        val lp = buildParams()
        val added = try {
            windowManager?.addView(renderer, lp)
            true
        } catch (_: Throwable) {
            false
        }
        if (!added) return false

        if (editable) renderer.setOnTouchListener(touchListener())
        view = renderer
        params = lp
        attachedDisplayId = prefs.displayId
        // 兜底：极端时序下（收起态又走到 addView）新 View 也应是收起态。
        if (collapsed) renderer.setCollapsed(true)
        renderer.post(placeTask)
        // 窗口叠放顺序按加入顺序：歌词窗贴左边缘时把手会与它重叠，
        // 被压在下面就点不到了 —— 所以每次重建歌词窗都要把把手重新加一遍（盖在最上面）。
        if (handle != null) removeHandle()
        syncHandle()
        return true
    }

    /** 彻底移除：歌词窗与把手一起走（无会话 / 服务断开 / 无歌词时隐藏）。 */
    fun hide() {
        handler.removeCallbacks(exitEdit)
        removeLyric()
        removeHandle()
        collapsed = false
        editable = false
        menuOpen = false
        dragging = false
        resizeCorner = -1
        resizing = false
    }

    /**
     * 服务销毁时的彻底收尾（0.2.7）：在 [hide] 之外把 Handler 上的所有回调摘干净，
     * 并复位「隐藏到期」时间戳。
     *
     * `hideExpired` 若被清掉而 `hiddenUntil` 还留着旧值，下一次服务起来后
     * `show()` 会一直命中「隐藏期内」直接返回 —— 表现是歌词永远不再出现。
     * 两个动作必须绑在一起。
     */
    fun release() {
        hide()
        handler.removeCallbacksAndMessages(null)
        hiddenUntil = 0L
        // 按应用隐藏是环境态：服务销毁后必须复位，否则下次起来若恰好没有前台事件
        // （服务启动瞬间就停在名单应用里），悬浮窗会一直不显示。
        suppressed = false
    }

    private fun removeLyric() {
        val current = view ?: return
        try {
            windowManager?.removeViewImmediate(current)
        } catch (_: Throwable) {
            // 窗口已失效：忽略
        }
        view = null
        params = null
    }

    // ---------------------------------------------------------------- 收起 / 展开

    /**
     * 收起只移除歌词窗，把手留在原位；展开时重新走一次渲染。
     *
     * 不落盘：收起是「临时让出屏幕」的动作，重启后应该回到展开态，
     * 否则用户会以为悬浮窗坏了。
     */
    fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        // 按应用隐藏期间没有把手可点，也不该被上层顺手收起 —— 恢复后状态会错乱。
        if (value && suppressed) return

        if (value) {
            // 没有把手就没法展开：此时拒绝收起，否则窗口会变成不可恢复。
            if (!prefs.handleEnabled) return
            val current = view
            val lp = params
            if (current != null && lp != null) {
                lastRect.set(lp.x, lp.y, lp.x + current.width, lp.y + current.height)
            }
            editable = false
            menuOpen = false
            // 先置位再建把手：把手初始化的箭头方向读的就是这个状态
            collapsed = true
            // 不拆窗口：只让渲染器停止绘制并主动重绘一次，surface 清成透明。
            // 拆窗口再 addView 会闪一帧，这正是「收起/展开卡顿」的根源。
            view?.setCollapsed(true)
            handle?.collapsed = true
            ensureHandle()
            if (handle == null) {
                // 车厂 ROM 只允许一个 overlay 窗口时，收起了就再也点不开
                collapsed = false
                view?.setCollapsed(false)
                onRequestRender?.invoke()
                return
            }
            placeHandle()
            return
        }

        collapsed = false
        handle?.collapsed = false
        val current = view
        if (current != null) {
            // 原地展开：只重建一次内容并重绘，不新建 View、不重新 addView。
            current.setCollapsed(false)
            current.post(placeTask)
        } else {
            onRequestRender?.invoke()
        }
        handle?.post { placeHandle() }
    }

    /** 换会话时复位收起态：换了播放器还收着，用户会以为歌词彻底没了。 */
    fun resetCollapsed() {
        if (!collapsed) return
        collapsed = false
        handle?.collapsed = false
        view?.setCollapsed(false)
    }

    // ---------------------------------------------------------------- 按应用隐藏

    /**
     * 「当前前台应用在隐藏名单里」：临时不画，**连左侧把手一起撤掉**（0.2.11）。
     *
     * 为什么连把手也撤：用户勾选这些应用就是为了「屏幕上什么都别挡」——
     * 视频、360 影像、导航这些界面上，一颗 24dp 的把手也照样压着画面。
     *
     * 三条实现约定：
     *  1. **不拆窗口**：只让渲染器停止绘制（复用收起那套「画空一次」），显隐都不掉帧；
     *  2. **不动用户收起态**：本方法只管 [suppressed]，恢复后用户原来收着的还收着；
     *  3. **不落盘**：这是环境态，进程重启后由前台事件重新判定。
     */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value

        if (value) {
            // 记下最后一帧位置：恢复时把手要摆回原处，不能因为隐藏期间没窗口就跑到默认位。
            val current = view
            val lp = params
            if (current != null && lp != null) {
                lastRect.set(lp.x, lp.y, lp.x + current.width, lp.y + current.height)
            }
            menuOpen = false
            // 编辑态必须**真正**退出（走 setEditable，而不是只改字段）：
            // 那扇窗口此刻仍挂在屏幕上，只清 editable 不摘触摸监听的话，
            // 被遮挡的应用上会有一块区域继续吃掉点击 —— 用户看到的是「点了没反应」。
            // 代价是这一次会拆窗重建，但「编辑态下切进名单应用」本身就是边缘场景。
            if (editable) setEditable(false)
            view?.setSuppressed(true)
            removeHandle()
            return
        }

        view?.setSuppressed(false)
        if (view == null) {
            // 隐藏期间窗口被移除了（例如「隐藏 10 分钟」到期前就销毁了服务）：
            // 让上层重推一次，由 show 决定是否重建窗口。
            onRequestRender?.invoke()
        }
        // 收起态由 setCollapsed 自己维护，这里只负责把把手补回来（收起态也照样要把手）。
        syncHandle()
    }

    fun isSuppressed(): Boolean = suppressed

    /** 与设置项的开关同步：关掉时把手立刻消失，开着且有窗口时补上。 */
    fun syncHandle() {
        if (!prefs.handleEnabled) {
            removeHandle()
            if (collapsed) {
                // 把手被关掉时不能留一个收起的空窗：直接展开
                collapsed = false
                view?.setCollapsed(false)
                onRequestRender?.invoke()
            }
            return
        }
        // 按应用隐藏期间一颗把手都不留：用户勾这些应用就是为了「什么都别挡」。
        if (suppressed) {
            removeHandle()
            return
        }
        if (view == null && !collapsed) {
            removeHandle()
            return
        }
        if (editable) {
            removeHandle()
            return
        }
        ensureHandle()?.let {
            it.collapsed = collapsed
            placeHandle()
        }
    }

    private fun ensureHandle(): CollapseHandle? {
        handle?.let { return it }
        if (!prefs.handleEnabled || editable || suppressed) return null
        refreshWindowManager()
        val h = CollapseHandle(context).apply {
            collapsed = this@OverlayHost.collapsed
            // 显式写全限定：这里的 collapsed 有两个同名属性，不写清就是在赌读者理解
            setOnClickListener { setCollapsed(!this@OverlayHost.collapsed) }
        }
        val lp = buildHandleParams()
        return try {
            windowManager?.addView(h, lp)
            handle = h
            handleParams = lp
            h
        } catch (_: Throwable) {
            // 车厂 ROM 可能只允许一个 overlay 窗口：把手失败不影响歌词显示
            null
        }
    }

    private fun removeHandle() {
        val current = handle ?: return
        try {
            windowManager?.removeViewImmediate(current)
        } catch (_: Throwable) {
            // 已失效：忽略
        }
        handle = null
        handleParams = null
        // 把手没了，内衬也要跟着还回去，否则歌词左侧会永久空一块
        view?.setLeftInset(0)
        handleInside = false
    }

    private fun buildHandleParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            (CollapseHandle.HANDLE_WIDTH_DP * density).toInt(),
            (CollapseHandle.HANDLE_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 可点但不可聚焦：不抢按键焦点，也不挡其它窗口的触摸（只挡自己这一小块）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

    /**
     * 摆把手：常规挂窗外，贴屏边放不下时塞进歌词窗的左侧内衬。
     *
     * 「不压歌词」是硬要求，两个位置都要满足：
     *  · 窗外 —— 与窗口零重叠，最省事；
     *  · 窗内 —— 让渲染器把文字整体右移一条把手宽（[LyricRenderer.setLeftInset]），
     *    按钮落在让出来的空白里。
     * 既不重叠也不出屏，不必让用户在「能点」和「能看」之间二选一。
     */
    private fun placeHandle(box: OverlayGeometry.Frame = frame()) {
        val h = handle
        val lp = handleParams
        val renderer = view
        if (h == null || lp == null) {
            // 把手不存在（关掉 / 编辑态 / 已收起）：歌词窗不该继续空着那条内衬
            renderer?.setLeftInset(0)
            handleInside = false
            return
        }

        val hw = lp.width
        val hh = lp.height
        val gap = (HANDLE_GAP_DP * density).toInt()

        val anchorLeft = if (lastRect.width() > 0) lastRect.left else box.left
        val anchorTop = if (lastRect.height() > 0) lastRect.top else box.top
        val anchorHeight = if (lastRect.height() > 0) lastRect.height() else box.height

        val placement = OverlayGeometry.handlePlacement(
            frame = box,
            windowLeft = anchorLeft,
            handleWidth = hw,
            gap = gap,
            inside = handleInside,
            hysteresis = (HANDLE_HYSTERESIS_DP * density).toInt()
        )
        // 先让歌词窗让出内衬，再摆把手：窗口宽度变了会重新布局并再次进到这里，
        // 第二次算出来的落点才是最终值（迟滞保证不会来回翻）。
        renderer?.setLeftInset(if (placement.inside) hw + gap else 0)
        handleInside = placement.inside

        val maxX = (box.right - hw).coerceAtLeast(box.left)
        val x = placement.x.coerceIn(box.left, maxX)
        val maxY = (box.bottom - hh).coerceAtLeast(box.top)
        val y = (anchorTop + (anchorHeight - hh) / 2).coerceIn(box.top, maxY)

        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        try {
            windowManager?.updateViewLayout(h, lp)
        } catch (_: Throwable) {
            // 窗口已失效：忽略
        }
    }

    /** 临时隐藏（长按菜单「隐藏 10 分钟」），到期后回调上层重新渲染。 */
    fun hideFor(durationMs: Long) {
        hiddenUntil = SystemClock.elapsedRealtime() + durationMs
        hide()
        handler.removeCallbacks(hideExpired)
        handler.postDelayed(hideExpired, durationMs)
    }

    fun setEditable(value: Boolean) {
        handler.removeCallbacks(exitEdit)
        menuOpen = false

        // 收起态没有歌词窗可编辑：先展开再说
        if (value && collapsed) setCollapsed(false)

        val current = view
        val lp = params
        if (current == null || lp == null) {
            editable = value
            if (!value) return
            // 没有窗口也要给一个可拖的预览窗（0.2.19）。
            // 以前这里只回调上层重新渲染：没有会话 / 刚被隐藏时就什么都不会出现，
            // 用户点了「进入编辑态」却在屏幕上找不到框，只能以为功能坏了。
            if (!addPreviewWindow()) onRequestRender?.invoke()
            view?.setEditing(true)
            view?.post(placeTask)
            armEditTimeout()
            onEditStateChanged?.invoke(true)
            return
        }

        lp.flags = flagsFor(value)
        if (!value) {
            // 退出编辑态不能只靠 updateViewLayout 改 flags —— 车机上出现过「改完 flags
            // 输入通道仍是可触摸」，结果整块歌词区域继续吃掉点击。直接把窗口拆了重建：
            // 新窗口添加时就带 NOT_TOUCHABLE，走的是完整的窗口添加流程，比改 flag 可靠。
            // 内容不用另存：重建时 addView 会拿 lastPrimary/lastSecondary 重新铺一遍。
            editable = false
            current.setOnTouchListener(null)
            removeLyric()
            removeHandle()
            collapsed = false
            onRequestRender?.invoke()
            onEditStateChanged?.invoke(false)
            return
        }

        try {
            windowManager?.updateViewLayout(current, lp)
        } catch (_: Throwable) {
            // 更新失败就保持原状：贸然把 editable 置真会留下「能点但拦不到触摸」的错位状态
            return
        }
        editable = true
        current.setOnTouchListener(touchListener())
        current.setEditing(true)
        // 编辑态收起把手：拖角时它会碍事，而且此时不需要「收起」
        removeHandle()
        armEditTimeout()
        current.post(placeTask)
        onEditStateChanged?.invoke(true)
    }

    /**
     * 编辑态的自动退出倒计时。
     *
     * 设置页在前台时不排计时器：用户正点着字号、来回拖角，中途框自己消失是纯干扰。
     * 离开设置页时 `SettingsActivity.onStop` 会主动退出编辑态，不会一直吃触摸。
     */
    private fun armEditTimeout() {
        handler.removeCallbacks(exitEdit)
        if (hostUiVisible) return
        handler.postDelayed(exitEdit, EDIT_TIMEOUT_MS)
    }

    /** 设置页进出前台时同步，只影响「编辑态会不会超时」。 */
    fun setHostUiVisible(visible: Boolean) {
        if (hostUiVisible == visible) return
        hostUiVisible = visible
        if (visible) {
            handler.removeCallbacks(exitEdit)
        } else if (editable) {
            armEditTimeout()
        }
    }

    /**
     * 调参预览：设置页停在外观 / 歌词页时打开，切走就关。
     *
     * 与「编辑态」的区别：预览**不接收触摸**（窗口照旧 NOT_TOUCHABLE），
     * 所以它能一直浮在设置页上面给用户看效果，而不会把开关和滑杆挡住。
     */
    fun setPreviewing(value: Boolean) {
        if (previewing == value) return
        previewing = value
        if (value) return
        // 关掉预览时若正在编辑，交给编辑态决定显隐，别把正在拖的框藏了
        if (editable) return
        view?.post(placeTask)
    }

    fun isPreviewing(): Boolean = previewing

    /**
     * 建一个「示例歌词」窗口，供没有会话时进入编辑态使用。
     *
     * 特意不写进 [lastPrimary]：它不是真歌词，写进去以后第一句真歌词若恰好同文本
     * 会被 [show] 判成「没变」而不再重绘。
     */
    private fun addPreviewWindow(): Boolean {
        val added = addView(
            context.getString(R.string.edit_preview_line),
            listOf(context.getString(R.string.edit_preview_hint)),
            null,
            LyricRenderer.Mode.CONTENT
        )
        if (!added) return false
        lastPrimary = ""
        lastSecondary = emptyList()
        lastTitle = null
        lastMode = LyricRenderer.Mode.CONTENT
        return true
    }

    /**
     * 设置页调参预览用：没有歌词可显示时给一句示例，让用户能看着真实字效调样式。
     *
     * 与编辑态的示例窗是同一段文字 —— 两处写不同内容会让人以为「编辑时和预览时不一样」。
     */
    fun showSample() = show(
        context.getString(R.string.edit_preview_line),
        listOf(context.getString(R.string.edit_preview_hint)),
        null,
        LyricRenderer.Mode.CONTENT
    )

    /** 样式变更后重算尺寸与位置。同一次调用里也负责「换了输出屏幕就重建窗口」。 */
    fun applyStyle() {
        if (view != null && attachedDisplayId != prefs.displayId) {
            hide()
            onRequestRender?.invoke()
            return
        }
        val current = view ?: run {
            syncHandle()
            onRequestRender?.invoke()
            return
        }
        current.applyStyle(prefs.style())
        applyLayoutLimits(current)
        syncHandle()
        current.post(placeTask)
    }

    fun resetPosition() {
        prefs.resetPosition()
        place(update = true)
    }

    private fun flagsFor(editableMode: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return if (editableMode) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            // 非编辑态两颗都挂上。NOT_TOUCHABLE 已经足够，但车厂 ROM 里出现过
            // 「只认 NOT_TOUCH_MODAL、仍按可触摸窗口走输入分发」的版本；同时挂上无副作用，
            // 少一个「歌词挡着点不到下面」的现场。
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    private fun buildParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsFor(editable),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

    private fun refreshWindowManager() {
        val id = prefs.displayId
        windowManager = try {
            if (id == Prefs.DEFAULT_DISPLAY_ID) {
                context.getSystemService(WindowManager::class.java)
            } else {
                val manager = context.getSystemService(DisplayManager::class.java)
                val display = manager?.getDisplay(id)
                if (display == null) {
                    context.getSystemService(WindowManager::class.java)
                } else {
                    context.createDisplayContext(display).getSystemService(WindowManager::class.java)
                }
            }
        } catch (_: Throwable) {
            context.getSystemService(WindowManager::class.java)
        }
    }

    /** 可用矩形：屏幕范围扣掉系统栏与额外留白。 */
    private fun frame(): OverlayGeometry.Frame {
        val metrics = context.resources.displayMetrics
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        try {
            val wm = windowManager?.currentWindowMetrics
            if (wm != null) {
                if (wm.bounds.width() > 0) width = wm.bounds.width()
                if (wm.bounds.height() > 0) height = wm.bounds.height()
                val insets = wm.windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                left = insets.left
                top = insets.top
                right = insets.right
                bottom = insets.bottom
            }
        } catch (_: Throwable) {
            // 部分 ROM 上 currentWindowMetrics 会抛异常：退回资源里的屏幕尺寸，仍可用
        }
        val pad = (EDGE_PAD_DP * density).toInt()
        return OverlayGeometry.frame(width, height, left, top, right, bottom, pad)
    }

    private fun maxWidthPx(box: OverlayGeometry.Frame): Int =
        (box.width * MAX_WIDTH_RATIO).toInt().coerceAtLeast((120 * density).toInt())

    /**
     * 把「宽度 / 高度比例」翻译成固定像素后交给渲染器；比例 0 表示自适应。
     *
     * 固定尺寸不能小于渲染器算出来的最小可用尺寸，否则字会被裁成半个。
     * 可用矩形只查一次并往下传：`currentWindowMetrics` 里带 insets 计算，
     * 一次布局里连查两遍纯属白花。
     */
    private fun applyLayoutLimits(renderer: LyricRenderer) {
        val box = frame()
        val style = prefs.style()
        val fixedW = if (style.widthRatio > 0f) {
            (box.width * style.widthRatio).toInt().coerceAtLeast(renderer.minWidthPx())
        } else {
            0
        }
        val fixedH = if (style.heightRatio > 0f) {
            (box.height * style.heightRatio).toInt().coerceAtLeast(renderer.minHeightPx())
        } else {
            0
        }
        renderer.setLayoutLimits(maxWidthPx(box), fixedW, fixedH)
    }

    private fun place(update: Boolean) {
        val current = view ?: return
        val lp = params ?: return
        val box = frame()
        val width = if (current.width > 0) current.width else current.measuredWidth
        val height = if (current.height > 0) current.height else current.measuredHeight
        val landscape = isLandscape()

        val x = OverlayGeometry.originX(box, width, prefs.ratioX(landscape))
        val y = OverlayGeometry.originY(box, height, prefs.ratioY(landscape))
        if (lp.x == x && lp.y == y) {
            if (width > 0 && height > 0) lastRect.set(x, y, x + width, y + height)
            placeHandle(box)
            return
        }

        lp.x = x
        lp.y = y
        if (update) {
            try {
                windowManager?.updateViewLayout(current, lp)
            } catch (_: Throwable) {
                // 窗口已失效：忽略
            }
        }
        if (width > 0 && height > 0) lastRect.set(x, y, x + width, y + height)
        placeHandle(box)
    }

    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun menuLabels(): List<String> = listOf(
        context.getString(R.string.edit_menu_lock),
        context.getString(R.string.edit_menu_hide),
        context.getString(R.string.edit_menu_settings),
        context.getString(R.string.edit_menu_copy)
    )

    private fun touchListener() = View.OnTouchListener { v, event ->
        val renderer = view ?: return@OnTouchListener false
        val lp = params

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (menuOpen) {
                    val index = renderer.hitMenu(event.y)
                    if (index >= 0) handleMenu(index) else closeMenu()
                    return@OnTouchListener true
                }
                if (renderer.hitDone(event.x, event.y)) {
                    setEditable(false)
                    onRequestRender?.invoke()
                    return@OnTouchListener true
                }
                if (lp == null) return@OnTouchListener false

                // 角上优先判定「改大小」：四角本来就是给缩放留的，且比拖动更不容易误触
                val corner = renderer.hitCorner(event.x, event.y)
                if (corner >= 0) {
                    resizeCorner = corner
                    resizing = false
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    resizeOrigin.set(lp.x, lp.y, lp.x + renderer.width, lp.y + renderer.height)
                    resizeW = renderer.width
                    resizeH = renderer.height
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(exitEdit)
                    return@OnTouchListener true
                }

                dragStartX = event.rawX
                dragStartY = event.rawY
                dragOriginX = lp.x
                dragOriginY = lp.y
                dragging = false
                dragFrame = frame()
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, LONG_PRESS_MS)
                handler.removeCallbacks(exitEdit)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (lp == null || menuOpen) return@OnTouchListener true

                if (resizeCorner >= 0) {
                    val dx = (event.rawX - resizeStartX).toInt()
                    val dy = (event.rawY - resizeStartY).toInt()
                    if (!resizing && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) resizing = true
                    if (!resizing) return@OnTouchListener true

                    val box = dragFrame
                    val minW = renderer.minWidthPx()
                    val minH = renderer.minHeightPx()
                    val originW = resizeOrigin.width()
                    val originH = resizeOrigin.height()
                    val leftEdge = resizeCorner == 0 || resizeCorner == 2
                    val topEdge = resizeCorner <= 1

                    val w = (if (leftEdge) originW - dx else originW + dx)
                        .coerceIn(minW, box.width.coerceAtLeast(minW))
                    val h = (if (topEdge) originH - dy else originH + dy)
                        .coerceIn(minH, box.height.coerceAtLeast(minH))
                    val x = if (leftEdge) resizeOrigin.left + (originW - w) else resizeOrigin.left
                    val y = if (topEdge) resizeOrigin.top + (originH - h) else resizeOrigin.top

                    lp.x = x.coerceIn(box.left, (box.right - w).coerceAtLeast(box.left))
                    lp.y = y.coerceIn(box.top, (box.bottom - h).coerceAtLeast(box.top))
                    resizeW = w
                    resizeH = h
                    renderer.previewSize(w, h)
                    try {
                        windowManager?.updateViewLayout(v, lp)
                    } catch (_: Throwable) {
                        return@OnTouchListener true
                    }
                    return@OnTouchListener true
                }

                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (!dragging && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (!dragging) return@OnTouchListener true

                val box = dragFrame
                lp.x = OverlayGeometry.clampOrigin(
                    dragOriginX + dx.toInt(), box.left, box.width - v.width
                )
                lp.y = OverlayGeometry.clampOrigin(
                    dragOriginY + dy.toInt(), box.top, box.height - v.height
                )
                try {
                    windowManager?.updateViewLayout(v, lp)
                } catch (_: Throwable) {
                    return@OnTouchListener true
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (menuOpen) return@OnTouchListener true
                if (lp == null) return@OnTouchListener true

                if (resizeCorner >= 0) {
                    val wasResizing = resizing
                    resizeCorner = -1
                    resizing = false
                    renderer.previewSize(0, 0)
                    if (wasResizing) persistSize()
                    handler.removeCallbacks(exitEdit)
                    armEditTimeout()
                    v.post(placeTask)
                    return@OnTouchListener true
                }

                if (dragging) {
                    val box = frame()
                    val spanX = box.width - v.width
                    val spanY = box.height - v.height
                    val threshold = (SNAP_DP * density).toInt()
                    lp.x = OverlayGeometry.snap(lp.x, box.left, spanX, threshold)
                    lp.y = OverlayGeometry.snap(lp.y, box.top, spanY, threshold)
                    try {
                        windowManager?.updateViewLayout(v, lp)
                    } catch (_: Throwable) {
                        // 落盘仍然继续，下次启动会重新钳制
                    }
                    prefs.setRatio(
                        OverlayGeometry.ratioOf(lp.x, box.left, spanX),
                        OverlayGeometry.ratioOf(lp.y, box.top, spanY),
                        isLandscape()
                    )
                    dragging = false
                }

                // 重新计时：一直在操作不该被「60 秒无操作」打断
                handler.removeCallbacks(exitEdit)
                armEditTimeout()
                true
            }

            else -> false
        }
    }

    /** 把拖出来的像素尺寸换算成比例落盘，并标记为自定义预设。 */
    private fun persistSize() {
        val box = frame()
        if (box.width <= 0 || box.height <= 0 || resizeW <= 0 || resizeH <= 0) return
        val style = prefs.style()
        prefs.saveStyle(
            style.copy(
                widthRatio = (resizeW.toFloat() / box.width)
                    .coerceIn(OverlayDefaults.WIDTH_MIN, OverlayDefaults.WIDTH_MAX),
                heightRatio = (resizeH.toFloat() / box.height)
                    .coerceIn(OverlayDefaults.HEIGHT_MIN, OverlayDefaults.HEIGHT_MAX),
                preset = Preset.CUSTOM
            )
        )
        applyStyle()
    }

    private fun openMenu() {
        val renderer = view ?: return
        if (!editable) return
        menuOpen = true
        handler.removeCallbacks(exitEdit)
        renderer.setContent(renderer.currentPrimary, emptyList(), null, LyricRenderer.Mode.MENU)
        renderer.post(placeTask)
    }

    private fun closeMenu() {
        menuOpen = false
        val renderer = view ?: return
        renderer.setContent(lastPrimary, lastSecondary, lastTitle, lastMode)
        handler.removeCallbacks(exitEdit)
        armEditTimeout()
        renderer.post(placeTask)
    }

    private fun handleMenu(index: Int) {
        when (index) {
            0 -> {
                menuOpen = false
                setEditable(false)
                onRequestRender?.invoke()
            }

            1 -> {
                menuOpen = false
                setEditable(false)
                hideFor(HIDE_FOR_MS)
            }

            2 -> {
                menuOpen = false
                setEditable(false)
                onOpenSettings?.invoke()
            }

            3 -> {
                copyCurrent()
                closeMenu()
            }

            else -> closeMenu()
        }
    }

    private fun copyCurrent() {
        val text = view?.currentPrimary ?: return
        try {
            val manager = context.getSystemService(ClipboardManager::class.java) ?: return
            manager.setPrimaryClip(ClipData.newPlainText("AnyLyric", text))
        } catch (_: Throwable) {
            // 部分车机把剪贴板服务裁掉了：静默失败，不影响歌词显示
        }
    }

    companion object {
        /** 边缘留白，避免贴着状态栏或屏幕边。 */
        private const val EDGE_PAD_DP = 10f

        /** 窗口最宽占可用宽度的比例。 */
        private const val MAX_WIDTH_RATIO = 0.92f

        /** 松手吸附阈值。 */
        private const val SNAP_DP = 24f

        /** 拖动判定阈值，避免手抖被当成拖动、误杀长按。 */
        private const val DRAG_SLOP = 8f

        /** 把手与歌词窗左缘的间隙。 */
        private const val HANDLE_GAP_DP = 4f

        /**
         * 「挂窗外」与「塞窗内」切换的迟滞带宽。
         *
         * 塞进窗内会让窗口变宽，而窗口按比例定位时左边缘会跟着左移，判断所用的
         * `windowLeft` 本身就在变；没有这条带宽就会在两种形态之间来回抖。
         */
        private const val HANDLE_HYSTERESIS_DP = 5f

        private const val LONG_PRESS_MS = 550L

        /**
         * 编辑态无操作自动退出。
         *
         * 编辑态是**唯一**会吃触摸的状态（拖位置、拖四角、点「完成」都靠它），
         * 所以这个值等价于「用户最多被挡多久」。原来 60 秒：调完尺寸顺手去导航上点一下，
         * 正好落在歌词框里就点不动，而且还要再等半分钟 —— 现场反馈就是「悬浮窗挡点击」。
         * 缩到 12 秒，且每次抬手都会重新计时（见 touchListener）。
         */
        private const val EDIT_TIMEOUT_MS = 12_000L

        private const val HIDE_FOR_MS = 10 * 60 * 1000L
    }
}
