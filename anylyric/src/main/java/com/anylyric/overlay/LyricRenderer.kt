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
import android.text.TextUtils
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

    private var primaryText = ""

    /**
     * 副行（上下文行）文本。
     *
     * 以前只有**一条** String，所以「行数 = 4」时最多也就能画出「当前行 + 下一行」两行 ——
     * 用户反馈的「副行内容有问题，只能显示 2 行歌词」就是这里：预算给了 4 行，
     * 却只有 1 条文本可填。现在由上层给一组相邻行，渲染器按
     * `maxLines` 的总预算往下铺，折行用掉几行就少画几行上下文。
     */
    private val secondaryLines = ArrayList<String>(CONTEXT_MAX)

    /** 标题行（歌名 · 歌手）。null 表示不显示。 */
    private var titleText: String? = null

    /** 标题行最终画出来的文本（按可用宽度做过省略），在 [rebuild] 里算好。 */
    private var titleLine: String? = null
    private var titleSize = 0f
    private var titleBlockH = 0f

    /** 标题占掉的上/下高度（含与歌词的间隙）。 */
    private var chromeTop = 0f
    private var chromeBottom = 0f

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
     * 单行滚动（[LineMode.SCROLL]）状态。
     *
     * 只在「这一句确实比内容区宽」时才起动画：否则跑马灯会在短句上变成静止的偏移，
     * 看起来像文字没对齐。动画本身只 `invalidate` 这一个 View，不改窗口尺寸。
     */
    private var scrollAnimator: ValueAnimator? = null
    private var scrollOffset = 0f
    private var scrollNeeded = false
    private var scrollCycleMs = 0L
    private var scrollDistance = 0f

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
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val doneBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 标题描边专用画笔。
     *
     * 不复用正文的 [strokePaint]：那个画笔在 `configureText` 里逐字改 textAlign 之外的
     * 全部属性，共用会让「标题把居中带进正文」这种错位重现，而且很难查。
     */
    private val titleStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 编辑态虚线的 PathEffect。
     *
     * 以前在 `drawEditFrame` 里每帧 new 一个：编辑态一拖角就是每秒几十个对象，
     * 而且 PathEffect 一变 Paint 就要重新走一遍 native 侧的准备。间隔只跟 density 有关，
     * 建 View 时算一次就够。
     */
    private val dashEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)

    private val rect = RectF()
    private val path = Path()

    /**
     * 交叉淡入动画。
     *
     * 刻意**不再**改窗口的 alpha：那会让整块 surface 重新合成，在车机上就是掉帧与卡顿。
     * 现在只在本 View 里多画一次旧文本，走的是同一张画的两次 drawText，代价可以忽略。
     */
    private var fadeAnimator: ValueAnimator? = null

    /**
     * 收起态：窗口与 View 都保留，只让 [onDraw] 直接返回。
     *
     * 收起/展开以前是「移除窗口 → 重新 addView」，一拆一建必然闪一帧；
     * 现在改成画空 + 重绘一次，window surface 会清成透明，但不需要重建任何东西。
     */
    private var collapsed = false

    /**
     * 「按应用隐藏」造成的临时不绘制（0.2.11）。
     *
     * 与 [collapsed] **互相独立**：用户收起是主动态，按应用隐藏是环境态。
     * 两者叠加时必须各记各的 —— 否则在名单应用里退出后，用户原本收起的状态会被
     * 一起清掉（表现为「回来一看歌词自己展开了」）。
     */
    private var suppressed = false

    /** 真正决定「画不画」的唯一判据。 */
    private val hidden: Boolean get() = collapsed || suppressed

    /**
     * 收起期间内容是否变过。
     *
     * 没变过 = 展开时字形还是对的，只需重绘（`invalidate`），不用重新测量/折行；
     * 变过才需要 [rebuild]。收起→展开这种「看一眼就收回去」的操作因此几乎零开销。
     */
    private var collapsedDirty = false

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

    /**
     * 收起 / 展开（不拆窗口）。
     *
     * 收起时停掉淡入并主动重绘一次：[onDraw] 立刻返回，窗口 surface 被清成透明，
     * 旧歌词不会滞留在屏幕上。展开时只重建一次内容，不需要重新 `addView`。
     */
    fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        val wasHidden = hidden
        collapsed = value
        applyHiddenTransition(wasHidden)
    }

    /**
     * 按应用隐藏：命中名单时临时不绘制，退出该应用后自动恢复。
     *
     * 走和收起同一条路（不拆窗口、只画空一次），所以**显隐都不掉帧**。
     * 与用户收起互不覆盖：本方法只动 [suppressed]。
     */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        val wasHidden = hidden
        suppressed = value
        applyHiddenTransition(wasHidden)
    }

    /**
     * 「可见 ↔ 不可见」的过渡只做一次。
     *
     * 两个状态（收起 / 按应用隐藏）各自动手时，只要合并后的结果没变就什么都不做 ——
     * 例如用户收起状态下切进名单应用，此时本来就没画，不必再清一次屏。
     */
    private fun applyHiddenTransition(wasHidden: Boolean) {
        val nowHidden = hidden
        if (nowHidden == wasHidden) return
        if (nowHidden) {
            stopFade()
            stopScroll()
            collapsedDirty = false
            // 必须主动 invalidate：不可见状态靠「不画」清 surface，得先触发一次绘制。
            invalidate()
        } else {
            // 隐藏期间没换句就直接重绘，省掉一次折行 + requestLayout。
            if (collapsedDirty) rebuild() else invalidate()
            syncScroll()
            collapsedDirty = false
        }
    }

    /**
     * @param secondary 副行文本，按显示顺序。条数不受限，[rebuild] 按行数预算截断
     * @param title 标题行（歌名 · 歌手）；null 或空串表示这帧没有标题
     */
    fun setContent(primary: String, secondary: List<String>, title: String?, nextMode: Mode) {
        if (hidden) {
            // 收起 / 按应用隐藏期间只记内容，不重建、不触发淡入；恢复时按 dirty 决定要不要 rebuild。
            val dirty = primary != primaryText ||
                secondary != secondaryLines ||
                title != titleText ||
                nextMode != mode
            primaryText = primary
            secondaryLines.clear()
            secondaryLines.addAll(secondary)
            titleText = title
            mode = nextMode
            if (dirty) collapsedDirty = true
            return
        }
        val changed = primary != primaryText ||
            secondary != secondaryLines ||
            title != titleText ||
            nextMode != mode
        if (!changed) return

        // 只有「真歌词换成真歌词」才值得做交叉淡入：提示语、菜单、编辑态都不淡入，
        // 否则操作过程中界面一直在闪。
        val crossfade = nextMode == Mode.CONTENT &&
            mode == Mode.CONTENT &&
            !editing &&
            style.lineMode == LineMode.WRAP &&
            style.fadeMs > 0 &&
            glyphs.isNotEmpty()

        if (crossfade) snapshotForFade()
        primaryText = primary
        secondaryLines.clear()
        secondaryLines.addAll(secondary)
        titleText = title
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
        stopScroll()
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

        // 标题：菜单态一律不画（那是一张操作列表，标题会看成菜单项）
        titleSize = spToPx(style.titleFontSp)
        titleLine = if (style.titleEnabled && mode != Mode.MENU) {
            titleText?.trim()?.takeIf { it.isNotEmpty() }?.let { ellipsizeTitle(it, limit) }
        } else {
            null
        }
        titleBlockH = if (titleLine != null) titleSize * 1.45f else 0f
        val titleGap = if (titleLine != null) TITLE_GAP_DP * density else 0f
        val showTitle = titleLine != null
        chromeTop = if (showTitle && style.titlePos == TitlePos.TOP) titleBlockH + titleGap else 0f
        chromeBottom =
            if (showTitle && style.titlePos == TitlePos.BOTTOM) titleBlockH + titleGap else 0f

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
                val sub = secondaryLines.firstOrNull()
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
                // 单行滚动：只画当前句，**不折行**。副行整组让位 —— 滚动行本身就在动，
                // 再叠一行上下文会让人分不清哪句是「现在唱到的地方」。
                if (style.lineMode == LineMode.SCROLL) {
                    val text = primaryText.trim()
                    if (text.isNotEmpty()) {
                        glyphs += glyph(text, primarySize, 255, style.bold)
                        glyphTops += 0f
                        y += primarySize * 1.38f
                    }
                } else {
                    // 当前行可以先占满预算（长句折成 3 行是合理的），剩下的才留给上下文行；
                    // 上下文行是一**组**，按剩余预算逐条铺 —— 只铺一条就是「永远只有两行」的老 bug。
                    val rows = LinePlan.build(primaryText, secondaryLines, style.maxLines) { text, lines, sub ->
                        val size = if (sub) secondarySize else primarySize
                        TextWrap.wrap(text, limit, lines) { s ->
                            widthOf(s, size, !sub && style.bold)
                        }
                    }
                    val subAlpha = (style.secondaryAlpha * 255f).toInt().coerceIn(0, 255)
                    for (row in rows) {
                        if (row.secondary) {
                            glyphs += glyph(row.text, secondarySize, subAlpha, false, secondary = true)
                            glyphTops += y
                            y += secondarySize * 1.45f
                        } else {
                            glyphs += glyph(row.text, primarySize, 255, style.bold)
                            glyphTops += y
                            y += primarySize * 1.38f
                        }
                    }
                }
            }
        }

        contentHeight = y

        // 错位只在左对齐下生效；上下文行整体右移，宽度也要跟着算进来，否则自适应时会被裁。
        val lineOffsetPx = if (style.align == Align.START) style.lineOffsetDp * density else 0f
        var width = glyphs.maxOfOrNull {
            it.width + if (it.secondary) lineOffsetPx else 0f
        } ?: 0f
        // 最小宽度：四个字。避免「我」这样的单字歌词变成一个孤零零的小方块。
        width = maxOf(width, widthOf("字字字字", primarySize, style.bold))
        // 标题也要能完整占满一行，否则窗口会被歌词挤窄、标题被省略成「歌名…」
        titleLine?.let { width = maxOf(width, measureTitle(it)) }
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
        syncScroll()
        // 字形已经按最新内容重建过：收起期间攒下的 dirty 一并清掉。
        collapsedDirty = false
    }

    /**
     * 按最新内容决定「要不要滚」。
     *
     * 三个条件同时成立才滚：单行模式、当前是正常歌词、这句比内容区宽。
     * 任一不成立就停掉动画并归零 —— 留着偏移量会让下一句直接从一个奇怪的位置开始。
     */
    private fun syncScroll() {
        val first = glyphs.firstOrNull()
        if (first == null ||
            style.lineMode != LineMode.SCROLL ||
            mode != Mode.CONTENT ||
            hidden ||
            editing ||
            !Marquee.needsScroll(first.width, contentWidth)
        ) {
            stopScroll()
            return
        }

        scrollDistance = Marquee.distance(first.width, contentWidth)
        scrollCycleMs = Marquee.durationMs(
            first.width, contentWidth, Marquee.SPEED_DP_PER_SEC * density
        )
        // 每次重建都从头滚：换句后从上一句的偏移接着跑，会让人以为新句子「缺了前半截」。
        scrollNeeded = true
        startScroll()
    }

    private fun startScroll() {
        val animator = scrollAnimator ?: ValueAnimator().also { scrollAnimator = it }
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()
        if (scrollCycleMs <= 0L || scrollDistance <= 0f) return
        animator.setFloatValues(0f, scrollDistance)
        animator.duration = scrollCycleMs
        animator.interpolator = null
        // 无限循环：repeatMode 的 RESTART 会先把值弹回 0，正好接上「尾句+空档」的起点。
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.RESTART
        animator.addUpdateListener {
            scrollOffset = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    private fun stopScroll() {
        scrollNeeded = false
        scrollOffset = 0f
        scrollAnimator?.let {
            it.removeAllUpdateListeners()
            it.cancel()
        }
    }

    /** 标题按可用宽度截断：宁可「歌名 · 歌…」也不要让窗口为了它整块变宽。 */
    private fun ellipsizeTitle(text: String, maxWidth: Float): String {
        titlePaint.textSize = titleSize
        titlePaint.typeface = Typeface.DEFAULT
        if (titlePaint.measureText(text) <= maxWidth) return text
        return TextUtils.ellipsize(text, titlePaint, maxWidth, TextUtils.TruncateAt.END).toString()
    }

    private fun measureTitle(text: String): Float {
        titlePaint.textSize = titleSize
        titlePaint.typeface = Typeface.DEFAULT
        return titlePaint.measureText(text)
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

    /** 当前可用窗口的最小尺寸，设置页滑杆靠它兜底，避免把自己调成一条线。 */
    fun minWidthPx(): Int =
        (widthOf("字字字", spToPx(style.fontSp), style.bold) + paddingH * 2f + leftInsetPx).toInt()

    fun minHeightPx(): Int =
        (spToPx(style.fontSp) * 1.38f + chromeTop + chromeBottom + paddingV * 2f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hint = if (editing && mode == Mode.CONTENT) hintHeight() else 0f
        val chrome = chromeTop + chromeBottom
        val fallbackW = (contentWidth + paddingH * 2f + leftInsetPx).toInt().coerceIn(1, 4000)
        val fallbackH = (contentHeight + chrome + paddingV * 2f + hint).toInt().coerceIn(1, 2000)

        val w = if (fixedWidthPx > 0) fixedWidthPx else fallbackW
        val h = when {
            // 固定高度是**严格**的：换行数、切单行滚动、换句都不改窗口高度，否则
            // 「调好的框自己长高一截」看起来像设置没保存。只有编辑态例外 ——
            // 底部 28dp 的提示条必须能塞进去，不然它会压住最后一行字。
            fixedHeightPx > 0 ->
                if (hint > 0) maxOf(fixedHeightPx, fallbackH) else fixedHeightPx

            else -> fallbackH
        }
        setMeasuredDimension(w.coerceIn(1, 4000), h.coerceIn(1, 2000))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // 收起态 / 按应用隐藏：什么都不画。主动 invalidate 过一次，窗口 surface 已被清成透明。
        if (hidden) return
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

        drawTitle(canvas, w, h)

        val fading = fadeCount > 0 && fadeElapsed < 1f
        if (scrollNeeded) {
            drawScrolling(canvas)
        } else if (fading) {
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
        val usable = height - paddingV * 2f - hint - chromeTop - chromeBottom
        if (usable <= contentHeight || fixedHeightPx <= 0) return paddingV + chromeTop
        return paddingV + chromeTop + (usable - contentHeight) / 2f
    }

    /**
     * 标题行：`歌名 · 歌手`，永远居中，字号与颜色独立于正文。
     *
     * 为什么单独一个绘制函数而不是塞进 [glyphs]：
     *  · 它不参与交叉淡入（换歌时淡入的是歌词，标题一跳到底反而更清楚）；
     *  · 它有自己的字号与颜色，混进 Glyph 只会让「这一行是什么」更难分辨；
     *  · 它不占 [OverlayStyle.maxLines] 的歌词行预算 —— 用户要的是「4 行歌词」，
     *    不该因为开了标题就少一行。
     */
    private fun drawTitle(canvas: Canvas, w: Float, h: Float) {
        val line = titleLine ?: return
        val hint = if (editing && mode == Mode.CONTENT) hintHeight() else 0f
        val top = if (style.titlePos == TitlePos.TOP) {
            paddingV
        } else {
            h - paddingV - hint - titleBlockH
        }

        titlePaint.textSize = titleSize
        titlePaint.typeface = Typeface.DEFAULT
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.color = style.titleColor
        val baseline = top - titlePaint.fontMetrics.ascent
        val cx = paddingH + leftInsetPx + contentWidth / 2f

        if (style.stroke) {
            titleStrokePaint.textSize = titleSize
            titleStrokePaint.typeface = Typeface.DEFAULT
            titleStrokePaint.textAlign = Paint.Align.CENTER
            titleStrokePaint.color = style.strokeColor
            titleStrokePaint.style = Paint.Style.STROKE
            titleStrokePaint.strokeWidth = titleSize * 0.085f
            canvas.drawText(line, cx, baseline, titleStrokePaint)
        }
        canvas.drawText(line, cx, baseline, titlePaint)
    }

    private fun drawGlyph(canvas: Canvas, glyph: Glyph, top: Float, alphaScale: Float) {
        configureText(glyph, alphaScale)
        val baseline = top - textPaint.fontMetrics.ascent
        val glyphWidth = glyph.width
        val baseX = textLeft
        val offset =
            if (glyph.secondary && style.align == Align.START) style.lineOffsetDp * density else 0f
        val rawX = when (style.align) {
            Align.START -> baseX + offset
            Align.CENTER -> baseX + (contentWidth - glyphWidth) / 2f
            Align.END -> baseX + contentWidth - glyphWidth
        }
        // 错位不能把字顶出内容区：固定宽度 + 长句时，右移量被夹回去。
        val maxX = (baseX + contentWidth - glyphWidth).coerceAtLeast(baseX)
        val x = rawX.coerceIn(baseX, maxX)
        // 描边先画、填充后画：这是真轮廓，不是把文字偏移复制一遍当阴影。
        if (style.stroke) canvas.drawText(glyph.text, x, baseline, strokePaint)
        canvas.drawText(glyph.text, x, baseline, textPaint)
    }

    /**
     * 单行滚动：把整句按 [scrollOffset] 左移，并在尾部再画一份接上，形成无缝循环。
     *
     * 必须 `clipRect`：窗口宽度是固定的，不裁的话文字会画到背景圆角外面去。
     * 只在内容区里裁，标题与编辑提示不受影响。
     */
    private fun drawScrolling(canvas: Canvas) {
        val glyph = glyphs.firstOrNull() ?: return
        if (contentWidth <= 0f) return
        val top = contentTop
        val gap = Marquee.gap(contentWidth)
        configureText(glyph, 1f)
        val baseline = top - textPaint.fontMetrics.ascent

        canvas.save()
        canvas.clipRect(textLeft, top, textLeft + contentWidth, top + contentHeight)
        // 两份之间的间距 = 整句宽 + 空档；偏移量走完一整轮时第二份正好接管第一份的起点
        for (i in 0..1) {
            val x = textLeft - scrollOffset + i * (glyph.width + gap)
            if (x > textLeft + contentWidth) break
            if (style.stroke) canvas.drawText(glyph.text, x, baseline, strokePaint)
            canvas.drawText(glyph.text, x, baseline, textPaint)
        }
        canvas.restore()
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

    /**
     * 编辑态的外框：只画一圈虚线。
     *
     * 以前四角各有一个白点提示「可以拖大」，现在大小改在设置页用滑杆调，
     * 圆点只会让人去按一个按不动的地方，所以一并去掉。
     */
    private fun drawEditFrame(canvas: Canvas, w: Float, h: Float) {
        val inset = 1.5f * density
        dashPaint.color = 0x99FFFFFF.toInt()
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = 1.2f * density
        dashPaint.pathEffect = dashEffect
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius(), radius(), dashPaint)
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
        /** 标题与歌词之间的间隙。固定 dp：标题字号改了间隙不该跟着翻倍。 */
        const val TITLE_GAP_DP = 6f

        /** 上下文行最多收几条。行数上限是 4，再多的行渲染器也会按预算丢掉。 */
        const val CONTEXT_MAX = 3
    }
}
