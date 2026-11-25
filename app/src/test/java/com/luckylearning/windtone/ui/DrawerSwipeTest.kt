package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerSwipeTest {

    private val slop = 16f

    @Test
    fun fingerDownDoesNotDecide() {
        assertEquals(DrawerSwipe.Decision.PENDING, DrawerSwipe.decide(4f, 4f, slop))
    }

    @Test
    fun verticalWinsEvenWithSomeHorizontalDrift() {
        // 排行榜上手指很难完全垂直。纵向更大时必须让给列表，不能把后面的位移吃掉。
        assertEquals(DrawerSwipe.Decision.YIELD, DrawerSwipe.decide(20f, 80f, slop))
        assertEquals(DrawerSwipe.Decision.YIELD, DrawerSwipe.decide(-30f, 40f, slop))
        assertEquals(DrawerSwipe.Decision.YIELD, DrawerSwipe.decide(40f, 40f, slop))
    }

    @Test
    fun rightwardDominantSwipeOpensTheDrawer() {
        assertEquals(DrawerSwipe.Decision.OPEN, DrawerSwipe.decide(80f, 10f, slop))
    }

    @Test
    fun leftwardSwipeDoesNotOpenTheDrawer() {
        assertEquals(DrawerSwipe.Decision.YIELD, DrawerSwipe.decide(-80f, 10f, slop))
    }
}
