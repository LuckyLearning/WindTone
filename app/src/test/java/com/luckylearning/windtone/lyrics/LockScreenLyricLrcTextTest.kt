package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.2.53：写进系统媒体会话 extras 的「整段 LRC」序列化。
 *
 * 这条链路的问题从来不是格式多复杂，而是**别把上一首的歌词发给下一个接收方** ——
 * 所以除了格式，还钉住「换歌后缓存必须作废」。
 */
class LockScreenLyricLrcTextTest {

    @Before
    fun reset() {
        LockScreenLyricPublisher.reset()
    }

    @Test
    fun `带时间轴的行按 LRC 时间戳输出`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = 0, text = "第一句"),
                LyricLine(timeMs = 65_430, text = "第二句"),
            ),
            synced = true,
        )
        val text = LockScreenLyricPublisher.lrcTextOf(lyrics)
        assertEquals("[00:00.00]第一句\n[01:05.43]第二句\n", text)
    }

    @Test
    fun `无时间轴的行原样输出不带时间戳`() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timeMs = -1, text = "纯文本一"),
                LyricLine(timeMs = -1, text = "纯文本二"),
            ),
            synced = false,
        )
        assertEquals("纯文本一\n纯文本二\n", LockScreenLyricPublisher.lrcTextOf(lyrics))
    }

    @Test
    fun `空歌词返回空串`() {
        assertEquals("", LockScreenLyricPublisher.lrcTextOf(Lyrics()))
    }

    @Test
    fun `换歌后不会沿用上一首的序列化结果`() {
        val first = Lyrics(
            lines = listOf(LyricLine(timeMs = 0, text = "旧的")),
            synced = true,
        )
        assertTrue(LockScreenLyricPublisher.lrcTextOf(first).contains("旧的"))
        LockScreenLyricPublisher.reset()
        val second = Lyrics(
            lines = listOf(LyricLine(timeMs = 0, text = "新的")),
            synced = true,
        )
        val text = LockScreenLyricPublisher.lrcTextOf(second)
        assertTrue("换歌后仍返回旧歌词：$text", text.contains("新的"))
        assertTrue("换歌后仍混入旧歌词：$text", !text.contains("旧的"))
    }

    @Test
    fun `毫秒向下取整到百分秒`() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine(timeMs = 1_009, text = "边界")),
            synced = true,
        )
        assertTrue(LockScreenLyricPublisher.lrcTextOf(lyrics).startsWith("[00:01.00]"))
    }
}
