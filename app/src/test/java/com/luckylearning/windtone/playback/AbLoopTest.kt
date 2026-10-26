package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopTest {

    @Test
    fun `markA then markB arms loop`() {
        val s = AbLoopState().markA(1_000L).markB(5_000L)
        assertTrue(s.armed)
        assertEquals(1_000L, s.aMs)
        assertEquals(5_000L, s.bMs)
    }

    @Test
    fun `markB before A is ignored`() {
        val s = AbLoopState().markB(3_000L)
        assertFalse(s.armed)
        assertNull(s.aMs)
        assertNull(s.bMs)
    }

    @Test
    fun `markB not after A keeps state`() {
        val s = AbLoopState().markA(5_000L).markB(4_000L)
        assertEquals(5_000L, s.aMs)
        assertNull(s.bMs)
        assertFalse(s.canMarkB(4_000L))
        assertTrue(s.canMarkB(5_001L))
    }

    @Test
    fun `markA after B drops B when inverted`() {
        val s = AbLoopState().markA(1_000L).markB(5_000L).markA(6_000L)
        assertEquals(6_000L, s.aMs)
        assertNull(s.bMs)
    }

    @Test
    fun `seekIfNeeded returns A at or past B`() {
        val s = AbLoopState().markA(100L).markB(200L)
        assertNull(s.seekIfNeeded(150L))
        assertEquals(100L, s.seekIfNeeded(200L))
        assertEquals(100L, s.seekIfNeeded(250L))
    }

    @Test
    fun `fromInterval is D1 extension point`() {
        val s = AbLoopState.fromInterval(10L, 90L)
        assertTrue(s.armed)
        assertEquals(10L, s.seekIfNeeded(90L))
        assertFalse(AbLoopState.fromInterval(90L, 10L).armed)
    }

    @Test
    fun `clear resets`() {
        val s = AbLoopState().markA(1L).markB(2L).clear()
        assertNull(s.aMs)
        assertNull(s.bMs)
    }

    @Test
    fun `D1 lyric line interval arms and seeks`() {
        val s = AbLoopState.fromInterval(12_000L, 15_500L)
        assertTrue(s.armed)
        assertNull(s.seekIfNeeded(14_000L))
        assertEquals(12_000L, s.seekIfNeeded(15_500L))
    }
}
