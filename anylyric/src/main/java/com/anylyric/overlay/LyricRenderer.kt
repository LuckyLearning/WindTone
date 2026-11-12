package com.anylyric.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/**
 * 单 View 绘制悬浮歌词。
 *
 * 三种形态共用同一个 View：
 *  · CONTENT —— 正常歌词（主行 + 副行，可折行）
 *  · PROMPT  —— 空状态提示（小一号、降透明度，避免和真歌词混在一起）
 *  · MENU    —— 编辑态长按出现的操作列表（自绘，不用 Dialog，车机上不抢焦点）
 *
 * 所有观感参数来自 [OverlayStyle]，这里只负责「把参数变成像素」，不做样式判断。
 */
class LyricRenderer(context: Context) : View(context) {

    enum class Mode { CONTENT, PROMPT, MENU }

    private val density = resources.displayMetrics.density

    /** sp → px。用 TypedValue 而不是 scaledDensity：后者在 API 34 起已废弃。 */
    private fun spToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics
    )

    private var style: OverlayStyle = OverlayDefaults.STYLE
    private var maxWidthPx = 0

    private var primaryText = ""
    private var secondaryText: String? = null

    var mode: Mode = Mode.CONTENT
        private set

    var editing: Boolean = false
        private set

    var playing: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var menuLabels: List<String> = emptyList()
    private var hintText: String = ""

    /**
     * 尺寸变化回调。
     *
     * 位置是按「窗口尺寸」算出来的，改字号、换行数、进出编辑态都会改变尺寸。
     * 只在 addView 之后 post 一次是不够的 —— post 可能先于第一次 layout 执行，
     * 那时 width 还是 0，窗口会先落在左上角再跳一下。
     */
    var sizeListener: ((Int, Int) -> Unit)? = null

    private val glyphs = ArrayList<Glyph>(4)
    private val glyphTops = ArrayList<Float>(4)

    private var contentWidth = 0f
    private var contentHeight = 0f
    private var menuItemH = 1f
    private var markGutter = 0f
    private var paddingH = 16f * density
    private var paddingV = 10f * density

    private class Glyph(val text: String, val size: Float, val alpha: Int, val bold: Boolean)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val measureNormal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val measureBold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val doneBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val path = Path()

    val currentPrimary: String get() = primaryText

    fun applyStyle(next: OverlayStyle) {
        style = OverlayDefaults.sanitize(next)
        rebuild()
    }

    /**
     * 宽度上限由宿主按「屏幕宽 − 安全边距」算出后传入。
     *
     * 不在 View 内部读 `displayMetrics.widthPixels`：多屏时那是最初 Display 的宽度，
     * 改字号后也不会重算，这正是旧实现长文案被裁断的原因。
     */
    fun setMaxWidth(px: Int) {
        if (px <= 0 || px == maxWidthPx) return
        maxWidthPx = px
        rebuild()
    }

    fun setMenuLabels(labels: List<String>) {
        if (labels == menuLabels) return
        menuLabels = labels
        if (mode == Mode.MENU) rebuild()
    }

    fun setHint(text: String) {
        if (text == hintText) return
        hintText = text
        if (editing) rebuild()
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        rebuild()
    }

    fun setContent(primary: String, secondary: String?, nextMode: Mode) {
        val changed = primary != primaryText || secondary != secondaryText || nextMode != mode
        primaryText = primary
        secondaryText = secondary
        mode = nextMode
        if (!changed) return
        rebuild()
        fadeIn()
    }

    /**
     * 行切换的淡入。用 View 的 alpha 而不是自己画透明度：合成层做这件事几乎零成本，
     * 而且不触碰测量与绘制流程。编辑态与菜单不淡入，避免操作时界面在闪。
     */
    private fun fadeIn() {
        if (style.fadeMs <= 0 || editing || mode != Mode.CONTENT || !isAttachedToWindow) {
            alpha = 1f
            return
        }
        animate().cancel()
        alpha = 0.25f
        animate().alpha(1f).setDuration(style.fadeMs.toLong()).start()
    }

    private fun rebuild() {
        style = OverlayDefaults.sanitize(style)

        val primarySize = spToPx(style.fontSp)
        val secondarySize = primarySize * 0.72f
        val promptSize = primarySize * 0.74f
        paddingH = 16f * density
        paddingV = 10f * density
        markGutter = if (style.stateMark && mode == Mode.CONTENT) 10f * density else 0f

        val limit = contentLimit()
        glyphs.clear()
        glyphTops.clear()

        var y = 0f
        when (mode) {
            Mode.MENU -> {
                val size = primarySize * 0.86f
                menuItemH = size * 2.1f
                for (label in menuLabels) {
                    glyphs += Glyph(label, size, 235, false)
                    glyphTops += y
                    y += menuItemH
                }
            }

            Mode.PROMPT -> {
                val lines = TextWrap.wrap(primaryText, limit, 2) { s ->
                    widthOf(s, promptSize, false)
                }
                val gap = promptSize * 1.42f
                for (line in lines) {
                    glyphs += Glyph(line, promptSize, 185, false)
                    glyphTops += y
                    y += gap
                }
                val sub = secondaryText
                if (!sub.isNullOrBlank() && lines.size < 2) {
                    val subSize = promptSize * 0.88f
                    val subLines = TextWrap.wrap(sub, limit, 1) { s -> widthOf(s, subSize, false) }
                    for (line in subLines) {
                        glyphs += Glyph(line, subSize, 135, false)
                        glyphTops += y
                        y += subSize * 1.45f
                    }
                }
            }

            Mode.CONTENT -> {
                val lines = TextWrap.wrap(primaryText, limit, style.maxLines) { s ->
                    widthOf(s, primarySize, style.bold)
                }
                for (line in lines) {
                    glyphs += Glyph(line, primarySize, 255, style.bold)
                    glyphTops += y
                    y += primarySize * 1.38f
                }
                val remaining = style.maxLines - lines.size
                val sub = secondaryText
                if (remaining >= 1 && !sub.isNullOrBlank()) {
                    val subLines = TextWrap.wrap(sub, limit, remaining) { s ->
                        widthOf(s, secondarySize, false)
                    }
                    for (line in subLines) {
                        glyphs += Glyph(
                            line, secondarySize,
                            (style.secondaryAlpha * 255f).toInt().coerceIn(0, 255), false
                        )
                        glyphTops += y
                        y += secondarySize * 1.45f
                    }
                }
            }
        }

        contentHeight = y

        var width = glyphs.maxOfOrNull { widthOf(it.text, it.size, it.bold) } ?: 0f
        // 最小宽度：四个字。避免「我」这样的单字歌词变成一个孤零零的小方块。
        width = maxOf(width, widthOf("字字字字", primarySize, style.bold))
        if (editing && mode == Mode.CONTENT) {
            width = maxOf(width, widthOf(hintText, spToPx(11f), false) + 54f * density)
        }
        contentWidth = width.coerceAtMost(limit)

        requestLayout()
        invalidate()
    }

    private fun contentLimit(): Float {
        val limitBase = if (maxWidthPx > 0) maxWidthPx.toFloat() - paddingH * 2f else 4000f
        return (limitBase - markGutter).coerceAtLeast(60f * density)
    }

    private fun widthOf(text: String, size: Float, bold: Boolean): Float {
        val paint = if (bold) measureBold else measureNormal
        paint.textSize = size
        return paint.measureText(text)
    }

    private fun radius(): Float = style.cornerDp * density

    private fun hintHeight(): Float = 28f * density

    private fun doneRadius(): Float = 10f * density

    private fun doneCx(): Float = width - 13f * density

    private fun doneCy(): Float = height - hintHeight() / 2f

    fun hitDone(x: Float, y: Float): Boolean {
        if (!editing || mode == Mode.MENU) return false
        val dx = x - doneCx()
        val dy = y - doneCy()
        val r = doneRadius() * 1.8f
        return dx * dx + dy * dy <= r * r
    }

    /** 返回被点的菜单项序号；不在菜单模式或落在空白处返回 -1。 */
    fun hitMenu(y: Float): Int {
        if (mode != Mode.MENU) return -1
        val index = ((y - paddingV) / menuItemH).toInt()
        return if (index in glyphs.indices) index else -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hint = if (editing && mode == Mode.CONTENT) hintHeight() else 0f
        val w = (contentWidth + paddingH * 2f + markGutter).toInt().coerceIn(1, 4000)
        val h = (contentHeight + paddingV * 2f + hint).toInt().coerceIn(1, 2000)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (mode == Mode.MENU) {
            bgPaint.color = 0xF014161A.toInt()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius(), radius(), bgPaint)
            drawMenu(canvas)
            return
        }

        if (style.opacity > 0.02f) {
            val a = (style.opacity * 255f).toInt().coerceIn(0, 255)
            bgPaint.color = 0x0014161A or (a shl 24)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius(), radius(), bgPaint)
        }

        drawStateMark(canvas, h)
        drawGlyphs(canvas)

        if (editing) {
            drawEditFrame(canvas, w, h)
            drawHintBar(canvas, w, h)
        }
    }

    private fun drawGlyphs(canvas: Canvas) {
        val baseX = paddingH + markGutter
        for (i in glyphs.indices) {
            val glyph = glyphs[i]
            configureText(glyph)
            val baseline = paddingV + glyphTops[i] - textPaint.fontMetrics.ascent
            val glyphWidth = widthOf(glyph.text, glyph.size, glyph.bold)
            val x = when (style.align) {
                Align.START -> baseX
                Align.CENTER -> baseX + (contentWidth - glyphWidth) / 2f
                Align.END -> baseX + contentWidth - glyphWidth
            }
            // 描边先画、填充后画：这是真轮廓，不是把文字偏移复制一遍当阴影。
            if (style.stroke) canvas.drawText(glyph.text, x, baseline, strokePaint)
            canvas.drawText(glyph.text, x, baseline, textPaint)
        }
    }

    private fun drawMenu(canvas: Canvas) {
        val baseX = paddingH * 1.4f
        for (i in glyphs.indices) {
            val glyph = glyphs[i]
            configureText(glyph)
            val fm = textPaint.fontMetrics
            val baseline = paddingV + glyphTops[i] + menuItemH / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(glyph.text, baseX, baseline, textPaint)

            if (i < glyphs.lastIndex) {
                dividerPaint.color = 0x22FFFFFF
                val y = paddingV + glyphTops[i] + menuItemH
                canvas.drawRect(paddingH, y, width - paddingH, y + 1f * density, dividerPaint)
            }
        }
    }

    private fun drawStateMark(canvas: Canvas, h: Float) {
        if (!style.stateMark || mode != Mode.CONTENT) return
        val barW = 3f * density
        val left = paddingH
        val top = paddingV * 0.7f
        val bottom = h - paddingV * 0.7f - (if (editing) hintHeight() else 0f)
        markPaint.color = if (playing) 0xFF7FD1A4.toInt() else 0x55FFFFFF
        rect.set(left, top, left + barW, bottom)
        canvas.drawRoundRect(rect, barW / 2f, barW / 2f, markPaint)
    }

    private fun drawEditFrame(canvas: Canvas, w: Float, h: Float) {
        val inset = 1.5f * density
        dashPaint.color = 0x99FFFFFF.toInt()
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = 1.2f * density
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius(), radius(), dashPaint)

        handlePaint.color = 0xE6FFFFFF.toInt()
        val r = 3.2f * density
        val pad = 3f * density
        for (cx in floatArrayOf(pad, w - pad)) {
            for (cy in floatArrayOf(pad, h - pad)) {
                canvas.drawCircle(cx, cy, r, handlePaint)
            }
        }
    }

    private fun drawHintBar(canvas: Canvas, w: Float, h: Float) {
        val barH = hintHeight()
        val top = h - barH

        dividerPaint.color = 0x33FFFFFF
        canvas.drawRect(paddingH, top, w - paddingH, top + 1f * density, dividerPaint)

        hintPaint.textSize = spToPx(11f)
        hintPaint.color = 0xCCFFFFFF.toInt()
        val fm = hintPaint.fontMetrics
        val baseline = top + barH / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(hintText, paddingH, baseline, hintPaint)

        val cx = doneCx()
        val cy = top + barH / 2f
        val r = doneRadius()
        doneBgPaint.color = 0xFF1D9E75.toInt()
        canvas.drawCircle(cx, cy, r, doneBgPaint)

        // 勾用 Path 画，不依赖字体里有没有 U+2713（车机字体常常缺字）。
        checkPaint.color = Color.WHITE
        checkPaint.style = Paint.Style.STROKE
        checkPaint.strokeWidth = 2f * density
        checkPaint.strokeCap = Paint.Cap.ROUND
        checkPaint.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(cx - r * 0.40f, cy + r * 0.02f)
        path.lineTo(cx - r * 0.08f, cy + r * 0.33f)
        path.lineTo(cx + r * 0.42f, cy - r * 0.30f)
        canvas.drawPath(path, checkPaint)
    }

    private fun configureText(glyph: Glyph) {
        val typeface = if (glyph.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.textSize = glyph.size
        textPaint.typeface = typeface
        textPaint.color = Color.WHITE
        textPaint.alpha = glyph.alpha

        strokePaint.textSize = glyph.size
        strokePaint.typeface = typeface
        strokePaint.color = Color.BLACK
        strokePaint.alpha = glyph.alpha
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = glyph.size * 0.085f
    }
}
