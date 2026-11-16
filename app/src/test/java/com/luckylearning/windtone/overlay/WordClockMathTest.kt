package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐字进度本地外推：零 binder、暂停冻结、陈旧时钟不外推。 */
class WordClockMathTest {

    @Test
    fun `播放中按本地经过时间外推`() {
        val clock = LyricOverlayBus.WordClock(progressMs = 10_000L, atRealtimeMs = 1_000L, playing = true)
        assertEquals(10_060L, WordClockMath.extrapolate(clock, nowRealtime = 1_060L))
    }

    @Test
    fun `暂停时冻结在推来的进度`() {
        val clock = LyricOverlayBus.WordClock(progressMs = 10_000L, atRealtimeMs = 1_000L, playing = false)
        assertEquals(10_000L, WordClockMath.extrapolate(clock, nowRealtime = 9_000L))
    }

    @Test
    fun `时钟回拨不外推`() {
        val clock = LyricOverlayBus.WordClock(progressMs = 10_000L, atRealtimeMs = 1_000L, playing = true)
        assertEquals(10_000L, WordClockMath.extrapolate(clock, nowRealtime = 900L))
    }

    @Test
    fun `超过2秒没更新不外推`() {
        val clock = LyricOverlayBus.WordClock(progressMs = 10_000L, atRealtimeMs = 1_000L, playing = true)
        assertEquals(10_000L, WordClockMath.extrapolate(clock, nowRealtime = 3_001L))
    }

    @Test
    fun `无进度返回负一`() {
        val clock = LyricOverlayBus.WordClock(progressMs = -1L, playing = true)
        assertEquals(-1L, WordClockMath.extrapolate(clock, 1_000L))
    }
}
