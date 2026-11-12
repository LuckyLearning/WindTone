package com.anylyric.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator

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

    /**
     * 固定宽高（px）。0 表示自适应。
     *
     * 由宿主按 `frame × 比例` 算好后传进来 —— View 内部不去读 displayMetrics，
     * 多屏时那是最初 Display 的尺寸，改字号也不会重算。
     */
    private var fixedWidthPx = 0
    private var fixedHeightPx = 0

    /** 拖角改尺寸期间的临时尺寸，松手后由样式比例接管。 */
    private var previewW = 0
    private var previewH = 0

    private var primaryText = ""
    private var secondaryText: String? = null

    /**
     * 左侧内衬（px）。
     *
     * 收起把手是一颗独立小窗，正常情况下挂在歌词窗左边、与窗口没有重叠。
     * 但歌词窗被拖到屏幕最左边时，窗外已经没有位置 —— 把手只能落到窗口范围内。
     * 这时由宿主把它塞进这条内衬里：文字整体右移一条把手宽，按钮就不会压住歌词。
     * 0 表示不需要内衬（把手在窗外，或把手被关掉／处于编辑态）。
     */
    private var leftInsetPx = 0

    /** 文字起始横坐标：左内边距 + 内衬。 */
    private val textLeft: Float get() = paddingH + leftInsetPx

    var mode: Mode = Mode.CONTENT
        private set

    var editing: Boolean = false
        private set


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

    /** 交叉淡入用的「上一帧内容」快照。 */
    private val fadeGlyphs = ArrayList<Glyph>(4)
    private val fadeTops = ArrayList<Float>(4)
    private var fadeTop = 0f
    private var fadeCount = 0
    private var fadeElapsed = 1f

    private var contentWidth = 0f
    private var contentHeight = 0f
    private var contentTop = 0f
    private var menuItemH = 1f
    private var paddingH = 16f * density
    private var paddingV = 10f * density

    /**
     * [secondary] 标记「其它行」：颜色取 style.secondaryColor，不透明度再乘 secondaryAlpha。
     *
     * [width] 在构造时算好并跟着对象走：绘制时每帧再量一次 `measureText` 是纯浪费，
     * 而换行 / 换字号都必然重建 Glyph，宽度不会走样。
     */
    private class Glyph(
        val text: String,
        val size: Float,
        val alpha: Int,
        val bold: Boolean,
        val width: Float,
        val secondary: Boolean = false
    )

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val measureNormal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val measureBold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val doneBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val path = Path()

    /**
     * 交叉淡入动画。
     *
     * 刻意**不再**改窗口的 alpha：那会让整块 surface 重新合成，在车机上就是掉帧与卡顿。
     * 现在只在本 View 里多画一次旧文本，走的是同一张画的两次 drawText，代价可以忽略。
     */
    private var fadeAnimator: ValueAnimator? = null

    val currentPrimary: String get() = primaryText

    fun applyStyle(next: OverlayStyle) {
        style = OverlayDefaults.sanitize(next)
        rebuild()
    }

    /**
     * 宽度上限与固定宽高。宿主每次布局都调一次，值没变时是空操作。
     */
    fun setLayoutLimits(maxWidth: Int, fixedWidth: Int, fixedHeight: Int) {
        val nextMax = maxWidth.coerceAtLeast(0)
        val nextW = fixedWidth.coerceAtLeast(0)
        val nextH = fixedHeight.coerceAtLeast(0)
        if (nextMax == maxWidthPx && nextW == fixedWidthPx && nextH == fixedHeightPx) return
        maxWidthPx = nextMax
        fixedWidthPx = nextW
        fixedHeightPx = nextH
        rebuild()
    }

    /**
     * 左侧内衬。宿主在摆把手时决定是否要让出这条宽度；值没变时是空操作，
     * 因此可以每次布局都调一次。
     */
    fun setLeftInset(px: Int) {
        val next = px.coerceAtLeast(0)
        if (next == leftInsetPx) return
        leftInsetPx = next
        rebuild()
    }

    /** 拖角过程中的实时尺寸。传 0,0 取消临时尺寸。 */
    fun previewSize(w: Int, h: Int) {
        if (w == previewW && h == previewH) return
        previewW = w.coerceAtLeast(0)
        previewH = h.coerceAtLeast(0)
        requestLayout()
        invalidate()
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
        if (!value) stopFade()
        rebuild()
    }

    fun setContent(primary: String, secondary: String?, nextMode: Mode) {
        val changed = primary != primaryText || secondary != secondaryText || nextMode != mode
        if (!changed) return

        // 只有「真歌词换成真歌词」才值得做交叉淡入：提示语、菜单、编辑态都不淡入，
        // 否则操作过程中界面一直在闪。
        val crossfade = nextMode == Mode.CONTENT &&
            mode == Mode.CONTENT &&
            !editing &&
            style.fadeMs > 0 &&
            glyphs.isNotEmpty()

        if (crossfade) snapshotForFade()
        primaryText = primary
        secondaryText = secondary
        mode = nextMode
        rebuild()
        if (crossfade) {
            startFade()
        } else if (fadeCount > 0) {
            // 正在淡入时切到菜单/提示：动画立刻收尾，别让旧行的残影压在菜单上
            stopFade()
            invalidate()
        }
    }

    // ---------------------------------------------------------------- 交叉淡入

    private fun snapshotForFade() {
        fadeGlyphs.clear()
        fadeGlyphs.addAll(glyphs)
        fadeTops.clear()
        fadeTops.addAll(glyphTops)
        fadeTop = contentTop
        fadeCount = glyphs.size
    }

    private fun startFade() {
        val animator = fadeAnimator ?: ValueAnimator().also { fadeAnimator = it }
        // 先摘监听器再 cancel：cancel 会同步回调上一轮的 onAnimationEnd，而那个回调会
        // 清空 fade 快照 —— 顺序反了的话，上一轮还没放完时紧跟着换行就没有淡入了。
        // 另外两类监听器都要清：只清 end/cancel 不清 update 的话，每换一句就多挂一个
        // 更新回调（放一小时歌挂上千个），之后每一帧都被上千个 lambda 各调一次 invalidate。
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()
        animator.setFloatValues(0f, 1f)
        animator.duration = style.fadeMs.toLong()
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener {
            fadeElapsed = it.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                fadeElapsed = 1f
                fadeCount = 0
                fadeGlyphs.clear()
                fadeTops.clear()
                invalidate()
            }
        })
        fadeElapsed = 0f
        animator.start()
    }

    private fun stopFade() {
        fadeAnimator?.cancel()
        fadeCount = 0
        fadeElapsed = 1f
        fadeGlyphs.clear()
        fadeTops.clear()
    }

    override fun onDetachedFromWindow() {
        stopFade()
        super.onDetachedFromWindow()
    }

    private fun rebuild() {
        style = OverlayDefaults.sanitize(style)

        val primarySize = spToPx(style.fontSp)
        val secondarySize = primarySize * 0.72f
        val promptSize = primarySize * 0.74f
        paddingH = 16f * density
        paddingV = 10f * density

        val limit = contentLimit()
        glyphs.clear()
        glyphTops.clear()

        var y = 0f
        when (mode) {
            Mode.MENU -> {
                val size = primarySize * 0.86f
                menuItemH = size * 2.1f
                for (label in menuLabels) {
                    glyphs += glyph(label, size, 235, false)
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
                    glyphs += glyph(line, promptSize, 185, false)
                    glyphTops += y
                    y += gap
                }
                val sub = secondaryText
                if (!sub.isNullOrBlank() && lines.size < 2) {
                    val subSize = promptSize * 0.88f
                    val subLines = TextWrap.wrap(sub, limit, 1) { s -> widthOf(s, subSize, false) }
                    for (line in subLines) {
                        glyphs += glyph(line, subSize, 135, false, secondary = true)
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
                    glyphs += glyph(line, primarySize, 255, style.bold)
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
                        glyphs += glyph(
                            line, secondarySize,
                            (style.secondaryAlpha * 255f).toInt().coerceIn(0, 255), false,
                            secondary = true
                        )
                        glyphTops += y
                        y += secondarySize * 1.45f
                    }
                }
            }
        }

        contentHeight = y

        var width = glyphs.maxOfOrNull { it.width } ?: 0f
        // 最小宽度：四个字。避免「我」这样的单字歌词变成一个孤零零的小方块。
        width = maxOf(width, widthOf("字字字字", primarySize, style.bold))
        if (editing && mode == Mode.CONTENT) {
            width = maxOf(width, widthOf(hintText, spToPx(11f), false) + 54f * density)
        }
        // 固定宽度时内容区就是「窗口宽 − 左右内边距 − 左内衬」，文字按 align 在里面排。
        contentWidth = if (fixedWidthPx > 0) {
            (fixedWidthPx - paddingH * 2f - leftInsetPx).coerceAtLeast(60f * density)
        } else {
            width.coerceAtMost(limit)
        }

        requestLayout()
        invalidate()
    }

    private fun glyph(
        text: String,
        size: Float,
        alpha: Int,
        bold: Boolean,
        secondary: Boolean = false
    ): Glyph = Glyph(text, size, alpha, bold, widthOf(text, size, bold), secondary)

    private fun contentLimit(): Float {
        val base = if (fixedWidthPx > 0) {
            fixedWidthPx.toFloat()
        } else if (maxWidthPx > 0) {
            maxWidthPx.toFloat()
        } else {
            4000f
        }
        return (base - paddingH * 2f - leftInsetPx).coerceAtLeast(60f * density)
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

    /**
     * 四角把手命中判定：0 左上 / 1 右上 / 2 左下 / 3 右下，未命中返回 -1。
     *
     * 只在编辑态生效 —— 非编辑态窗口根本不接收触摸。
     */
    fun hitCorner(x: Float, y: Float): Int {
        if (!editing || mode == Mode.MENU) return -1
        val r = CORNER_TOUCH_DP * density
        val w = width.toFloat()
        val h = height.toFloat()
        val corners = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
        var best = -1
        var bestDist = r * r
        for (i in 0 until 4) {
            val dx = x - corners[i * 2]
            val dy = y - corners[i * 2 + 1]
            val d = dx * dx + dy * dy
            if (d <= bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** 当前可用窗口的最小尺寸，拖角时用来兜底，避免把自己拖成一条线。 */
    fun minWidthPx(): Int =
        (widthOf("字字字", spToPx(style.fontSp), style.bold) + paddingH * 2f + leftInsetPx).toInt()

    fun minHeightPx(): Int = (spToPx(style.fontSp) * 1.38f + paddingV * 2f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hint = if (editing && mode == Mode.CONTENT) hintHeight() else 0f
        val fallbackW = (contentWidth + paddingH * 2f + leftInsetPx).toInt().coerceIn(1, 4000)
        val fallbackH = (contentHeight + paddingV * 2f + hint).toInt().coerceIn(1, 2000)

        val w = when {
            previewW > 0 -> previewW
            fixedWidthPx > 0 -> fixedWidthPx
            else -> fallbackW
        }
        val h = when {
            previewH > 0 -> previewH
            fixedHeightPx > 0 -> maxOf(fixedHeightPx, (contentHeight + paddingV * 2f + hint).toInt())
            else -> fallbackH
        }
        setMeasuredDimension(w.coerceIn(1, 4000), h.coerceIn(1, 2000))
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
            bgPaint.color = (style.bgColor and 0x00FFFFFF) or (a shl 24)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius(), radius(), bgPaint)
        }

        // 固定高度时内容垂直居中：只有一行时贴顶会显得整体偏上，尤其在 2~3 行高度里。
        contentTop = blockTop()

        val fading = fadeCount > 0 && fadeElapsed < 1f
        if (fading) {
            // 旧行在前 35% 里退干净，新行从 12% 才开始浮上来。
            // 两条曲线刻意错开：全程同步交叉会让两句话叠在一起，看起来像重影。
            val out = (1f - fadeElapsed / 0.35f).coerceIn(0f, 1f)
            val inn = ((fadeElapsed - 0.12f) / 0.88f).coerceIn(0f, 1f)
            for (i in 0 until fadeCount) {
                drawGlyph(canvas, fadeGlyphs[i], fadeTop + fadeTops[i], out)
            }
            for (i in glyphs.indices) {
                drawGlyph(canvas, glyphs[i], contentTop + glyphTops[i], inn)
            }
        } else {
            for (i in glyphs.indices) {
                drawGlyph(canvas, glyphs[i], contentTop + glyphTops[i], 1f)
            }
        }

        if (editing) {
            drawEditFrame(canvas, w, h)
            drawHintBar(canvas, w, h)
        }
    }

    /** 内容块顶边。自适应高度时就是内边距；固定高度时按可用高度居中。 */
    private fun blockTop(): Float {
        val hint = if (editing && mode == Mode.CONTENT) hintHeight() else 0f
        val usable = height - paddingV * 2f - hint
        if (usable <= contentHeight || fixedHeightPx <= 0) return paddingV
        return paddingV + (usable - contentHeight) / 2f
    }

    private fun drawGlyph(canvas: Canvas, glyph: Glyph, top: Float, alphaScale: Float) {
        configureText(glyph, alphaScale)
        val baseline = top - textPaint.fontMetrics.ascent
        val glyphWidth = glyph.width
        val baseX = textLeft
        val x = when (style.align) {
            Align.START -> baseX
            Align.CENTER -> baseX + (contentWidth - glyphWidth) / 2f
            Align.END -> baseX + contentWidth - glyphWidth
        }
        // 描边先画、填充后画：这是真轮廓，不是把文字偏移复制一遍当阴影。
        if (style.stroke) canvas.drawText(glyph.text, x, baseline, strokePaint)
        canvas.drawText(glyph.text, x, baseline, textPaint)
    }

    private fun drawMenu(canvas: Canvas) {
        val baseX = textLeft * 1.4f
        for (i in glyphs.indices) {
            val glyph = glyphs[i]
            configureText(glyph, 1f)
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
        canvas.drawText(hintText, textLeft, baseline, hintPaint)

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

    private fun configureText(glyph: Glyph, alphaScale: Float) {
        val typeface = if (glyph.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val alpha = (glyph.alpha * alphaScale).toInt().coerceIn(0, 255)
        // 颜色带自己的 alpha 通道时以样式为准：色板都是不透明色，这里只乘 glyph 的 alpha。
        val base = if (glyph.secondary) style.secondaryColor else style.primaryColor

        textPaint.textSize = glyph.size
        textPaint.typeface = typeface
        textPaint.color = base and 0x00FFFFFF
        textPaint.alpha = alpha

        strokePaint.textSize = glyph.size
        strokePaint.typeface = typeface
        strokePaint.color = style.strokeColor
        strokePaint.alpha = alpha
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = glyph.size * 0.085f
    }

    private companion object {
        /** 四角把手的触摸半径。车机手指粗，比视觉半径大得多。 */
        const val CORNER_TOUCH_DP = 22f
    }
}
