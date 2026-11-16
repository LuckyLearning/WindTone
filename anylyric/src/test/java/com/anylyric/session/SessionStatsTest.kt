package com.anylyric.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatsTest {

    @Test
    fun `hit rate counts only sessions that produced a line`() {
        val stats = SessionStats(capacity = 10)
        // 会话 1：60 秒，第 3 秒出词
        stats.onPlayStart(0)
        stats.onFirstLine(3_000)
        stats.onPlayStop(60_000)
        // 会话 2：60 秒，始终没出词
        stats.onPlayStart(100_000)
        stats.onPlayStop(160_000)

        val s = stats.snapshot()
        assertEquals(2, s.sessions)
        assertEquals(1, s.withLyrics)
        assertEquals(0.5f, s.hitRate, 0.001f)
        assertEquals(3_000L, s.p50FirstLineMs)
    }

    @Test
    fun `sessions shorter than the floor are ignored`() {
        val stats = SessionStats()
        stats.onPlayStart(0)
        stats.onPlayStop(10_000)
        assertEquals(0, stats.snapshot().sessions)
    }

    @Test
    fun `first line is recorded only once`() {
        val stats = SessionStats()
        stats.onPlayStart(0)
        stats.onFirstLine(2_000)
        stats.onFirstLine(9_000)
        stats.onPlayStop(60_000)
        assertEquals(2_000L, stats.snapshot().p50FirstLineMs)
    }

    @Test
    fun `repeated start does not reset the timing`() {
        // 缓冲 / 短暂暂停会重复发 PLAYING，不该把「首句延迟」重新计时。
        val stats = SessionStats()
        stats.onPlayStart(0)
        stats.onFirstLine(2_000)
        stats.onPlayStart(3_000)
        stats.onPlayStop(60_000)
        assertEquals(1, stats.snapshot().sessions)
        assertEquals(2_000L, stats.snapshot().p50FirstLineMs)
    }

    @Test
    fun `percentiles use the upper rank`() {
        val stats = SessionStats(capacity = 20)
        for (i in 1..10) {
            val base = i * 100_000L
            stats.onPlayStart(base)
            stats.onFirstLine(base + i * 1_000L)
            stats.onPlayStop(base + 60_000L)
        }
        val s = stats.snapshot()
        assertEquals(10, s.sessions)
        assertEquals(5_000L, s.p50FirstLineMs)
        assertEquals(9_000L, s.p90FirstLineMs)
    }

    @Test
    fun `snapshot is empty before any session`() {
        val s = SessionStats().snapshot()
        assertEquals(0, s.sessions)
        assertEquals(0f, s.hitRate, 0.001f)
        assertNull(s.p50FirstLineMs)
        assertNull(s.p90FirstLineMs)
    }

    @Test
    fun `capacity keeps only the most recent sessions`() {
        val stats = SessionStats(capacity = 3)
        for (i in 1..6) {
            val base = i * 100_000L
            stats.onPlayStart(base)
            stats.onPlayStop(base + 60_000L)
        }
        assertEquals(3, stats.snapshot().sessions)
    }

    @Test
    fun `clear drops samples and stops recording`() {
        val stats = SessionStats()
        stats.onPlayStart(0)
        stats.onFirstLine(1_000)
        stats.clear()
        assertEquals(0, stats.snapshot().sessions)
        // clear 之后旧会话不会再被收尾统计
        stats.onPlayStop(60_000)
        assertEquals(0, stats.snapshot().sessions)
    }
}
