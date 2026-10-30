package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroOutroSkipTest {

    @Test
    fun clampSeconds() {
        assertEquals(0, IntroOutroSkip.clampSeconds(-3))
        assertEquals(120, IntroOutroSkip.clampSeconds(999))
        assertEquals(45, IntroOutroSkip.clampSeconds(45))
    }

    @Test
    fun introOffOrZero() {
        assertNull(IntroOutroSkip.introSeekMs(0, 180_000L))
        assertNull(IntroOutroSkip.introSeekMs(-1, 180_000L))
    }

    @Test
    fun introNormal() {
        assertEquals(15_000L, IntroOutroSkip.introSeekMs(15, 180_000L))
    }

    @Test
    fun introTooShortTrack() {
        // 20s intro on 21s track → would leave <1s
        assertNull(IntroOutroSkip.introSeekMs(20, 21_000L))
        assertEquals(10_000L, IntroOutroSkip.introSeekMs(10, 20_000L))
    }

    @Test
    fun introUnknownDurationStillReturns() {
        assertEquals(12_000L, IntroOutroSkip.introSeekMs(12, 0L))
    }

    @Test
    fun outroOff() {
        assertFalse(IntroOutroSkip.shouldSkipOutro(170_000L, 180_000L, 0))
    }

    @Test
    fun outroTriggersNearEnd() {
        assertTrue(IntroOutroSkip.shouldSkipOutro(175_000L, 180_000L, 10))
        assertFalse(IntroOutroSkip.shouldSkipOutro(160_000L, 180_000L, 10))
    }

    @Test
    fun outroTooShortTrackNeverFires() {
        assertFalse(IntroOutroSkip.shouldSkipOutro(1_000L, 8_000L, 10))
        assertFalse(IntroOutroSkip.shouldSkipOutro(7_500L, 8_000L, 10))
    }

    @Test
    fun shouldApplyIntroOnceAndNearStart() {
        assertEquals(
            10_000L,
            IntroOutroSkip.shouldApplyIntro(false, 200L, 10, 200_000L),
        )
        assertNull(IntroOutroSkip.shouldApplyIntro(true, 200L, 10, 200_000L))
        // 用户已拖过片头
        assertNull(IntroOutroSkip.shouldApplyIntro(false, 30_000L, 10, 200_000L))
    }
}
