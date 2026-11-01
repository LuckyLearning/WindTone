package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A4：句内时间窗 — 不依赖 Player，只钉 [LockScreenLyricPublisher.rememberHoldWindow]。
 */
class LockScreenLyricPublisherHoldTest {

    @Before
    fun reset() {
        LockScreenLyricPublisher.reset()
    }

    @Test
    fun `同步歌词落在某句时 until 为下一句起点`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 0, text = "A"),
                LyricLine(timeMs = 5_000, text = "B"),
                LyricLine(timeMs = 10_000, text = "C"),
            ),
            synced = true,
        )
        LockScreenLyricPublisher.rememberHoldWindow(lyrics, adjustedMs = 1_000)
        assertEquals(0L, LockScreenLyricPublisher.debugHoldFrom())
        assertEquals(5_000L, LockScreenLyricPublisher.debugHoldUntil())
    }

    @Test
    fun `前奏 until 为第一句起点`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 3_000, text = "首句"),
                LyricLine(timeMs = 8_000, text = "次句"),
            ),
            synced = true,
        )
        LockScreenLyricPublisher.rememberHoldWindow(lyrics, adjustedMs = 500)
        assertTrue(LockScreenLyricPublisher.debugHoldFrom() < 0 || LockScreenLyricPublisher.debugHoldFrom() == Long.MIN_VALUE)
        assertEquals(3_000L, LockScreenLyricPublisher.debugHoldUntil())
    }

    @Test
    fun `无时间轴 until 很大 切歌 reset 清掉`() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine(timeMs = 0, text = "纯文本")),
            synced = false,
        )
        LockScreenLyricPublisher.rememberHoldWindow(lyrics, adjustedMs = 0)
        assertTrue(LockScreenLyricPublisher.debugHoldUntil() > 1_000_000L)
        LockScreenLyricPublisher.reset()
        assertEquals(-1L, LockScreenLyricPublisher.debugHoldUntil())
    }
}
