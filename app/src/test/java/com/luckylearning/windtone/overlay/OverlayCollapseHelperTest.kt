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
    fun handleXStaysLeftOfWindowAndClampsToScreen() {
        val mid = OverlayCollapseHelper.handlePositions(
            windowX = 200,
            handleSizePx = 48,
            gapPx = 8,
            edgeGapPx = 4,
            screenW = 1080,
            anchorY = 40,
        )
        assertEquals(200 - 48 - 4, mid.x)
        assertEquals(40, mid.collapseY)
        assertEquals(40 + 48 + 8, mid.playY)

        val flushLeft = OverlayCollapseHelper.handlePositions(
            windowX = 0,
            handleSizePx = 48,
            gapPx = 8,
            edgeGapPx = 4,
            screenW = 1080,
            anchorY = 10,
        )
        assertEquals(0, flushLeft.x)
    }
}
