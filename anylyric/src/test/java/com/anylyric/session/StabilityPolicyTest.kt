package com.anylyric.session

import com.anylyric.session.StabilityPolicy.QUIET_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「静默补查」与「同曲空窗保持」这两条策略的边界。
 *
 * 它们直接决定「多久读一次播放器」与「什么时候清屏」，写错的代价分别是耗电和闪屏，
 * 都属于真机上很难回看的东西 —— 所以判定逻辑必须能被单测钉住。
 */
class StabilityPolicyTest {

    @Test
    fun `播放中每秒看一次`() {
        assertEquals(1_000L, StabilityPolicy.pollDelayMs(playing = true))
    }

    @Test
    fun `非播放态放宽到五秒`() {
        assertEquals(5_000L, StabilityPolicy.pollDelayMs(playing = false))
    }

    @Test
    fun `从未收到回调时要补读`() {
        // 刚绑上会话、播放器一个回调都没发：这正是补查存在的理由
        assertTrue(StabilityPolicy.shouldRequery(nowMs = 10_000L, lastCallbackAtMs = 0L, playing = true))
    }

    @Test
    fun `静默刚好到阈值的边界算静默`() {
        val now = 100_000L
        assertFalse(
            StabilityPolicy.shouldRequery(now, now - QUIET_MS + 1, playing = true)
        )
        assertTrue(
            StabilityPolicy.shouldRequery(now, now - QUIET_MS, playing = true)
        )
    }

    @Test
    fun `刚收到回调就不补读`() {
        // 正常播放器每秒都有 state 回调，这条路径等于空转一次比较
        assertFalse(StabilityPolicy.shouldRequery(nowMs = 5_000L, lastCallbackAtMs = 4_900L, playing = true))
    }

    @Test
    fun `暂停时门槛放宽一倍`() {
        val now = 100_000L
        // 播放中 1.5s 就算静默；暂停时同样的静默还不值得读一次（多等一个周期）
        assertFalse(StabilityPolicy.shouldRequery(now, now - QUIET_MS, playing = false))
        assertTrue(StabilityPolicy.shouldRequery(now, now - QUIET_MS * 2, playing = false))
    }

    @Test
    fun `时间戳来自别的基准时按静默处理`() {
        // lastCallback 比 now 还大只可能是时钟基准不一致：宁可多读一次，也不要永远不读
        assertTrue(StabilityPolicy.shouldRequery(nowMs = 1_000L, lastCallbackAtMs = 9_000L, playing = true))
    }

    @Test
    fun `同曲解析为空时保留上一轮的行`() {
        assertTrue(StabilityPolicy.keepLines(trackChanged = false, hadLines = true))
    }

    @Test
    fun `换歌时绝不保留上一首的行`() {
        assertFalse(StabilityPolicy.keepLines(trackChanged = true, hadLines = true))
    }

    @Test
    fun `屏上本来就没行时无所谓保留`() {
        assertFalse(StabilityPolicy.keepLines(trackChanged = false, hadLines = false))
    }
}
