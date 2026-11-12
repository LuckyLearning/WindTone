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
    val stateMark: Boolean = OverlayDefaults.STATE_MARK
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
    const val FADE_MS = 120
    const val FADE_MAX = 240
    const val STATE_MARK = true

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
        stateMark = STATE_MARK
    )

    /**
     * 把预设展开成一组参数。
     *
     * 只覆盖「观感」字段，保留用户选的字号与副行策略 —— 换预设不应该把字号也重置掉。
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

    /** 所有范围都在这里收敛，避免界面上调出不可读或越界的值。 */
    fun sanitize(style: OverlayStyle): OverlayStyle = style.copy(
        fontSp = style.fontSp.coerceIn(FONT_MIN, FONT_MAX),
        maxLines = style.maxLines.coerceIn(LINES_MIN, LINES_MAX),
        opacity = style.opacity.coerceIn(0f, 1f),
        cornerDp = style.cornerDp.coerceIn(0f, CORNER_MAX),
        secondaryAlpha = style.secondaryAlpha.coerceIn(SECONDARY_ALPHA_MIN, 1f),
        fadeMs = style.fadeMs.coerceIn(0, FADE_MAX)
    )
}
