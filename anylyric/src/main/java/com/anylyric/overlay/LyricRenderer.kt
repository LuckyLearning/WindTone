package com.anylyric.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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

    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = primarySize
    }

    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        val contentWidth = maxOf(primaryWidth, secondaryWidth)

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

        // 一行细阴影提升强光下的可读性（不用模糊，车机 GPU 合成代价高）
        val shadowOffset = 1.2f * density
        var baseline = paddingV + primarySize
        canvas.drawText(primary, paddingH + shadowOffset, baseline + shadowOffset, shadowPaint)
        canvas.drawText(primary, paddingH, baseline, primaryPaint)

        val second = secondary
        if (!second.isNullOrEmpty()) {
            baseline += secondarySize * 1.45f
            canvas.drawText(second, paddingH + shadowOffset, baseline + shadowOffset, shadowPaint)
            canvas.drawText(second, paddingH, baseline, secondaryPaint)
        }
    }
}
