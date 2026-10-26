package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1：逐句区间 → AbLoopState.fromInterval */
class LyricsIntervalTest {

    private fun synced(vararg times: Long) = Lyrics(
        lines = times.mapIndexed { i, t -> LyricLine(t, "line$i") },
        synced = true,
    )

    @Test
    fun `中间句取下一句起点为结束`() {
        val lyrics = synced(1000L, 3000L, 5000L)
        val pair = lyrics.lineIntervalMs(1)!!
        assertEquals(3000L, pair.first)
        assertEquals(5000L, pair.second)
    }

    @Test
    fun `末句用歌曲时长`() {
        val lyrics = synced(1000L, 3000L)
        val pair = lyrics.lineIntervalMs(1, fallbackEndMs = 12_000L)!!
        assertEquals(3000L, pair.first)
        assertEquals(12_000L, pair.second)
    }

    @Test
    fun `末句无时长则 5 秒兜底`() {
        val lyrics = synced(1000L)
        val pair = lyrics.lineIntervalMs(0)!!
        assertEquals(1000L, pair.first)
        assertEquals(6000L, pair.second)
    }

    @Test
    fun `非同步或越界返回 null`() {
        assertNull(Lyrics(lines = listOf(LyricLine(1L, "a")), synced = false).lineIntervalMs(0))
        assertNull(synced(1L).lineIntervalMs(9))
        assertNull(Lyrics(lines = listOf(LyricLine(-1L, "plain")), synced = true).lineIntervalMs(0))
    }

    @Test
    fun `fromInterval 与 lineInterval 可武装`() {
        val pair = synced(10L, 90L).lineIntervalMs(0)!!
        val loop = com.luckylearning.windtone.playback.AbLoopState.fromInterval(pair.first, pair.second)
        assertTrue(loop.armed)
        assertEquals(10L, loop.seekIfNeeded(90L))
    }
}
