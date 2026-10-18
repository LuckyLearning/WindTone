package com.luckylearning.windtone.overlay

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
 */
object OverlayLegibility {

    /** 有底板时上下句的不透明度（与播放页 0.42 对齐） */
    const val CONTEXT_ALPHA_WITH_BACKDROP = 0x6B

    /** 无底板时上下句的不透明度（≈61%）：不压这么暗才能在浅底上读得清 */
    const val CONTEXT_ALPHA_NO_BACKDROP = 0x9B

    /** 有底板时译文行的不透明度 */
    const val TRANSLATION_ALPHA_WITH_BACKDROP = 0xA0

    /** 无底板时译文行的不透明度（≈74%） */
    const val TRANSLATION_ALPHA_NO_BACKDROP = 0xBD

    fun contextAlpha(noBackdrop: Boolean): Int =
        if (noBackdrop) CONTEXT_ALPHA_NO_BACKDROP else CONTEXT_ALPHA_WITH_BACKDROP

    fun translationAlpha(noBackdrop: Boolean): Int =
        if (noBackdrop) TRANSLATION_ALPHA_NO_BACKDROP else TRANSLATION_ALPHA_WITH_BACKDROP
}
