package com.anylyric.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * 单 View 绘制两行歌词。
 *
 * 不用 TextView / Compose：一个 View + 两个 Paint 就够，避免视图树与主题递归；
 * 只在文本变化时 requestLayout + invalidate，没有动画、没有封面位图。
 */
class LyricRenderer(context: Context) : View(context) {

    var primary: String = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var secondary: String? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var primarySize = 22f * scaledDensity
    private var secondarySize = primarySize * 0.72f

    private val primaryPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = primarySize
    }

    private val secondaryPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = secondarySize
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
    }

    private val rect = RectF()
    private val paddingH = 16f * density
    private val paddingV = 10f * density
    private val radius = 12f * density

    /**
     * 宽度上限：车机屏幕窄，长句（尤其「暂无歌词（该播放器未暴露歌词通道）」这类
     * 提示语）会一路顶到屏幕外被裁断。这里按屏宽留出边距，超长部分省略号收尾。
     */
    private val maxTotalWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtLeast(1)

    fun setTextSizeSp(sp: Float) {
        primarySize = sp * scaledDensity
        secondarySize = primarySize * 0.72f
        primaryPaint.textSize = primarySize
        secondaryPaint.textSize = secondarySize
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val primaryWidth = primaryPaint.measureText(primary)
        val secondaryWidth = secondary?.let { secondaryPaint.measureText(it) } ?: 0f
        val limit = (maxTotalWidth - paddingH * 2).coerceAtLeast(1f)
        val contentWidth = minOf(maxOf(primaryWidth, secondaryWidth), limit)

        val primaryLine = primarySize * 1.24f
        val secondaryLine = if (secondary.isNullOrEmpty()) 0f else secondarySize * 1.45f
        val contentHeight = primaryLine + secondaryLine

        setMeasuredDimension(
            (contentWidth + paddingH * 2).toInt().coerceIn(1, 4000),
            (contentHeight + paddingV * 2).toInt().coerceIn(1, 1000)
        )
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        if (primary.isEmpty()) return

        val available = (width - paddingH * 2).coerceAtLeast(1f)
        val shown = TextUtils.ellipsize(primary, primaryPaint, available, TextUtils.TruncateAt.END)

        // 一行细阴影提升强光下的可读性（不用模糊，车机 GPU 合成代价高）
        val shadowOffset = 1.2f * density
        var baseline = paddingV + primarySize
        canvas.drawText(shown.toString(), paddingH + shadowOffset, baseline + shadowOffset, shadowPaint)
        canvas.drawText(shown, 0, shown.length, paddingH, baseline, primaryPaint)

        val second = secondary
        if (!second.isNullOrEmpty()) {
            baseline += secondarySize * 1.45f
            val shownSecond = TextUtils.ellipsize(second, secondaryPaint, available, TextUtils.TruncateAt.END)
            canvas.drawText(
                shownSecond.toString(), paddingH + shadowOffset, baseline + shadowOffset, shadowPaint
            )
            canvas.drawText(shownSecond, 0, shownSecond.length, paddingH, baseline, secondaryPaint)
        }
    }
}
