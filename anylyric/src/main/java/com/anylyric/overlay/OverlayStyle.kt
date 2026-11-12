package com.anylyric.overlay

/**
 * 悬浮窗外观的唯一真源。
 *
 * 设计原则：外观是**数据**而不是代码分支。加一种样式只加一个参数，不改 [LyricRenderer]
 * 的绘制逻辑；默认值只在 [OverlayDefaults] 定义一次，任何「恢复默认」都写回这里的常量。
 */

/** 外观预设。CUSTOM 表示用户已在某个预设上微调过，界面不再高亮任何预设。 */
enum class Preset(val id: String) {
    STRIPE("stripe"),
    GLASS("glass"),
    SOLID("solid"),
    NAVI("navi"),
    CUSTOM("custom");

    companion object {
        fun of(id: String?): Preset = values().firstOrNull { it.id == id } ?: GLASS
    }
}

enum class Align(val id: String) {
    START("start"),
    CENTER("center"),
    END("end");

    companion object {
        fun of(id: String?): Align = values().firstOrNull { it.id == id } ?: START
    }
}

/** 副行内容策略。逐行流没有时间轴，副行取相邻行才有意义。 */
enum class Secondary(val id: String) {
    NEXT("next"),
    PREVIOUS("previous"),
    TRACK("track"),
    NONE("none");

    companion object {
        fun of(id: String?): Secondary = values().firstOrNull { it.id == id } ?: NEXT
    }
}

/**
 * @param widthRatio 窗口宽度占比；**0 表示自适应**（按文字宽度收窄）。
 * @param heightRatio 窗口高度占比；**0 表示自适应**（按行数收窄）。
 *
 * 宽高都存「占可用区的比例」而不是像素，理由与位置一致：车机与手机分辨率差两三倍，
 * 存像素在另一台机器上不是过宽就是被裁。
 */
data class OverlayStyle(
    val preset: Preset = Preset.GLASS,
    val fontSp: Float = OverlayDefaults.FONT_SP,
    val bold: Boolean = OverlayDefaults.BOLD,
    val maxLines: Int = OverlayDefaults.MAX_LINES,
    val opacity: Float = OverlayDefaults.OPACITY,
    val cornerDp: Float = OverlayDefaults.CORNER_DP,
    val stroke: Boolean = OverlayDefaults.STROKE,
    val secondaryAlpha: Float = OverlayDefaults.SECONDARY_ALPHA,
    val align: Align = Align.START,
    val fadeMs: Int = OverlayDefaults.FADE_MS,
    val secondary: Secondary = Secondary.NEXT,
    val stateMark: Boolean = OverlayDefaults.STATE_MARK,
    val primaryColor: Int = OverlayDefaults.PRIMARY_COLOR,
    val secondaryColor: Int = OverlayDefaults.SECONDARY_COLOR,
    val bgColor: Int = OverlayDefaults.BG_COLOR,
    val widthRatio: Float = OverlayDefaults.WIDTH_RATIO,
    val heightRatio: Float = OverlayDefaults.HEIGHT_RATIO
)

object OverlayDefaults {

    const val FONT_SP = 22f
    const val FONT_MIN = 14f
    const val FONT_MAX = 40f
    const val BOLD = true
    const val MAX_LINES = 2
    const val LINES_MIN = 1
    const val LINES_MAX = 3
    const val OPACITY = 0.55f
    const val CORNER_DP = 14f
    const val CORNER_MAX = 28f
    const val STROKE = false
    const val SECONDARY_ALPHA = 0.58f
    const val SECONDARY_ALPHA_MIN = 0.2f
    const val FADE_MS = 180
    const val FADE_MAX = 320
    const val STATE_MARK = true

    /** 当前行（正在唱的那句）颜色。 */
    const val PRIMARY_COLOR = 0xFFFFFFFF.toInt()

    /** 其它行（副行）颜色。实际不透明度还会再乘 [SECONDARY_ALPHA]。 */
    const val SECONDARY_COLOR = 0xFFFFFFFF.toInt()

    /** 背景色。实际不透明度由 [OPACITY] 控制，等于 0 时完全不画底。 */
    const val BG_COLOR = 0xFF14161A.toInt()

    /** 0 = 自适应宽度；非 0 = 占可用区宽度的比例。 */
    const val WIDTH_RATIO = 0f
    const val WIDTH_MIN = 0.24f
    const val WIDTH_MAX = 0.98f

    /** 0 = 自适应高度；非 0 = 占可用区高度的比例。 */
    const val HEIGHT_RATIO = 0f
    const val HEIGHT_MIN = 0.04f
    const val HEIGHT_MAX = 0.60f

    /** 默认位置：底部居中。比例而非像素，换分辨率不丢位置。 */
    const val RATIO_X = 0.5f
    const val RATIO_Y = 1f

    val STYLE = OverlayStyle(
        preset = Preset.GLASS,
        fontSp = FONT_SP,
        bold = BOLD,
        maxLines = MAX_LINES,
        opacity = OPACITY,
        cornerDp = CORNER_DP,
        stroke = STROKE,
        secondaryAlpha = SECONDARY_ALPHA,
        align = Align.START,
        fadeMs = FADE_MS,
        secondary = Secondary.NEXT,
        stateMark = STATE_MARK,
        primaryColor = PRIMARY_COLOR,
        secondaryColor = SECONDARY_COLOR,
        bgColor = BG_COLOR,
        widthRatio = WIDTH_RATIO,
        heightRatio = HEIGHT_RATIO
    )

    /**
     * 把预设展开成一组参数。
     *
     * 只覆盖「观感」字段，保留用户选的字号、副行策略与**自定义颜色** ——
     * 换预设不该把用户刚调好的配色一起冲掉，那是最容易被当成 bug 的行为。
     */
    fun preset(preset: Preset, base: OverlayStyle): OverlayStyle = when (preset) {
        Preset.STRIPE -> base.copy(
            preset = preset, opacity = 0f, cornerDp = 0f, stroke = true,
            maxLines = 2, secondaryAlpha = 0.85f, align = Align.START,
            stateMark = false, fadeMs = FADE_MS
        )

        Preset.GLASS -> base.copy(
            preset = preset, opacity = 0.55f, cornerDp = 14f, stroke = false,
            maxLines = 2, secondaryAlpha = 0.58f, align = Align.START,
            stateMark = true, fadeMs = FADE_MS
        )

        Preset.SOLID -> base.copy(
            preset = preset, opacity = 0.86f, cornerDp = 8f, stroke = false,
            maxLines = 2, secondaryAlpha = 0.70f, align = Align.START,
            stateMark = true, fadeMs = FADE_MS
        )

        // 导航态：只留一句，居中，行数固定 1 —— 行车中多一行都是干扰。
        Preset.NAVI -> base.copy(
            preset = preset, opacity = 0.86f, cornerDp = 20f, stroke = false,
            maxLines = 1, secondaryAlpha = 0.70f, align = Align.CENTER,
            stateMark = false, fadeMs = 160
        )

        Preset.CUSTOM -> base.copy(preset = preset)
    }

    /**
     * 所有范围都在这里收敛，避免界面上调出不可读或越界的值。
     *
     * 宽高比允许 0（自适应），因此不能用 coerceIn 直接夹：0 会被拉到下限。
     */
    fun sanitize(style: OverlayStyle): OverlayStyle = style.copy(
        fontSp = style.fontSp.coerceIn(FONT_MIN, FONT_MAX),
        maxLines = style.maxLines.coerceIn(LINES_MIN, LINES_MAX),
        opacity = style.opacity.coerceIn(0f, 1f),
        cornerDp = style.cornerDp.coerceIn(0f, CORNER_MAX),
        secondaryAlpha = style.secondaryAlpha.coerceIn(SECONDARY_ALPHA_MIN, 1f),
        fadeMs = style.fadeMs.coerceIn(0, FADE_MAX),
        widthRatio = ratioOrAuto(style.widthRatio, WIDTH_MIN, WIDTH_MAX),
        heightRatio = ratioOrAuto(style.heightRatio, HEIGHT_MIN, HEIGHT_MAX)
    )

    /** 0（或负数/NaN）保持 0 表示自适应；其余夹到 [min, max]。 */
    private fun ratioOrAuto(value: Float, min: Float, max: Float): Float = when {
        value.isNaN() || value <= 0f -> 0f
        value < min -> min
        value > max -> max
        else -> value
    }
}

/**
 * 可选颜色。刻意只给一组「车机上看得清」的色板，不做自由取色器：
 * 车机屏在阳光下对比度本来就差，让用户调出灰字灰底是负体验。
 */
object OverlayPalette {

    /** 当前行可选色。末尾那个深色是给浅色背景准备的。 */
    val TEXT = intArrayOf(
        0xFFFFFFFF.toInt(), // 纯白
        0xFFF6EFE0.toInt(), // 暖白
        0xFF9BE7FF.toInt(), // 冰蓝
        0xFF7FD1A4.toInt(), // 薄荷
        0xFFFFD166.toInt(), // 琥珀
        0xFFFF9E80.toInt(), // 珊瑚
        0xFFC7A4FF.toInt(), // 薰衣草
        0xFF111417.toInt()  // 近黑
    )

    /** 背景可选色。都偏深，浅色只留一个给「白底黑字」。 */
    val BACKGROUND = intArrayOf(
        0xFF14161A.toInt(), // 石墨（默认）
        0xFF000000.toInt(), // 纯黑
        0xFF1E2A38.toInt(), // 深蓝
        0xFF2B2118.toInt(), // 深咖
        0xFF3A2230.toInt(), // 深紫红
        0xFF0F1B2A.toInt(), // 藏青
        0xFFF2F0EA.toInt()  // 米白
    )

    fun textIndexOf(color: Int): Int = TEXT.indexOfFirst { it == color }
    fun backgroundIndexOf(color: Int): Int = BACKGROUND.indexOfFirst { it == color }
}
