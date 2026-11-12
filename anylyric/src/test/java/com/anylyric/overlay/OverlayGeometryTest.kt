package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 定位模型的纯逻辑测试。
 *
 * 这些用例是为了守住「拖不丢、换屏不偏、字号变大不越界」三条：旧实现里窗口能被拖到
 * 屏幕外并落盘，用户看到的是「悬浮窗消失了」，很难自查。
 */
class OverlayGeometryTest {

    private fun frame(): OverlayGeometry.Frame = OverlayGeometry.frame(
        screenW = 1000,
        screenH = 600,
        insetLeft = 0,
        insetTop = 24,
        insetRight = 0,
        insetBottom = 16,
        pad = 10
    )

    @Test
    fun clampsRatioIntoUnitRange() {
        assertEquals(0f, OverlayGeometry.clampRatio(-3f), 0.0001f)
        assertEquals(1f, OverlayGeometry.clampRatio(9f), 0.0001f)
        assertEquals(0.5f, OverlayGeometry.clampRatio(0.5f), 0.0001f)
        assertEquals(0f, OverlayGeometry.clampRatio(Float.NaN), 0.0001f)
    }

    @Test
    fun frameRespectsInsetsAndPadding() {
        val box = frame()
        assertEquals(10, box.left)
        assertEquals(34, box.top)
        assertEquals(990, box.right)
        assertEquals(574, box.bottom)
        assertEquals(980, box.width)
        assertEquals(540, box.height)
    }

    /** 系统栏把可用区压没时不能返回倒置矩形，否则后面算 span 会得到负数。 */
    @Test
    fun frameNeverInverts() {
        val box = OverlayGeometry.frame(100, 100, 200, 200, 200, 200, 40)
        assertEquals(100, box.left)
        assertEquals(100, box.right)
        assertEquals(0, box.width)
        assertEquals(0, box.height)
    }

    @Test
    fun originMapsRatioOverWholeSpan() {
        val box = frame()
        assertEquals(10, OverlayGeometry.originX(box, 100, 0f))
        assertEquals(450, OverlayGeometry.originX(box, 100, 0.5f))
        assertEquals(890, OverlayGeometry.originX(box, 100, 1f))
    }

    @Test
    fun oversizedViewSticksToStartInsteadOfGoingNegative() {
        val box = frame()
        assertEquals(10, OverlayGeometry.originX(box, 2000, 0.5f))
        assertEquals(34, OverlayGeometry.originY(box, 2000, 0.5f))
    }

    @Test
    fun ratioRoundTripKeepsPosition() {
        val box = frame()
        val x = OverlayGeometry.originX(box, 200, 0.37f)
        val ratio = OverlayGeometry.ratioOf(x, box.left, box.width - 200)
        assertEquals(0.37f, ratio, 0.01f)
    }

    @Test
    fun ratioOfZeroSpanIsZero() {
        assertEquals(0f, OverlayGeometry.ratioOf(500, 10, 0), 0.0001f)
    }

    @Test
    fun clampsOriginInsideFrame() {
        assertEquals(10, OverlayGeometry.clampOrigin(-50, 10, 880))
        assertEquals(890, OverlayGeometry.clampOrigin(5000, 10, 880))
        assertEquals(400, OverlayGeometry.clampOrigin(400, 10, 880))
    }

    @Test
    fun snapsOnlyNearEdges() {
        val box = frame()
        val span = box.width - 200
        assertEquals(box.left, OverlayGeometry.snap(box.left + 10, box.left, span, 60))
        assertEquals(box.left + span, OverlayGeometry.snap(box.left + span - 10, box.left, span, 60))
        assertEquals(box.left + 300, OverlayGeometry.snap(box.left + 300, box.left, span, 60))
    }

    @Test
    fun anchorGridMapping() {
        assertEquals(0f, OverlayGeometry.anchorRatio(0), 0.0001f)
        assertEquals(0.5f, OverlayGeometry.anchorRatio(1), 0.0001f)
        assertEquals(1f, OverlayGeometry.anchorRatio(2), 0.0001f)

        assertEquals(0, OverlayGeometry.nearestAnchor(0.1f))
        assertEquals(1, OverlayGeometry.nearestAnchor(0.5f))
        assertEquals(2, OverlayGeometry.nearestAnchor(0.9f))
    }
}
