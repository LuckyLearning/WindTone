package com.luckylearning.windtone.overlay

import android.view.Gravity

/**
 * 悬浮歌词「外观」的纯计算集合（v1.1.38 重构抽出）。
 *
 * ## 为什么单独抽出来
 * [LyricOverlayService] 里散落着一批「只算数、不碰 View、不读上下文」的判定：
 * 对齐档位怎么翻译成 gravity、标题字号怎么算、暂停态透明度是多少、无底板时
 * 投影参数怎么取、全屏行距是多少。它们原本混在几十上百行的 UI 方法里，
 * 改一处要读懂整段，也根本没法单测。
 *
 * 这里把它们收成纯函数 + 常量：**没有 Android 运行时依赖**（用到的 `Gravity` 等
 * 全是编译期内联的 `const val` 整型），因此可以在普通 JVM 单测里直接验证，
 * 不需要 Robolectric 起一套模拟环境。
 *
 * ## 边界
 * 只负责「算出值」，不负责「把值设到 View 上」——设值仍在服务里做
 * （那里才有 `dp`、才有 `TextView`）。本对象对外暴露的都是可测的纯数据。
 */
object OverlayAppearance {

    /** 悬浮字幕基准字号（中档，sp）：车机屏大、观看距离远，比手机正文大一档 */
    const val OVERLAY_TEXT_SP = 20f

    /** 字号三档的倍率：小 / 中 / 大。存档位不存具体 sp，日后调基准能带上老用户 */
    val TEXT_SCALE_FACTORS = floatArrayOf(0.82f, 1f, 1.25f)

    /** 标题相对正文的固定缩小比例 */
    const val TITLE_TEXT_SCALE = 0.62f

    /**
     * 暂停态的不透明度（0..1）。
     *
     * 播放时歌词满亮，暂停时压到 55% —— 既告诉用户「停了」，又不至于完全看不见
     * 还停在原句的画面。选 0.55 是因为低于 0.5 在深色底板上会显得「像没加载」。
     */
    const val PAUSED_ALPHA = 0.55f

    /** 全屏模式：行间距额外像素，让 11 行铺满一屏时呼吸感接近播放页 */
    const val FULL_LINE_SPACING_EXTRA_DP = 10

    /** 全屏模式：行间距倍率 */
    const val FULL_LINE_SPACING_MULT = 1.2f

    /**
     * 底板三档：0 无 / 1 半透明（默认）/ 2 实底。
     *
     * 语义集中在这一处，服务里所有「看底板判事」的逻辑都引用这里，
     * 避免同一个 0/1/2 散落在各方法里各写各的、哪天加档位对不齐。
     */
    const val BACKDROP_NONE = 0
    const val BACKDROP_SOLID = 2

    /**
     * 文字在窗内的对齐：居左 = 从同一条竖线起排；否则居中（折行也居中）。
     *
     * 纯查表，无副作用。
     */
    fun alignGravity(align: Int): Int =
        if (align == OverlayPositioner.ALIGN_LEFT) {
            Gravity.START or Gravity.CENTER_VERTICAL
        } else {
            Gravity.CENTER
        }

    /** 正文（歌词）字号（sp）：以基准字号乘三档倍率 */
    fun bodyTextSp(scale: Int): Float =
        OVERLAY_TEXT_SP * TEXT_SCALE_FACTORS[scale.coerceIn(0, 2)]

    /** 歌名 / 歌手小字的字号（sp）：在正文基础上再乘标题档位与固定缩小比例 */
    fun titleTextSp(scale: Int): Float =
        OVERLAY_TEXT_SP * TEXT_SCALE_FACTORS[scale.coerceIn(0, 2)] * TITLE_TEXT_SCALE

    /**
     * 暂停 / 播放的目标透明度。
     *
     * @param opaque true = 播放中（满亮），false = 暂停（压暗）
     */
    fun targetAlpha(opaque: Boolean): Float = if (opaque) 1f else PAUSED_ALPHA

    /**
     * 文字投影（描边）参数，按是否「无底板」两档取值。
     *
     * 无底板时文字直接压在车机画面上，浅色字会被浅底吃掉，所以改成
     * **零位移 + 更紧的深色描边**（边界立刻变硬）；有底板时保持原来的柔光
     * （底板已给了对比度，再加深会显脏）。返回的是 dp / 0..255 的「意图值」，
     * 像素换算（`dp(...)`）和颜色组装由调用方完成——这里只决定「取哪一组」。
     */
    fun shadowFor(backdrop: Int): ShadowSpec =
        if (backdrop == BACKDROP_NONE) {
            ShadowSpec(radiusDp = 2, offsetYDp = 0, colorAlpha = 255)
        } else {
            ShadowSpec(radiusDp = 3, offsetYDp = 1, colorAlpha = 220)
        }

    /** 全屏模式的行距参数（额外像素, 倍率），供 [android.widget.TextView.setLineSpacing] 直接使用 */
    fun fullHeightLineSpacing(): Pair<Int, Float> =
        FULL_LINE_SPACING_EXTRA_DP to FULL_LINE_SPACING_MULT

    /** [shadowFor] 的纯数据结果：投影半径（dp）、纵向偏移（dp）、阴影色 alpha（0..255） */
    data class ShadowSpec(val radiusDp: Int, val offsetYDp: Int, val colorAlpha: Int)
}
