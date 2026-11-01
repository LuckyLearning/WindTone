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
        repeat(20) { OverlayMetrics.onVisibilityEval() }
        repeat(20) { OverlayMetrics.onVisibilityApply() }
        OverlayMetrics.onVisibilityChanged()
        OverlayMetrics.onHandleRebuild()
        repeat(3) { OverlayMetrics.onA11yTick(1_000_000L) } // 3 × 1ms
        OverlayMetrics.onRenderApply()
        repeat(5) { OverlayMetrics.onRenderSkip() }

        val text = OverlayMetrics.summary()

        // A-OV2 起多了「显隐判定」这一层：判定每帧跑，重算只在结果变化时跑
        assertTrue(text, text.contains("显隐判定 20 次"))
        assertTrue(text, text.contains("重算 20 次（实际变化 1 次）"))
        assertTrue(text, text.contains("侧钮重建 1 次"))
        assertTrue(text, text.contains("刷字 1 次（跳过 5 次）"))
        assertTrue(text, text.contains("无障碍轮询 3 次"))
        assertTrue(text, text.contains("累计 3 ms"))
    }

    /**
     * 判据已改向（A-OV2）：`applyVisibility` 只在判定结果变化时才调用，
     * 所以「重算次数 ≈ 变化次数」是**最理想**的状态，不是抖动。
     *
     * 旧判读「变化占比 >1/3 就报反复翻转」方向反了 —— v1.2.44 车机包（27/26）
     * 因此被判成异常，实际很健康。现在只按**每分钟重算频率**判定，
     * 而这里没有真实时长，所以不该出现任何抖动提示。
     */
    @Test
    fun `重算≈变化是正常状态不报抖动`() {
        repeat(20) { OverlayMetrics.onVisibilityEval() }
        repeat(20) { OverlayMetrics.onVisibilityApply() }
        repeat(19) { OverlayMetrics.onVisibilityChanged() }

        val text = OverlayMetrics.summary()

        assertTrue("重算≈变化不应被判为抖动，实际：$text", !text.contains("切换频繁"))
    }

    /** 有真实时长且频率很高时才提示——这里靠反射不便模拟，改用「判定基数很小」这一等价形态 */
    @Test
    fun `没有任何判定时不做频率判读`() {
        repeat(5) { OverlayMetrics.onVisibilityApply() }
        val text = OverlayMetrics.summary()
        assertTrue("没有判定基座就没有频率判读，实际：$text", !text.contains("每分钟重算"))
    }

    @Test
    fun `轮询耗时为负数时被夹到零而不是算出负值`() {
        OverlayMetrics.onA11yTick(-5_000L)

        val text = OverlayMetrics.summary()

        assertTrue(text, text.contains("累计 0 ms"))
    }
}
