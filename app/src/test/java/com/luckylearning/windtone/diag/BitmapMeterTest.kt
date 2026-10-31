package com.luckylearning.windtone.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BitmapMeter] 的计数逻辑测试。
 *
 * `Bitmap` 在纯 JVM 单测里是 stub（`isReturnDefaultValues = true`），
 * `allocationByteCount` 恒为 0，弱引用回收也无法稳定触发。所以只测抽出来的
 * [BitmapMeter.ByteCounter]：存活 / 峰值 / 重复回收 / 重置，这几条是账本的正确性底线。
 */
class BitmapMeterTest {

    @Test
    fun `创建累加存活并刷新峰值`() {
        val c = BitmapMeter.ByteCounter()

        c.onCreate(100)
        assertEquals(1L, c.created)
        assertEquals(100L, c.createdBytes)
        assertEquals(100L, c.liveBytes)
        assertEquals(100L, c.peakLiveBytes)

        c.onCreate(50)
        assertEquals(2L, c.created)
        assertEquals(150L, c.createdBytes)
        assertEquals(150L, c.liveBytes)
        assertEquals(150L, c.peakLiveBytes)
    }

    @Test
    fun `回收只减存活不动峰值与累计`() {
        val c = BitmapMeter.ByteCounter()
        c.onCreate(100)
        c.onCreate(200)

        c.onRelease(100)

        assertEquals(200L, c.liveBytes)
        // 峰值是「曾经同时活过多少」，回落不该抹掉它 —— 泄漏判据就靠这一项
        assertEquals(300L, c.peakLiveBytes)
        assertEquals(2L, c.created)
        assertEquals(300L, c.createdBytes)
    }

    /** 重复回收（recycle + 弱引用入队各算一次）不能把存活做成负数 */
    @Test
    fun `重复回收把存活夹在零`() {
        val c = BitmapMeter.ByteCounter()
        c.onCreate(64)
        c.onRelease(64)
        c.onRelease(64)

        assertEquals(0L, c.liveBytes)
    }

    @Test
    fun `重置清空全部计数`() {
        val c = BitmapMeter.ByteCounter()
        c.onCreate(128)
        c.onRelease(64)

        c.reset()

        assertEquals(0L, c.created)
        assertEquals(0L, c.createdBytes)
        assertEquals(0L, c.liveBytes)
        assertEquals(0L, c.peakLiveBytes)
    }

    /** 全局统计即使没登记过任何位图也必须可用（不能抛、不能为负） */
    @Test
    fun `全局统计在没有任何位图时返回零`() {
        val s = BitmapMeter.stats()
        assertTrue(s.liveBytes >= 0L)
        assertTrue(s.peakLiveBytes >= s.liveBytes)
    }
}
