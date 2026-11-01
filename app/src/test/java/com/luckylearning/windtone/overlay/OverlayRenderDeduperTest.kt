package com.luckylearning.windtone.overlay

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OverlayRenderDeduper]: same render key must skip; key change applies.
 */
class OverlayRenderDeduperTest {

    private val deduper = OverlayRenderDeduper()

    @Before
    fun setUp() {
        OverlayMetrics.reset()
        deduper.clear()
    }

    @After
    fun tearDown() = OverlayMetrics.reset()

    @Test
    fun `same key second begin returns false and counts skip`() {
        val key = OverlayRenderKey.build(
            listOf(OverlayTextComposer.StyledLine("same", OverlayTextComposer.Role.CURRENT)),
            fullHeight = false,
            opaque = true,
            baseColor = 0xFFFFFFFF.toInt(),
            noBackdrop = false,
            currentColor = 0,
            dimPercent = 42,
        )
        assertTrue(deduper.begin(key))
        assertFalse("same key must skip", deduper.begin(key))
        assertFalse(deduper.begin(key))
        val text = OverlayMetrics.summary()
        assertTrue(text, text.contains("刷字 1 次（跳过 2 次）"))
    }

    @Test
    fun `text change reapplies then skips`() {
        val a = OverlayRenderKey.build(
            listOf(OverlayTextComposer.StyledLine("A", OverlayTextComposer.Role.CURRENT)),
            false, true, 0xFFFFFFFF.toInt(), false, 0, 42,
        )
        val b = OverlayRenderKey.build(
            listOf(OverlayTextComposer.StyledLine("B", OverlayTextComposer.Role.CURRENT)),
            false, true, 0xFFFFFFFF.toInt(), false, 0, 42,
        )
        assertTrue(deduper.begin(a))
        assertTrue(deduper.begin(b))
        assertFalse(deduper.begin(b))
        val text = OverlayMetrics.summary()
        assertTrue(text, text.contains("刷字 2 次（跳过 1 次）"))
    }

    @Test
    fun `clear allows same key to apply again`() {
        val key = "F|1|1|B|0|42|CURRENT:x\n"
        assertTrue(deduper.begin(key))
        deduper.clear()
        assertTrue(deduper.begin(key))
        val text = OverlayMetrics.summary()
        assertTrue(text, text.contains("刷字 2 次（跳过 0 次）"))
    }
}
