package com.anylyric.session

import com.anylyric.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionClockTest {

    private var now = 0L
    private fun clock() = SessionClock { now }

    @Test
    fun extrapolatesWhilePlaying() {
        val c = clock()
        now = 10_000
        c.update(1_000, 1f, PlayState.PLAYING)
        now = 10_500
        assertEquals(1_500L, c.position())
    }

    @Test
    fun freezesWhenPaused() {
        val c = clock()
        now = 0
        c.update(1_000, 1f, PlayState.PAUSED)
        now = 9_000
        assertEquals(1_000L, c.position())
    }

    @Test
    fun honoursPlaybackSpeed() {
        val c = clock()
        now = 0
        c.update(1_000, 2f, PlayState.PLAYING)
        now = 500
        assertEquals(2_000L, c.position())
    }

    @Test
    fun returnsUnknownWhenStopped() {
        val c = clock()
        c.update(1_000, 1f, PlayState.STOPPED)
        assertEquals(-1L, c.position())
    }

    @Test
    fun locatesCurrentLineByBinarySearch() {
        val c = clock()
        val lines = listOf(
            LyricLine(1_000L, "a"),
            LyricLine(3_000L, "b"),
            LyricLine(5_000L, "c")
        )
        assertEquals(1, c.locate(lines, 4_000L))
        assertEquals(2, c.locate(lines, 9_000L))
        assertEquals(-1, c.locate(lines, 500L))
    }

    @Test
    fun cannotLocateUntimedLines() {
        val c = clock()
        val lines = listOf(LyricLine(null, "a"), LyricLine(null, "b"))
        assertEquals(-1, c.locate(lines, 4_000L))
    }
}
