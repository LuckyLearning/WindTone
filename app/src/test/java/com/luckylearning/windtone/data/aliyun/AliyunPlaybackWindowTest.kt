package com.luckylearning.windtone.data.aliyun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliyunPlaybackWindowTest {

    private val pending = AliyunPlaybackWindow.PENDING_SCHEME
    private val ready = "https"

    @Test
    fun `开头只取当前和后面两首`() {
        assertEquals(0..2, AliyunPlaybackWindow.range(0, 20))
        assertEquals(listOf(0, 1, 2), AliyunPlaybackWindow.pendingIndices(List(20) { pending }, 0))
    }

    @Test
    fun `播到中间时窗口含前一首和后两首`() {
        assertEquals(4..7, AliyunPlaybackWindow.range(5, 20))
        val schemes = List(20) { index -> if (index <= 5) ready else pending }
        assertEquals(listOf(6, 7), AliyunPlaybackWindow.pendingIndices(schemes, 5))
    }

    @Test
    fun `末尾不再向后要地址`() {
        assertEquals(8..9, AliyunPlaybackWindow.range(9, 10))
        assertTrue(AliyunPlaybackWindow.pendingIndices(emptyList(), 0).isEmpty())
        assertTrue(AliyunPlaybackWindow.range(-1, 10).isEmpty())
    }

    @Test
    fun `当前这首没有地址时从窗口里后面那首起播`() {
        val flags = listOf(false, true, false, true)
        assertEquals(2, AliyunPlaybackWindow.firstReady(flags, 1))
        assertEquals(0, AliyunPlaybackWindow.firstReady(flags, 0))
        assertNull(AliyunPlaybackWindow.firstReady(listOf(true, true, true), 0))
        assertNull(AliyunPlaybackWindow.firstReady(emptyList(), 0))
    }
}
