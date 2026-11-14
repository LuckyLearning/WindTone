package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宽高滑杆的档位换算。
 *
 * 这类 bug 在真机上是「滑一点点跳一大格」「进来时滑杆位置和显示的数字对不上」，
 * 现场很难判断是滑杆还是落盘的问题，只能把往返换算钉死。
 */
class SizeSliderTest {

    private val minPx = 200
    private val maxPx = 1000

    @Test
    fun minPixelSitsOnFirstStep() {
        assertEquals(1, SizeSlider.progressOf(minPx, minPx, maxPx))
    }

    @Test
    fun maxPixelSitsOnLastStep() {
        assertEquals(SizeSlider.STEPS, SizeSlider.progressOf(maxPx, minPx, maxPx))
    }

    @Test
    fun progressZeroMeansAuto() {
        // 0 档不是「最小比例」而是自适应，换算里必须单独处理，不能被当成 1 档。
        assertEquals(minPx, SizeSlider.pxOf(SizeSlider.AUTO, minPx, maxPx))
        assertEquals(1, SizeSlider.progressOf(minPx - 500, minPx, maxPx))
    }

    @Test
    fun roundTripKeepsStep() {
        for (step in 1..SizeSlider.STEPS) {
            val px = SizeSlider.pxOf(step, minPx, maxPx)
            assertEquals("step $step", step, SizeSlider.progressOf(px, minPx, maxPx))
        }
    }

    @Test
    fun progressIsMonotonic() {
        var last = -1
        for (step in 1..SizeSlider.STEPS) {
            val px = SizeSlider.pxOf(step, minPx, maxPx)
            assertTrue("step $step px $px", px >= last)
            last = px
        }
    }

    @Test
    fun outOfRangeProgressClamps() {
        assertEquals(minPx, SizeSlider.pxOf(-5, minPx, maxPx))
        assertEquals(maxPx, SizeSlider.pxOf(SizeSlider.STEPS + 50, minPx, maxPx))
    }

    /** 退化区间（可用区还没测量出来时 min == max）不能除零，也不能返回 0。 */
    @Test
    fun degenerateRangeIsSafe() {
        assertEquals(1, SizeSlider.progressOf(300, 300, 300))
        assertEquals(300, SizeSlider.pxOf(7, 300, 300))
    }
}
