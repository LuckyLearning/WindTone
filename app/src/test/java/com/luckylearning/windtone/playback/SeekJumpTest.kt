package com.luckylearning.windtone.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekJumpTest {

    @Test
    fun presetsAreTenAndFifteen() {
        assertArrayEquals(intArrayOf(10, 15), SeekJump.PRESET_SECONDS)
    }

    @Test
    fun deltaMsConvertsSeconds() {
        assertEquals(10_000L, SeekJump.deltaMs(10))
        assertEquals(15_000L, SeekJump.deltaMs(15))
        assertEquals(-10_000L, SeekJump.deltaMs(-10))
    }

    @Test
    fun forwardWithinRange() {
        assertEquals(25_000L, SeekJump.target(10_000L, 60_000L, 15_000L))
        assertEquals(20_000L, SeekJump.target(10_000L, 60_000L, 10_000L))
    }

    @Test
    fun backwardWithinRange() {
        assertEquals(5_000L, SeekJump.target(20_000L, 60_000L, -15_000L))
        assertEquals(10_000L, SeekJump.target(20_000L, 60_000L, -10_000L))
    }

    @Test
    fun clampsToZeroOnRewindPastStart() {
        assertEquals(0L, SeekJump.target(5_000L, 60_000L, -15_000L))
        assertEquals(0L, SeekJump.target(0L, 60_000L, -10_000L))
    }

    @Test
    fun clampsToDurationOnForwardPastEnd() {
        assertEquals(60_000L, SeekJump.target(55_000L, 60_000L, 15_000L))
        assertEquals(60_000L, SeekJump.target(60_000L, 60_000L, 10_000L))
    }

    @Test
    fun unknownDurationOnlyAllowsZero() {
        assertEquals(0L, SeekJump.target(12_000L, 0L, 10_000L))
        assertEquals(0L, SeekJump.target(12_000L, -1L, -5_000L))
        assertEquals(0L, SeekJump.target(-100L, 0L, 10_000L))
    }

    @Test
    fun negativePositionTreatedAsZero() {
        assertEquals(10_000L, SeekJump.target(-5_000L, 60_000L, 10_000L))
    }
}
