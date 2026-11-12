package com.anylyric.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
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
 *  · 默认 `FLAG_NOT_TOUCHABLE`，完全不拦截驾驶操作；只有编辑态才临时打开触摸；
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

    private var editable = false
    private var menuOpen = false
    private var playing = true

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragging = false

    private var hiddenUntil = 0L
    private var attachedDisplayId = Prefs.DEFAULT_DISPLAY_ID

    private var lastPrimary = ""
    private var lastSecondary: String? = null
    private var lastMode = LyricRenderer.Mode.CONTENT

    private val handler = Handler(Looper.getMainLooper())

    /** 上层重渲染入口：菜单关闭、隐藏到期、编辑态需要重建窗口时回调。 */
    var onRequestRender: (() -> Unit)? = null

    /** 打开设置页。用回调而不是直接 startActivity，避免 overlay 反向依赖 ui 包。 */
    var onOpenSettings: (() -> Unit)? = null

    private val exitEdit = Runnable { setEditable(false) }

    private val longPress = Runnable {
        if (editable && !dragging && !menuOpen) openMenu()
    }

    private val hideExpired = Runnable {
        hiddenUntil = 0L
        onRequestRender?.invoke()
    }

    fun isAttached(): Boolean = view?.isAttachedToWindow == true

    fun isMeasured(): Boolean {
        val v = view ?: return false
        return v.width > 0 && v.height > 0
    }

    fun isEditable(): Boolean = editable

    fun showContent(primary: String, secondary: String?) =
        show(primary, secondary, LyricRenderer.Mode.CONTENT)

    /** 空状态提示：小一号、降透明度，和真歌词区分开。 */
    fun showPrompt(primary: String, secondary: String?) =
        show(primary, secondary, LyricRenderer.Mode.PROMPT)

    private fun show(primary: String, secondary: String?, mode: LyricRenderer.Mode) {
        // 「隐藏 10 分钟」期间的任何刷新都不该把窗口又拉回来
        if (SystemClock.elapsedRealtime() < hiddenUntil) return

        val changed = primary != lastPrimary || secondary != lastSecondary || mode != lastMode
        lastPrimary = primary
        lastSecondary = secondary
        lastMode = mode

        // 菜单打开期间，播放回调仍会每几百毫秒推一次内容。只更新「待恢复的内容」，
        // 不动当前界面 —— 否则菜单会被下一句歌词顶掉，用户根本点不到。
        if (menuOpen) return

        val current = view
        if (current == null) {
            addView(primary, secondary, mode)
            return
        }
        current.playing = playing
        // 内容没变就不重绘、不重新排位置：播放器每 250ms 推一次状态，
        // 每句歌词能触发三四次刷新，白跑一次 layout 就是白烧一次车机 GPU。
        if (!changed) return
        current.setContent(primary, secondary, mode)
        current.post { place(update = true) }
    }

    private fun addView(primary: String, secondary: String?, mode: LyricRenderer.Mode): Boolean {
        refreshWindowManager()

        val renderer = LyricRenderer(context).apply {
            setMenuLabels(menuLabels())
            setHint(context.getString(R.string.edit_hint_bar))
            applyStyle(prefs.style())
            setMaxWidth(maxWidthPx())
            setEditing(editable)
            setContent(primary, secondary, mode)
        }
        renderer.playing = playing
        // 在 layout 期间直接改窗口位置不安全，延到下一轮消息再摆
        renderer.sizeListener = { _, _ -> renderer.post { place(update = true) } }

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
        renderer.post { place(update = true) }
        return true
    }

    fun hide() {
        val current = view ?: return
        handler.removeCallbacks(exitEdit)
        try {
            windowManager?.removeViewImmediate(current)
        } catch (_: Throwable) {
            // 窗口已失效：忽略
        }
        view = null
        params = null
        editable = false
        menuOpen = false
        dragging = false
    }

    /** 临时隐藏（长按菜单「隐藏 10 分钟」），到期后回调上层重新渲染。 */
    fun hideFor(durationMs: Long) {
        hiddenUntil = SystemClock.elapsedRealtime() + durationMs
        hide()
        handler.removeCallbacks(hideExpired)
        handler.postDelayed(hideExpired, durationMs)
    }

    fun setPlaying(value: Boolean) {
        playing = value
        view?.playing = value
    }

    fun setEditable(value: Boolean) {
        handler.removeCallbacks(exitEdit)
        val wasMenu = menuOpen
        menuOpen = false

        val current = view
        val lp = params
        if (current == null || lp == null) {
            editable = value
            // 还没挂上窗口（无会话 / 刚被移除）：让上层重新渲染一次，由 show 决定是否创建
            if (value) onRequestRender?.invoke()
            return
        }

        lp.flags = flagsFor(value)
        try {
            windowManager?.updateViewLayout(current, lp)
        } catch (_: Throwable) {
            // 更新失败就保持原状：贸然把 editable 置真会留下「能点但拦不到触摸」的错位状态
            return
        }

        editable = value
        current.setOnTouchListener(if (value) touchListener() else null)
        current.setEditing(value)
        if (value) {
            handler.postDelayed(exitEdit, EDIT_TIMEOUT_MS)
        } else {
            // 退出编辑态时可能正停在菜单上：这里必须主动把内容换回来。
            // 仅靠回调上层是不够的 —— 内容没变时 show() 会走「无变化直接返回」的快路径，
            // 结果就是留下一个「看得见但点不动」的菜单。
            if (wasMenu || current.mode == LyricRenderer.Mode.MENU) {
                current.setContent(lastPrimary, lastSecondary, lastMode)
            }
            onRequestRender?.invoke()
        }
        current.post { place(update = true) }
    }

    /** 样式变更后重算尺寸与位置。同一次调用里也负责「换了输出屏幕就重建窗口」。 */
    fun applyStyle() {
        if (view != null && attachedDisplayId != prefs.displayId) {
            hide()
            onRequestRender?.invoke()
            return
        }
        val current = view ?: run {
            onRequestRender?.invoke()
            return
        }
        current.applyStyle(prefs.style())
        current.setMaxWidth(maxWidthPx())
        current.post { place(update = true) }
    }

    fun setAnchor(col: Int, row: Int) {
        prefs.setRatio(
            OverlayGeometry.anchorRatio(col),
            OverlayGeometry.anchorRatio(row),
            isLandscape()
        )
        place(update = true)
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
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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

    private fun maxWidthPx(): Int =
        (frame().width * MAX_WIDTH_RATIO).toInt().coerceAtLeast((120 * density).toInt())

    private fun place(update: Boolean) {
        val current = view ?: return
        val lp = params ?: return
        val box = frame()
        val width = if (current.width > 0) current.width else current.measuredWidth
        val height = if (current.height > 0) current.height else current.measuredHeight
        val landscape = isLandscape()

        val x = OverlayGeometry.originX(box, width, prefs.ratioX(landscape))
        val y = OverlayGeometry.originY(box, height, prefs.ratioY(landscape))
        if (lp.x == x && lp.y == y) return

        lp.x = x
        lp.y = y
        if (update) {
            try {
                windowManager?.updateViewLayout(current, lp)
            } catch (_: Throwable) {
                // 窗口已失效：忽略
            }
        }
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

                dragStartX = event.rawX
                dragStartY = event.rawY
                dragOriginX = lp.x
                dragOriginY = lp.y
                dragging = false
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, LONG_PRESS_MS)
                handler.removeCallbacks(exitEdit)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (lp == null || menuOpen) return@OnTouchListener true
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (!dragging && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (!dragging) return@OnTouchListener true

                val box = frame()
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
                handler.postDelayed(exitEdit, EDIT_TIMEOUT_MS)
                true
            }

            else -> false
        }
    }

    private fun openMenu() {
        val renderer = view ?: return
        if (!editable) return
        menuOpen = true
        handler.removeCallbacks(exitEdit)
        renderer.setContent(renderer.currentPrimary, null, LyricRenderer.Mode.MENU)
        renderer.post { place(update = true) }
    }

    private fun closeMenu() {
        menuOpen = false
        val renderer = view ?: return
        renderer.setContent(lastPrimary, lastSecondary, lastMode)
        handler.removeCallbacks(exitEdit)
        handler.postDelayed(exitEdit, EDIT_TIMEOUT_MS)
        renderer.post { place(update = true) }
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

        private const val LONG_PRESS_MS = 550L

        /** 编辑态无操作自动退出：不退出会一直挡着触摸，用户会以为屏幕坏了。 */
        private const val EDIT_TIMEOUT_MS = 60_000L

        private const val HIDE_FOR_MS = 10 * 60 * 1000L
    }
}
