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
}
