package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败跳曲策略（v1.1.45）。
 *
 * 真机反馈「完全播放不了了，一直切换」就是这里没兜住：所有远程文件都 403 时，
 * 旧逻辑会把整张队列挨个试一遍再从头来，用户看到的只有无限切歌。
 */
class ErrorSkipPolicyTest {

    @Test
    fun `单曲失败不该继续跳`() {
        assertFalse(ErrorSkipPolicy.shouldSkip(queueSize = 1, consecutiveErrors = 1))
        assertFalse(ErrorSkipPolicy.shouldSkip(queueSize = 0, consecutiveErrors = 1))
    }

    @Test
    fun `偶发单曲坏文件跳到下一首`() {
        assertTrue(ErrorSkipPolicy.shouldSkip(queueSize = 20, consecutiveErrors = 1))
        assertTrue(ErrorSkipPolicy.shouldSkip(queueSize = 20, consecutiveErrors = 2))
    }

    @Test
    fun `连续失败到上限必须停下而不是绕着队列转`() {
        // 队列再长也不能继续跳 —— 旧实现用「跳过数 < 队列长度」，20 首队列就能循环很久
        assertFalse(ErrorSkipPolicy.shouldSkip(queueSize = 20, consecutiveErrors = 3))
        assertFalse(ErrorSkipPolicy.shouldSkip(queueSize = 500, consecutiveErrors = 9))
    }

    @Test
    fun `停止时的话术带上原因与次数`() {
        val msg = ErrorSkipPolicy.stopMessage(3, "HTTP 403，直链没有权限或已过期")
        assertTrue(msg.contains("连续 3 首"))
        assertTrue(msg.contains("403"))
    }
}
