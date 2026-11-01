package com.luckylearning.windtone.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 环形事件日志（v1.1.88）。
 *
 * 它只有三条必须守住的性质：**有上限**（不能吃内存）、**旧→新**（读起来像时间线）、
 * **清空彻底**（诊断包里不混进上一次的内容）。都是纯内存逻辑，直接测。
 */
class DiagLogTest {

    @Before
    fun setUp() {
        DiagLog.clear()
    }

    @Test
    fun `按写入顺序保留`() {
        DiagLog.log("FG", "a")
        DiagLog.log("FG", "b")
        val lines = DiagLog.snapshot()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("[FG] a"))
        assertTrue(lines[1].endsWith("[FG] b"))
    }

    @Test
    fun `超过上限时丢最旧的`() {
        repeat(320) { DiagLog.log("T", "n$it") }
        val lines = DiagLog.snapshot()
        assertEquals("上限 300 条", 300, lines.size)
        assertTrue("最旧的应被挤掉", lines.first().endsWith("[T] n20"))
        assertTrue(lines.last().endsWith("[T] n319"))
    }

    @Test
    fun `清空后为空`() {
        DiagLog.log("T", "x")
        DiagLog.clear()
        assertTrue(DiagLog.snapshot().isEmpty())
        assertEquals(0, DiagLog.size())
    }

    /**
     * A13：连续重复合并成一行带计数。
     *
     * 车机上「前台 → 同一包名」会连着报很多次（导航与桌面来回切时尤其密），
     * 每条都要格式化一次时间戳。合并后同一事件只占一行，信息量反而更清楚。
     */
    @Test
    fun `连续重复的事件合并为一行并标次数`() {
        DiagLog.log("FG", "前台 → com.byd.avc")
        DiagLog.log("FG", "前台 → com.byd.avc")
        DiagLog.log("FG", "前台 → com.byd.avc")

        val lines = DiagLog.snapshot()
        assertEquals("连续三条相同事件应只占一行", 1, lines.size)
        assertTrue("应标注出现次数，实际：${lines[0]}", lines[0].endsWith("[FG] 前台 → com.byd.avc ×3"))
    }

    /** 中间插了别的事件就必须断开：排障靠的是先后顺序，不能被合并抹掉 */
    @Test
    fun `插入其它事件后不再合并`() {
        DiagLog.log("FG", "a")
        DiagLog.log("OVERLAY", "b")
        DiagLog.log("FG", "a")

        val lines = DiagLog.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("[FG] a"))
        assertTrue(lines[1].endsWith("[OVERLAY] b"))
        assertTrue(lines[2].endsWith("[FG] a"))
    }

    /** 合并后的行沿用首次出现的时间戳，格式仍是 `HH:mm:ss.SSS [TAG] msg` */
    @Test
    fun `合并行保留时间戳前缀`() {
        DiagLog.log("T", "same")
        DiagLog.log("T", "same")

        val line = DiagLog.snapshot().single()
        assertTrue("应保留时间戳前缀，实际：$line", Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[T\\] same ×2$").matches(line))
    }

    /** 清空必须把合并状态一起重置，否则下一次写入会被误并进已清掉的上一行 */
    @Test
    fun `清空后合并状态重置`() {
        DiagLog.log("T", "x")
        DiagLog.clear()
        DiagLog.log("T", "x")

        val lines = DiagLog.snapshot()
        assertEquals(1, lines.size)
        assertTrue("清空后应重新计次，实际：${lines[0]}", lines[0].endsWith("[T] x"))
    }
}
