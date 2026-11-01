package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.lyrics.OverlayContentResolver
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LyricOverlayBusSkipTest {

    @Before
    fun setUp() {
        LyricOverlayBus.clear()
        OverlayMetrics.reset()
    }

    @After
    fun tearDown() {
        LyricOverlayBus.clear()
        OverlayMetrics.reset()
    }

    @Test
    fun `same content republish counts skip`() {
        val resolved = OverlayContentResolver.Resolved(current = "hello", idle = false)
        LyricOverlayBus.publish(resolved, playing = true, title = "T", artist = "A")
        OverlayMetrics.reset()
        repeat(5) {
            LyricOverlayBus.publish(resolved, playing = true, title = "T", artist = "A")
        }
        val text = OverlayMetrics.summary()
        assertTrue(text, text.contains("跳过 5 次"))
    }

    @Test
    fun `line change does not count skip at bus`() {
        LyricOverlayBus.publish(
            OverlayContentResolver.Resolved(current = "one"),
            playing = true, title = "T", artist = "A",
        )
        OverlayMetrics.reset()
        LyricOverlayBus.publish(
            OverlayContentResolver.Resolved(current = "two"),
            playing = true, title = "T", artist = "A",
        )
        val text = OverlayMetrics.summary()
        assertTrue("line change must not bus-skip", text == "" || !text.contains("跳过"))
    }
}
