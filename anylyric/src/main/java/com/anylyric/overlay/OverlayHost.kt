package com.anylyric.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.anylyric.data.Prefs

/**
 * 悬浮层的唯一出入口。
 *
 * 关键约定：
 *  · 默认 `FLAG_NOT_TOUCHABLE`，完全不拦截驾驶操作；
 *  · 只有编辑态才临时打开触摸（拖动、长按），退出编辑态立刻回到穿透；
 *  · 坐标只存 Int 快照，不持有 View / WindowManager 引用；
 *  · add / remove / update 全部吞掉异常：车厂 ROM 的窗口策略差异会导致
 *    BadTokenException / SecurityException，不能让悬浮窗把进程带崩。
 */
class OverlayHost(private val context: Context, private val prefs: Prefs) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var view: LyricRenderer? = null
    private var params: WindowManager.LayoutParams? = null
    private var editable = false

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragging = false

    fun isAttached(): Boolean = view?.isAttachedToWindow == true

    fun isMeasured(): Boolean {
        val v = view ?: return false
        return v.width > 0 && v.height > 0
    }

    fun show(primary: String, secondary: String?) {
        val current = view
        if (current == null) {
            val created = LyricRenderer(context)
            created.setTextSizeSp(prefs.fontSp)
            created.primary = primary
            created.secondary = secondary
            val layoutParams = buildParams()
            val added = try {
                windowManager?.addView(created, layoutParams)
                true
            } catch (_: Throwable) {
                false
            }
            if (!added) return
            view = created
            params = layoutParams
            return
        }

        current.primary = primary
        current.secondary = secondary
    }

    fun hide() {
        val current = view ?: return
        try {
            windowManager?.removeViewImmediate(current)
        } catch (_: Throwable) {
            // 窗口已失效：忽略
        }
        view = null
        params = null
        editable = false
        dragging = false
    }

    fun setEditable(value: Boolean) {
        editable = value
        val current = view ?: return
        val layoutParams = params ?: return
        layoutParams.flags = flagsFor(value)
        try {
            windowManager?.updateViewLayout(current, layoutParams)
        } catch (_: Throwable) {
            return
        }
        current.setOnTouchListener(if (value) touchListener() else null)
    }

    fun isEditable(): Boolean = editable

    fun refreshTextSize(sp: Float) {
        view?.setTextSizeSp(sp)
    }

    private fun flagsFor(editable: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (editable) {
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
            x = prefs.overlayX
            y = prefs.overlayY
        }

    /** 编辑态：拖动改坐标（落盘 Int 快照）。 */
    private fun touchListener() = View.OnTouchListener { v, event ->
        val layoutParams = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragOriginX = layoutParams.x
                dragOriginY = layoutParams.y
                dragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (!dragging && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                    dragging = true
                }
                if (dragging) {
                    layoutParams.x = dragOriginX + dx.toInt()
                    layoutParams.y = dragOriginY + dy.toInt()
                    try {
                        windowManager?.updateViewLayout(v, layoutParams)
                    } catch (_: Throwable) {
                        return@OnTouchListener true
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    prefs.overlayX = layoutParams.x
                    prefs.overlayY = layoutParams.y
                    dragging = false
                }
                true
            }

            else -> false
        }
    }
}
