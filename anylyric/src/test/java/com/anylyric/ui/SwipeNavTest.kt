package com.anylyric.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滑页阈值。
 *
 * 这些数字就是手感本身：改大了「划不动」，改小了「一碰就翻」。用纯函数锁住，
 * 免得以后调手感时顺手把边界也调坏。
 */
class SwipeNavTest {

    private val width = 1000

    @Test
    fun nothingWhenBarelyMoved() {
        assertEquals(0, SwipeNav.decide(dx = -40f, width = width, velocityX = 0f))
    }

    @Test
    fun farEnoughSwipesToNextPage() {
        // 手指往左推 = 看下一张
        assertEquals(1, SwipeNav.decide(dx = -300f, width = width, velocityX = 0f))
    }

    @Test
    fun farEnoughSwipesBack() {
        assertEquals(-1, SwipeNav.decide(dx = 300f, width = width, velocityX = 0f))
    }

    @Test
    fun quickFlickCountsEvenWhenShort() {
        assertEquals(1, SwipeNav.decide(dx = -60f, width = width, velocityX = -1_400f))
    }

    @Test
    fun quickFlickBackCounts() {
        assertEquals(-1, SwipeNav.decide(dx = 60f, width = width, velocityX = 1_400f))
    }

    @Test
    fun jitterIsNotASwipe() {
        // 速度再大，手指其实没动（按下即抬），不能翻页
        assertEquals(0, SwipeNav.decide(dx = -8f, width = width, velocityX = -5_000f))
    }

    @Test
    fun unclearWidthNeverSwipes() {
        assertEquals(0, SwipeNav.decide(dx = -400f, width = 0, velocityX = -3_000f))
    }
}
