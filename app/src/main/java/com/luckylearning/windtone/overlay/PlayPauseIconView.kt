package com.luckylearning.windtone.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 悬浮窗左侧「播放 / 暂停」键的图标（v1.2.0）。
 *
 * ## 为什么自己画，不用文字符号
 *
 * 车机 ROM 的字体裁得很狠：`⏸`（U+23F8）这类符号在 DiLink 上直接是豆腐块，
 * `▮▮` 也时有时无。用 Canvas 画三角形 / 两条竖条则与字体无关，任何机器都一样。
 *
 * ## 样式参考（用户给的截图）
 *
 * 截图里那排控制键是**白色图标 + 一圈细描边**，暂停键用圆圈强调，不用实心大色块。
 * 这里照同一套：深色半透明底 + 白色细环 + 白色图标，压在任意画面上都看得清，
 * 又不会像实心圆那样抢走歌词的视线。
 */
internal class PlayPauseIconView(context: Context) : View(context) {

    private var playing = false

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
        color = Color.WHITE
    }
    private val path = Path()

    /** 播放态：显示「暂停」两条竖条；暂停态：显示「播放」三角形 */
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        contentDescription = if (value) "暂停" else "播放"
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

        val unit = radius * 0.52f
        if (playing) {
            // 两条竖条（暂停）
            val barW = unit * 0.34f
            val barH = unit * 1.5f
            val gap = unit * 0.36f
            canvas.drawRoundRect(
                cx - gap - barW, cy - barH / 2f,
                cx - gap, cy + barH / 2f,
                barW / 2f, barW / 2f, iconPaint,
            )
            canvas.drawRoundRect(
                cx + gap, cy - barH / 2f,
                cx + gap + barW, cy + barH / 2f,
                barW / 2f, barW / 2f, iconPaint,
            )
        } else {
            // 三角形（播放）：略向右偏 1dp，视觉重心才在圆心
            val half = unit * 0.86f
            path.reset()
            path.moveTo(cx - half * 0.72f + dp(1f), cy - half)
            path.lineTo(cx + half * 0.95f + dp(1f), cy)
            path.lineTo(cx - half * 0.72f + dp(1f), cy + half)
            path.close()
            canvas.drawPath(path, iconPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** 深色半透明底：与歌词底板同族，压在任何画面上都有对比 */
        const val DISC_COLOR = 0x59000000

        /** 细白环：参考截图里暂停键的那一圈描边 */
        const val RING_COLOR = 0x99FFFFFF.toInt()
    }
}
