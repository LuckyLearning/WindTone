package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OverlayCollapseHelper]：收起态 / 侧钮几何纯函数（从 LyricOverlayService 第一刀抽出）。
 */
class OverlayCollapseHelperTest {

    private val wrapContent = -2 // WindowManager.LayoutParams.WRAP_CONTENT

    @Test
    fun collapsedWindowPrefersMeasuredHeightThenFixedLayoutHeight() {
        val fromMeasured = OverlayCollapseHelper.collapsedWindow(
            measuredHeightPx = 180,
            layoutHeightPx = wrapContent,
            wrapContentSentinel = wrapContent,
            collapsedWidthPx = 24,
            fallbackHeightPx = 120,
        )
        assertEquals(24, fromMeasured.widthPx)
        assertEquals(180, fromMeasured.heightPx)
        assertEquals(0, fromMeasured.x)

        val fromLayout = OverlayCollapseHelper.collapsedWindow(
            measuredHeightPx = 0,
            layoutHeightPx = 200,
            wrapContentSentinel = wrapContent,
            collapsedWidthPx = 24,
            fallbackHeightPx = 120,
        )
        assertEquals(200, fromLayout.heightPx)

        val fromFallback = OverlayCollapseHelper.collapsedWindow(
            measuredHeightPx = 0,
            layoutHeightPx = wrapContent,
            wrapContentSentinel = wrapContent,
            collapsedWidthPx = 24,
            fallbackHeightPx = 120,
        )
        assertEquals(120, fromFallback.heightPx)
    }

    @Test
    fun handleAnchorOnlyRecalculatesWhenWindowYChanges() {
        val first = OverlayCollapseHelper.resolveHandleAnchor(
            windowY = 100,
            boxHeightPx = 200,
            totalHandlesHeightPx = 104,
            screenH = 800,
            currentAnchorY = -1,
            currentAnchorFromWindowY = Int.MIN_VALUE,
        )
        // centered = 100 + (200-104)/2 = 148
        assertEquals(148, first.anchorY)
        assertEquals(100, first.anchorFromWindowY)

        val sameY = OverlayCollapseHelper.resolveHandleAnchor(
            windowY = 100,
            boxHeightPx = 300,
            totalHandlesHeightPx = 104,
            screenH = 800,
            currentAnchorY = first.anchorY,
            currentAnchorFromWindowY = first.anchorFromWindowY,
        )
        assertEquals(148, sameY.anchorY)

        val moved = OverlayCollapseHelper.resolveHandleAnchor(
            windowY = 220,
            boxHeightPx = 200,
            totalHandlesHeightPx = 104,
            screenH = 800,
            currentAnchorY = first.anchorY,
            currentAnchorFromWindowY = first.anchorFromWindowY,
        )
        assertEquals(220, moved.anchorFromWindowY)
        assertEquals(220 + (200 - 104) / 2, moved.anchorY)
    }

    @Test
    fun `三颗钮按尺寸加间隙依次下排`() {
        val column = OverlayCollapseHelper.handleColumn(
            windowX = 200,
            handleSizePx = 48,
            gapPx = 8,
            edgeGapPx = 4,
            screenW = 1080,
            anchorY = 100,
            count = 3,
        )
        assertEquals(200 - 48 - 4, column.x)
        assertEquals(listOf(100, 156, 212), column.ys)
    }

    @Test
    fun `钮数为一颗时不产生额外间距`() {
        val column = OverlayCollapseHelper.handleColumn(
            windowX = 0,
            handleSizePx = 48,
            gapPx = 8,
            edgeGapPx = 4,
            screenW = 1080,
            anchorY = 10,
            count = 1,
        )
        assertEquals(listOf(10), column.ys)
        assertEquals(0, column.x)
    }

    @Test
    fun `窗口贴右缘时钮被夹回屏内`() {
        val column = OverlayCollapseHelper.handleColumn(
            windowX = 1080,
            handleSizePx = 48,
            gapPx = 8,
            edgeGapPx = 4,
            screenW = 1080,
            anchorY = 0,
            count = 2,
        )
        // x 先按「窗口左边 − 钮宽 − 间隙」算，再夹到 [0, 屏宽−钮宽]；
        // 贴右缘时结果是 1080 − 48 − 4 = 1028（不是贴到 1032）
        assertEquals(1080 - 48 - 4, column.x)
    }

    @Test
    fun `侧钮掩码与整体开关、车载模式联动`() {
        // 全开四颗（1+2+4+8）
        assertEquals(15, OverlayCollapseHelper.handleMask(true, true, true, true, true, true))
        // 旧三颗（不传 a11y，默认 false）仍为 7
        assertEquals(7, OverlayCollapseHelper.handleMask(true, true, true, true, true))
        // 只留播放暂停
        assertEquals(2, OverlayCollapseHelper.handleMask(true, true, false, true, false))
        // 只开无障碍
        assertEquals(8, OverlayCollapseHelper.handleMask(true, true, false, false, false, true))
        // 手机模式：一颗都不显示
        assertEquals(0, OverlayCollapseHelper.handleMask(false, true, true, true, true, true))
        // 整体开关关掉：即使单颗都开着也不显示
        assertEquals(0, OverlayCollapseHelper.handleMask(true, false, true, true, true, true))
        // 全关
        assertEquals(0, OverlayCollapseHelper.handleMask(true, true, false, false, false, false))
    }

    @Test
    fun `侧钮顺序默认含无障碍第四颗`() {
        val order = OverlayCollapseHelper.DEFAULT_HANDLE_ORDER
        assertEquals(
            listOf(
                OverlayCollapseHelper.OverlayHandleKind.COLLAPSE,
                OverlayCollapseHelper.OverlayHandleKind.PLAY_PAUSE,
                OverlayCollapseHelper.OverlayHandleKind.PLAYER,
                OverlayCollapseHelper.OverlayHandleKind.A11Y,
            ),
            order,
        )
        assertEquals("collapse,play_pause,player,a11y", OverlayCollapseHelper.handleOrderCsv())
    }

    @Test
    fun `mergeHandleOrder 纠正未知项并补齐缺项`() {
        val merged = OverlayCollapseHelper.mergeHandleOrder(
            listOf("player", "bogus", "collapse"),
        )
        // 缺的 play_pause / a11y 按默认相对位置补齐（与 SettingsMenuOrder.merge 同口径）
        assertEquals(
            listOf(
                OverlayCollapseHelper.OverlayHandleKind.PLAY_PAUSE,
                OverlayCollapseHelper.OverlayHandleKind.PLAYER,
                OverlayCollapseHelper.OverlayHandleKind.COLLAPSE,
                OverlayCollapseHelper.OverlayHandleKind.A11Y,
            ),
            merged,
        )
    }

    @Test
    fun `visibleHandles 按用户顺序筛掩码`() {
        val order = OverlayCollapseHelper.mergeHandleOrder(
            listOf("player", "collapse", "play_pause"),
        )
        // 只开收起+播放页 = 1+4
        val visible = OverlayCollapseHelper.visibleHandles(order, mask = 1 or 4)
        assertEquals(
            listOf(
                OverlayCollapseHelper.OverlayHandleKind.PLAYER,
                OverlayCollapseHelper.OverlayHandleKind.COLLAPSE,
            ),
            visible,
        )
    }

    @Test
    fun `上移下移等价于 CSV 重排并可 roundtrip`() {
        // 模拟设置页「把第 3 颗上移」：player 提到第二位（a11y 由 merge 补在末尾）
        val start = OverlayCollapseHelper.mergeHandleOrder(listOf("collapse", "play_pause", "player"))
        val ids = start.map { it.id }.toMutableList()
        val moved = ids.removeAt(2)
        ids.add(1, moved)
        val csv = ids.joinToString(",")
        val again = OverlayCollapseHelper.mergeHandleOrder(csv.split(','))
        assertEquals(
            listOf(
                OverlayCollapseHelper.OverlayHandleKind.COLLAPSE,
                OverlayCollapseHelper.OverlayHandleKind.PLAYER,
                OverlayCollapseHelper.OverlayHandleKind.PLAY_PAUSE,
                OverlayCollapseHelper.OverlayHandleKind.A11Y,
            ),
            again,
        )
        assertEquals(csv, OverlayCollapseHelper.handleOrderCsv(again))
    }

    @Test
    fun `可见掩码含无障碍时按顺序出现`() {
        val order = OverlayCollapseHelper.DEFAULT_HANDLE_ORDER
        val visible = OverlayCollapseHelper.visibleHandles(order, mask = 8 or 1)
        assertEquals(
            listOf(
                OverlayCollapseHelper.OverlayHandleKind.COLLAPSE,
                OverlayCollapseHelper.OverlayHandleKind.A11Y,
            ),
            visible,
        )
    }
}

