package com.luckylearning.windtone.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.luckylearning.windtone.R

/**
 * 悬浮窗侧钮「无障碍状态」（对齐播放页前台识别钮语义）。
 *
 * - 图标色：已开启 = 绿；未开启 = 红（一眼可辨）
 * - 点击不在进程内假开关，由服务跳系统无障碍页（与设置页 / 播放页同一通道）
 * - 自绘而非 emoji：车机字体常把无障碍符号裁成豆腐块
 */
internal class AccessibilityIconView(context: Context) : View(context) {

    private var a11yOn = false

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DISC_COLOR
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = RING_COLOR
        strokeWidth = dp(1.5f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_OFF
    }
    private val path = Path()

    fun setA11yEnabled(value: Boolean) {
        if (a11yOn == value) return
        a11yOn = value
        iconPaint.color = if (value) COLOR_ON else COLOR_OFF
        contentDescription = context.getString(
            if (value) R.string.cd_a11y_on else R.string.cd_a11y_off,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(cx, cy) - dp(1f)

        canvas.drawCircle(cx, cy, radius, discPaint)
        canvas.drawCircle(cx, cy, radius - dp(1f), ringPaint)

        // Material Accessibility 简化：圆头 + 横臂 + 躯干（相对 24dp viewport 缩放）
        val s = radius * 2f / 24f
        val ox = cx - 12f * s
        val oy = cy - 12f * s

        canvas.drawCircle(ox + 12f * s, oy + 4f * s, 2.1f * s, iconPaint)

        path.reset()
        path.moveTo(ox + 3f * s, oy + 7f * s)
        path.lineTo(ox + 21f * s, oy + 7f * s)
        path.lineTo(ox + 21f * s, oy + 9f * s)
        path.lineTo(ox + 15f * s, oy + 9f * s)
        path.lineTo(ox + 15f * s, oy + 22f * s)
        path.lineTo(ox + 13f * s, oy + 22f * s)
        path.lineTo(ox + 13f * s, oy + 16f * s)
        path.lineTo(ox + 11f * s, oy + 16f * s)
        path.lineTo(ox + 11f * s, oy + 22f * s)
        path.lineTo(ox + 9f * s, oy + 22f * s)
        path.lineTo(ox + 9f * s, oy + 9f * s)
        path.lineTo(ox + 3f * s, oy + 9f * s)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val DISC_COLOR = 0x59000000
        val RING_COLOR = 0x99FFFFFF.toInt()
        val COLOR_ON = 0xFF66BB6A.toInt()
        val COLOR_OFF = 0xFFEF5350.toInt()
    }
}