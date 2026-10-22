package com.luckylearning.windtone.overlay

import kotlin.math.roundToInt

/**
 * 悬浮歌词在**没有底板**时的可读性参数（v1.1.25）。
 *
 * 起因是一次真机反馈：「无底板时歌词模糊」。查下来不是渲染问题，而是两个设置叠加的结果：
 *  1. 投影是「半径 3dp + 下移 1dp」的柔光，浅色字压在泊车影像 / 浅色地图上时，
 *     边缘被柔光晕开，观感就是糊；
 *  2. 上下句只有 42% 不透明度，在浅底上直接看不见。
 *
 * 有底板时这两点都不成立（底板本身给了对比度，柔光看着也自然），所以只在无底板时
 * 换一套参数。抽成对象是为了让「无底板必须比有底板更不透明」这条能被单测钉住 ——
 * 否则下次有人调美观时很容易又把它压回 42%。
 *
 * v1.1.52：压暗程度开放为设置项（[SettingsDefaults.OVERLAY_DIM_PERCENT]，20–80%）。
 * 默认 42 与播放页 0.42 对齐，公式保证默认值下与旧版逐位一致：
 *  - 有底板：上下句 = 255 × dim% （42 → 0x6B），译文 = 上下句 + 53（42 → 0xA0）；
 *  - 无底板：上下句取「计算值与 0x9B 的较大者」（保底不压太暗），
 *    译文 = 上下句 + 34（42 → 0xBD）。
 */
object OverlayLegibility {

    /** 出厂默认的压暗百分比（与播放页 0.42 对齐） */
    const val DEFAULT_DIM_PERCENT = 42

    /** 有底板时上下句的不透明度（与播放页 0.42 对齐） */
    const val CONTEXT_ALPHA_WITH_BACKDROP = 0x6B

    /** 无底板时上下句的不透明度（≈61%）：不压这么暗才能在浅底上读得清 */
    const val CONTEXT_ALPHA_NO_BACKDROP = 0x9B

    /** 有底板时译文行的不透明度 */
    const val TRANSLATION_ALPHA_WITH_BACKDROP = 0xA0

    /** 无底板时译文行的不透明度（≈74%） */
    const val TRANSLATION_ALPHA_NO_BACKDROP = 0xBD

    /** 译文相对上下句多出来的那档亮度（有底板） */
    private const val TRANSLATION_DELTA_WITH_BACKDROP = 53

    /** 译文相对上下句多出来的那档亮度（无底板） */
    private const val TRANSLATION_DELTA_NO_BACKDROP = 34

    fun contextAlpha(noBackdrop: Boolean): Int =
        contextAlpha(noBackdrop, DEFAULT_DIM_PERCENT)

    fun contextAlpha(noBackdrop: Boolean, dimPercent: Int): Int {
        val base = (255 * dimPercent.coerceIn(0, 100) / 100f).roundToInt()
        return if (noBackdrop) {
            base.coerceAtLeast(CONTEXT_ALPHA_NO_BACKDROP)
        } else {
            base
        }
    }

    fun translationAlpha(noBackdrop: Boolean): Int =
        translationAlpha(noBackdrop, DEFAULT_DIM_PERCENT)

    fun translationAlpha(noBackdrop: Boolean, dimPercent: Int): Int =
        contextAlpha(noBackdrop, dimPercent) +
            if (noBackdrop) TRANSLATION_DELTA_NO_BACKDROP else TRANSLATION_DELTA_WITH_BACKDROP
}
