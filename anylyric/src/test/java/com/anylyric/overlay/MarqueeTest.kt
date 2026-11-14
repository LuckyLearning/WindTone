package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单行滚动的边界值。
 *
 * 这些数字在车机上肉眼几乎无法校验：滚太快/太慢、首尾粘连、回来时跳一下，
 * 看起来都像「动画有点怪」。把「什么时候该滚、滚一轮多久、某一刻在哪」钉成纯函数，
 * 回归时才有东西可对。
 */
class MarqueeTest {

    @Test
    fun shortLineDoesNotScroll() {
        assertFalse(Marquee.needsScroll(textWidth = 300f, contentWidth = 500f))
    }

    /** 差不到 1px 当放得下：为了半像素一直滚，看起来像文字在抖。 */
    @Test
    fun subPixelOverflowDoesNotScroll() {
        assertFalse(Marquee.needsScroll(textWidth = 500.6f, contentWidth = 500f))
    }

    @Test
    fun longLineScrolls() {
        assertTrue(Marquee.needsScroll(textWidth = 900f, contentWidth = 500f))
    }

    /** 没量到宽度（0）时不能判定为「要滚」，否则会起一个没有终点的动画。 */
    @Test
    fun zeroContentWidthNeverScrolls() {
        assertFalse(Marquee.needsScroll(textWidth = 900f, contentWidth = 0f))
    }

    @Test
    fun gapHasFloor() {
        // 比例算出来只有 14px，下限必须把它抬到 28px，否则首尾两句会粘在一起
        assertEquals(28f, Marquee.gap(contentWidth = 100f), 0.001f)
        assertEquals(140f, Marquee.gap(contentWidth = 1000f), 0.001f)
    }

    @Test
    fun distanceIsTextPlusGap() {
        assertEquals(900f + Marquee.gap(500f), Marquee.distance(900f, 500f), 0.001f)
    }

    @Test
    fun durationFollowsSpeedAndHasFloor() {
        // 44dp/s 在 density=1 下就是 44px/s：走 944px 约 21.4 秒
        val ms = Marquee.durationMs(900f, 500f, 44f)
        assertEquals((Marquee.distance(900f, 500f) / 44f * 1000f).toLong(), ms)
        // 极短距离不能低于 1.2 秒，否则一轮一闪而过
        assertEquals(1200L, Marquee.durationMs(10f, 5f, 400f))
    }

    @Test
    fun offsetStartsAtZeroAndWraps() {
        val text = 900f
        val width = 500f
        val speed = 44f
        assertEquals(0f, Marquee.offsetAt(0, text, width, speed), 0.001f)

        val duration = Marquee.durationMs(text, width, speed)
        val half = Marquee.offsetAt(duration / 2, text, width, speed)
        assertEquals(Marquee.distance(text, width) / 2f, half, 2f)

        // 一整轮后回到起点，而不是继续往左跑出屏幕
        assertTrue(Marquee.offsetAt(duration * 3, text, width, speed) <= 1.5f)
    }

    /** 速度非法时不能除零，也不能返回负数时长。 */
    @Test
    fun invalidSpeedDoesNotBlowUp() {
        assertEquals(1L, Marquee.durationMs(100f, 50f, 0f))
        assertEquals(1L, Marquee.durationMs(100f, 50f, -3f))
    }
}
