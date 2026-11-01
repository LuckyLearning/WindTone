package com.luckylearning.windtone.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OverlayMetrics] 的摘要文本。
 *
 * 它进的是诊断包正文，格式错了就等于「下次拿到数据也读不懂」，所以按字段断言。
 */
class OverlayMetricsTest {

    @Before
    fun setUp() = OverlayMetrics.reset()

    @After
    fun tearDown() = OverlayMetrics.reset()

    @Test
    fun `没有任何计数时返回空串`() {
        assertEquals("", OverlayMetrics.summary())
    }

    @Test
    fun `摘要包含四组计数`() {
        repeat(20) { OverlayMetrics.onVisibilityApply() }
        OverlayMetrics.onVisibilityChanged()
        OverlayMetrics.onHandleRebuild()
        repeat(3) { OverlayMetrics.onA11yTick(1_000_000L) } // 3 × 1ms
        OverlayMetrics.onRenderApply()
        repeat(5) { OverlayMetrics.onRenderSkip() }

        val text = OverlayMetrics.summary()

        assertTrue(text, text.contains("可见性重算 20 次（实际变化 1 次）"))
        assertTrue(text, text.contains("侧钮重建 1 次"))
        assertTrue(text, text.contains("刷字 1 次（跳过 5 次）"))
        assertTrue(text, text.contains("无障碍轮询 3 次"))
        assertTrue(text, text.contains("累计 3 ms"))
    }

    @Test
    fun `可见性反复翻转时给出判读`() {
        repeat(3) { OverlayMetrics.onVisibilityApply() }
        repeat(2) { OverlayMetrics.onVisibilityChanged() }

        val text = OverlayMetrics.summary()

        assertTrue("变化占比 2/3 应命中判读", text.contains("反复翻转"))
    }

    @Test
    fun `重算幂等时不出现判读`() {
        repeat(100) { OverlayMetrics.onVisibilityApply() }
        OverlayMetrics.onVisibilityChanged()

        val text = OverlayMetrics.summary()

        assertTrue("变化占比 1% 不该报抖", !text.contains("反复翻转"))
    }

    @Test
    fun `轮询耗时为负数时被夹到零而不是算出负值`() {
        OverlayMetrics.onA11yTick(-5_000L)

        val text = OverlayMetrics.summary()

        assertTrue(text, text.contains("累计 0 ms"))
    }
}
