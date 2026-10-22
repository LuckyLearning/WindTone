package com.luckylearning.windtone.overlay

import kotlin.math.roundToInt

/**
 * 「一次画几行」的自适应计算（v1.1.49）。
 *
 * ## 为什么要有这一层
 * v1.1.48 之前行数只有 1..5 五个档位，全屏形态则写死 11 行。两个问题在真机上
 * 都会顶出来：
 *  - 单条字幕把高度调成固定百分比之后，行数还是老档位 —— 高度 30% 仍画 5 行，
 *    多出来的那两行被窗口裁掉，「设了几行」与「看到几行」对不上；
 *  - 全屏形态写死 11 行，在手机竖屏 / 面板高度调矮时同样会被裁（20sp 的字在
 *    1080p 手机上单行约 110px，11 行要 1200px，而可用高度只有 900px 出头）。
 *
 * 所以行数改成可以被高度反推：**先算单行占多高，再用可用高度除**，取奇数
 * （与 [OverlayTextComposer.compose]「偶数行时下方多一行」的规则一致，
 * 奇数才能让当前行真正落在中间），最后夹进上下限。
 *
 * ## 边界
 * 纯函数：不读 Prefs、不碰 View，字号与 dp 换算都在调用方（服务）完成。
 * 这样「算得对不对」可以用普通 JVM 单测钉住，「字号是多少」照旧可调。
 */
object OverlayLineFit {

    /** 行数设置取这个值表示「自动」：由窗口高度决定 */
    const val AUTO = 0

    /**
     * 自动行数的上限。
     *
     * v1.1.50 之前这里分形态设过 11 / 7 两档，真机立刻打脸：手机竖屏上把单条
     * 高度拉到 94%，窗口大半是空的、只画 7 行 —— 用户要的是「参考播放页，
     * 高度用满」。所以自动档的上限放宽成一个宽松的安全网，**行数主要交给
     * 高度反推**：字号再小、屏幕再高，25 行也是一块完整的歌词墙而不是失控。
     */
    const val MAX_AUTO_LINES = 25

    /**
     * 单条字幕「高度也是自动」时自动行数的兜底值。
     *
     * 此时窗口高度由内容撑开、内容又由行数决定 —— 真按测量值反推会形成
     * 「行多变高、变高又多行」的自我强化循环。3 行（上一行 / 当前 / 下一行）
     * 是「能看出上下文」且不会把字幕撑成一块板的最小合理值。
     */
    const val STRIP_FALLBACK_LINES = 3

    /**
     * 字体自身行高相对字号的倍率。
     *
     * Android 默认字体的 `fontMetrics` 上下沿加起来约 1.16~1.2 倍字号，
     * 取 1.25 留一点余量：宁可少算一行，也不要把最后一行压到窗口之外
     * （裁掉半行比少一行难看得多，而且用户会以为「行数设置没生效」）。
     */
    const val FONT_HEIGHT_FACTOR = 1.25f

    /**
     * 单行实际占用的高度（像素），与 `TextView.setLineSpacing(extra, mult)` 同口径：
     * `字高 × 倍率 + 额外间距`。
     */
    fun lineBoxPx(textSizePx: Float, spacingExtraPx: Float, spacingMult: Float): Int {
        val mult = spacingMult.coerceAtLeast(0f)
        val box = textSizePx.coerceAtLeast(1f) * FONT_HEIGHT_FACTOR * mult +
            spacingExtraPx.coerceAtLeast(0f)
        return box.roundToInt().coerceAtLeast(1)
    }

    /**
     * 能放下几行。
     *
     * @param availablePx 窗口（面板）高度
     * @param lineBoxPx 单行占用高度，见 [lineBoxPx]
     * @param chromePx 与行数无关的固定开销：上下内边距、标题两行小字、
     *                 以及一句「译文 / 长句折行」的余量
     * @param maxLines 上限（全屏 / 单条各一档）
     * @return 奇数行数，至少 1
     */
    fun fit(
        availablePx: Int,
        lineBoxPx: Int,
        chromePx: Int = 0,
        maxLines: Int = MAX_AUTO_LINES,
    ): Int {
        val box = lineBoxPx.coerceAtLeast(1)
        val usable = (availablePx - chromePx.coerceAtLeast(0)).coerceAtLeast(0)
        var lines = usable / box
        // 偶数行时 compose 会把多出来的那一行放到下方，当前行略偏上。
        // 自动档优先「当前行居中」，所以统一取奇数（1 行是合法结果）。
        if (lines % 2 == 0) lines -= 1
        return lines.coerceIn(1, maxLines.coerceAtLeast(1))
    }
}
