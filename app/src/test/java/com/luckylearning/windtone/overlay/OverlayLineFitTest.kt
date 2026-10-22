package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行数自适应（v1.1.49）单测。
 *
 * 这段算法决定「窗口里塞得下几行」。算大了最后一行会被窗口裁掉 —— 表现为
 * 「设了 5 行只看到 4 行」，用户只会判定成设置没生效；算小了白白浪费空间。
 * 纯函数，用普通 JVM 就能钉死，不需要起模拟器。
 */
class OverlayLineFitTest {

    // ------------------------------------------------------------ 单行高度

    @Test
    fun `单行高度 = 字高×倍率 + 额外间距`() {
        // 40px 字、无额外间距、倍率 1（单条字幕的默认档）：默认字体行高约 1.25 倍字号
        assertEquals(50, OverlayLineFit.lineBoxPx(40f, 0f, 1f))
        // 全屏档：倍率 1.2 + 额外 10px
        assertEquals(70, OverlayLineFit.lineBoxPx(40f, 10f, 1.2f))
    }

    @Test
    fun `非法字号与负间距会被兜住`() {
        assertEquals(1, OverlayLineFit.lineBoxPx(0f, 0f, 1f))
        assertEquals(50, OverlayLineFit.lineBoxPx(40f, -100f, 1f))
    }

    // ------------------------------------------------------------ 能放几行

    @Test
    fun `高度除以行高取奇数，当前行才能居中`() {
        // 1000 / 100 = 10 行，取奇数为 9（偶数行时 compose 会把多的一行放下面）
        assertEquals(9, OverlayLineFit.fit(availablePx = 1000, lineBoxPx = 100))
        assertEquals(3, OverlayLineFit.fit(availablePx = 400, lineBoxPx = 100))
        assertEquals(5, OverlayLineFit.fit(availablePx = 600, lineBoxPx = 100))
    }

    @Test
    fun `连一行都放不下时仍然画一行`() {
        // 宁可让这一行被裁一点，也不能返回 0 行（compose 会退化成空列表）
        assertEquals(1, OverlayLineFit.fit(availablePx = 50, lineBoxPx = 100))
        assertEquals(1, OverlayLineFit.fit(availablePx = 0, lineBoxPx = 100))
    }

    @Test
    fun `两行也要取奇数`() {
        assertEquals(1, OverlayLineFit.fit(availablePx = 250, lineBoxPx = 100))
    }

    @Test
    fun `固定开销会先被扣掉`() {
        // 1000 − 400 = 600 → 6 行 → 取奇数 5
        assertEquals(
            5,
            OverlayLineFit.fit(availablePx = 1000, lineBoxPx = 100, chromePx = 400),
        )
        // 开销比窗口还大：夹到 1 行，不要出现负数
        assertEquals(
            1,
            OverlayLineFit.fit(availablePx = 300, lineBoxPx = 100, chromePx = 900),
        )
    }

    @Test
    fun `自动档上限是宽松安全网，行数主要由高度决定`() {
        // 高度充裕时顶到上限（v1.1.50 前 7/11 两档会在手机竖屏上留大片空白）
        assertEquals(
            OverlayLineFit.MAX_AUTO_LINES,
            OverlayLineFit.fit(availablePx = 4000, lineBoxPx = 100),
        )
        assertEquals(1, OverlayLineFit.fit(availablePx = 1000, lineBoxPx = 100, maxLines = 0))
    }

    @Test
    fun `自动档在高度自动时的兜底值是 3 行`() {
        // 「上一行 / 当前 / 下一行」—— 能看出上下文、又不会把字幕撑成一块板
        assertEquals(3, OverlayLineFit.STRIP_FALLBACK_LINES)
    }

    // ------------------------------------------------------------ 真实屏尺寸

    @Test
    fun `车机全屏面板高度拉满时给 15 行（不再被 11 档截住）`() {
        // 字 20sp × 1.5 = 30px；行高 30×1.25×1.2 + 15 = 60px；可用高度 1080 − 84（Dock）
        val box = OverlayLineFit.lineBoxPx(30f, 15f, 1.2f)
        val lines = OverlayLineFit.fit(
            availablePx = 996,
            lineBoxPx = box,
            chromePx = 24,
            maxLines = OverlayLineFit.MAX_AUTO_LINES,
        )
        // 996 − 24 = 972 → 16.2 → 16 行 → 取奇数 15（v1.1.50 前被 11 档截住）
        assertEquals(15, lines)
    }

    @Test
    fun `面板调矮到三成高度时行数降下来，不会被裁`() {
        // 与上一条同一台车机，面板高度 30%：996 × 0.3 ≈ 298px
        val box = OverlayLineFit.lineBoxPx(30f, 15f, 1.2f)
        val lines = OverlayLineFit.fit(
            availablePx = 298,
            lineBoxPx = box,
            chromePx = 24,
            maxLines = OverlayLineFit.MAX_AUTO_LINES,
        )
        // 298 − 24 = 274 → 4 行 → 取奇数 3
        assertEquals(3, lines)
    }

    @Test
    fun `手机竖屏单条高度拉满时行数用满窗口（回归上一版只给 7 行）`() {
        // 1080×2340 / 440dpi：20sp = 55px；行高 55×1.25×1 + dp2 = 74px
        val box = OverlayLineFit.lineBoxPx(55f, 5.5f, 1f)
        // 固定开销：上下内边距 44 + 标题两行小字 107 + 一句译文余量 74
        val lines = OverlayLineFit.fit(
            availablePx = 2000,
            lineBoxPx = box,
            chromePx = 225,
            maxLines = OverlayLineFit.MAX_AUTO_LINES,
        )
        // (2000 − 225) / 74 ≈ 23.9 → 23 行 —— v1.1.49 在这里被 7 档截住
        assertEquals(23, lines)
    }
}
