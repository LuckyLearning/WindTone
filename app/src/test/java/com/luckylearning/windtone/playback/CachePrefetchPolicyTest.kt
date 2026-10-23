package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台预取的安全边界（v1.1.46）。
 *
 * 预取的价值全在「不打扰当前这首」：候选选错 → 下了不听的歌；开始条件放宽 →
 * 抢带宽导致当前曲卡顿。两条都在这钉住。
 */
class CachePrefetchPolicyTest {

    @Test
    fun `只挑在线歌且最多两首`() {
        val schemes = listOf("content", "https", "content", "http", "https", "https")
        // 本地曲目不占名额：第 2、4、5 个才是远程，取前两个 → 下标 1、3
        assertEquals(listOf(1, 3), CachePrefetchPolicy.candidates(schemes))
        assertEquals(listOf(1), CachePrefetchPolicy.candidates(schemes, max = 1))
    }

    @Test
    fun `后面没有在线歌时什么都不预取`() {
        assertTrue(CachePrefetchPolicy.candidates(listOf("content", "file")).isEmpty())
        assertTrue(CachePrefetchPolicy.candidates(emptyList()).isEmpty())
        // scheme 大小写不该影响判断（M3U 里常见大写 HTTP）
        assertEquals(listOf(0), CachePrefetchPolicy.candidates(listOf("HTTP")))
    }

    @Test
    fun `缓冲不够时不预取`() {
        // 正在播、只缓冲了 5 秒：先把管道留给这首
        assertFalse(CachePrefetchPolicy.shouldStart(playing = true, bufferAheadMs = 5_000))
        assertFalse(CachePrefetchPolicy.shouldStart(playing = true, bufferAheadMs = 19_999))
        assertTrue(CachePrefetchPolicy.shouldStart(playing = true, bufferAheadMs = 20_000))
    }

    @Test
    fun `暂停时可以预取`() {
        // 暂停时网络空闲，正是补缓存的时候（哪怕只缓冲了 1 秒）
        assertTrue(CachePrefetchPolicy.shouldStart(playing = false, bufferAheadMs = 1_000))
        assertTrue(CachePrefetchPolicy.shouldStart(playing = false, bufferAheadMs = 0))
    }

    @Test
    fun `当前曲缺口还没补完时不预取`() {
        assertFalse(CachePrefetchPolicy.shouldStart(playing = false, bufferAheadMs = 30_000, currentTailOpen = true))
        assertFalse(CachePrefetchPolicy.shouldStart(playing = true, bufferAheadMs = 30_000, currentTailOpen = true))
    }
}
