package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BootRetryPolicy] 单测：开机后台起播的重试时机与失败兜底判定。
 *
 * 这组策略原本内联在 PlaybackEngine.startPlaybackInBackground 里，连点 / 车机音频链路起振慢
 * 等边界很难覆盖；抽成纯函数后可直接钉死「0 / 4s / 10s 三次、总长 < 20s、用尽后兜底」的契约。
 */
class BootRetryPolicyTest {

    @Test
    fun `总尝试次数等于重试时机数组长度`() {
        assertEquals(3, BootRetryPolicy.attempts())
    }

    @Test
    fun `重试时机按 0s 4s 10s 排列`() {
        assertEquals(0L, BootRetryPolicy.waitMs(0))
        assertEquals(4_000L, BootRetryPolicy.waitMs(1))
        assertEquals(10_000L, BootRetryPolicy.waitMs(2))
    }

    @Test
    fun `三次总等待时长仍在 20 秒内`() {
        val total = (0 until BootRetryPolicy.attempts()).sumOf { BootRetryPolicy.waitMs(it) }
        assertTrue("三次总等待 $total 应 < 20000", total < 20_000L)
    }

    @Test
    fun `越界索引取最后一次时机，不抛异常`() {
        assertEquals(10_000L, BootRetryPolicy.waitMs(99))
    }

    @Test
    fun `hasMore 在第 0、1 次为 true，第 2 次为 false`() {
        assertTrue(BootRetryPolicy.hasMore(0))
        assertTrue(BootRetryPolicy.hasMore(1))
        assertFalse(BootRetryPolicy.hasMore(2))
    }

    @Test
    fun `isExhausted 在第 2 次（最后）为 true，之前为 false`() {
        assertFalse(BootRetryPolicy.isExhausted(0))
        assertFalse(BootRetryPolicy.isExhausted(1))
        assertTrue(BootRetryPolicy.isExhausted(2))
    }

    @Test
    fun `失败兜底：每次都失败则循环恰好 attempts 次后才走兜底`() {
        // 复刻 startPlaybackInBackground 的循环：每次 attemptBootPlayback 都返回失败，
        // 断言循环只跑 attempts() 次、且最后一次 isExhausted=true（即触发 BootStatus.markResult(false)）。
        var runs = 0
        var fallbackTriggered = false
        for (attempt in 0 until Int.MAX_VALUE) {
            runs++
            val ok = false // 模拟 attemptBootPlayback 始终失败
            if (ok) break
            if (BootRetryPolicy.isExhausted(attempt)) {
                fallbackTriggered = true
                break
            }
        }
        assertEquals(BootRetryPolicy.attempts(), runs)
        assertTrue("用尽重试后必须走失败兜底", fallbackTriggered)
    }
}
