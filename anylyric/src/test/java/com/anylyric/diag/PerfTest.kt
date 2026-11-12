package com.anylyric.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工作量计数器。
 *
 * 它服务的是一条诊断结论：「CPU 高」到底是事件太多，还是某段代码太贵。所以只有两条
 * 硬要求：增量只统计「上次之后」的新工作；归零之后不能算出负数。
 */
class PerfTest {

    @Before
    fun setUp() = Perf.reset()

    @After
    fun tearDown() = Perf.reset()

    @Test
    fun deltaCountsOnlyNewWork() {
        Perf.onRefresh()
        Perf.onChain()
        val first = Perf.sinceLast()
        assertTrue(first.contains("+refresh=1"))
        assertTrue(first.contains("+chain=1"))

        Perf.onRefresh()
        val second = Perf.sinceLast()
        assertTrue(second.contains("+refresh=1"))
        assertTrue(second.contains("+chain=0"))
    }

    @Test
    fun cumulativeLineIsMonotonic() {
        repeat(3) { Perf.onSkip() }
        Perf.onTick()
        assertEquals("refresh=0 chain=0 skip=3 render=0 tick=1", Perf.oneLine())
    }

    @Test
    fun resetKeepsDeltaNonNegative() {
        repeat(5) { Perf.onRender() }
        assertTrue(Perf.sinceLast().contains("+render=5"))

        Perf.reset()
        val after = Perf.sinceLast()
        assertTrue(after.contains("+render=0"))
        assertTrue(after.contains("+refresh=0"))
    }
}
