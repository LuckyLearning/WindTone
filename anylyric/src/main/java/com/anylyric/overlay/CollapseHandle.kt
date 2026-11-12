package com.anylyric.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.anylyric.R

/**
 * 悬浮窗最左侧的收起 / 展开把手。
 *
 * 为什么是**独立小窗口**而不是画在歌词窗里：歌词窗默认 `FLAG_NOT_TOUCHABLE`
 * （完全不拦截驾驶操作），一旦为了这颗按钮把整窗改成可触摸，歌词区域就会变成一个
 * 挡手的黑洞。拆成独立窗口后，只有这颗 30×72dp 的窄条吃掉触摸，其余照旧穿透。
 *
 * 与 WindTone 车载歌词的侧钮同一思路：贴歌词窗左缘、竖向居中、点一下切换。
 */
class CollapseHandle(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** true = 已收起（此时箭头朝右，表示「点我展开」）。 */
    var collapsed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            contentDescription = context.getString(
                if (value) R.string.handle_expand else R.string.handle_collapse
            )
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.handle_collapse)
        // 车机上「点不动」有一部分是落点太小：把最小触摸尺寸写死在这里，
        // 视觉上仍是窄条，实际可点区域由宿主窗口尺寸决定（见 OverlayHost）。
        minimumWidth = (HANDLE_WIDTH_DP * density).toInt()
        minimumHeight = (HANDLE_HEIGHT_DP * density).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f

        bgPaint.color = 0xB314161A.toInt()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        borderPaint.color = 0x33FFFFFF
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 1f * density
        rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // 箭头用 Path 画：车机字体常缺 U+2039/U+276E 这类符号，靠字体就是赌运气。
        val cx = w / 2f
        val cy = h / 2f
        val arm = 4.2f * density
        val direction = if (collapsed) 1f else -1f
        chevron.color = 0xE6FFFFFF.toInt()
        chevron.style = Paint.Style.STROKE
        chevron.strokeWidth = 2f * density
        chevron.strokeCap = Paint.Cap.ROUND
        chevron.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(cx - arm * direction * 0.55f, cy - arm)
        path.lineTo(cx + arm * direction * 0.55f, cy)
        path.lineTo(cx - arm * direction * 0.55f, cy + arm)
        canvas.drawPath(path, chevron)
    }

    companion object {
        const val HANDLE_WIDTH_DP = 30f
        const val HANDLE_HEIGHT_DP = 72f
    }
}
