package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机版尺码表（v1.1.98）。
 *
 * 背景：这台 DiLink 是 **1280×656dp**，此前用的是按 1707dp 大屏调的一套固定尺码，
 * 播放条 112dp 就占掉 17% 的纵向空间，整屏又挤又"按钮特别大"。
 *
 * 这里锁三件事：档位判定、紧凑档全面更小、以及"紧凑不等于缩到不可点"。
 * E2 补：760dp / 1400dp 阈值本身。
 */
class CarMetricsTest {

    @Test
    fun `1280x656 的车机走紧凑档`() {
        val m = carMetricsFor(1280f, 656f)
        assertTrue("这台车机必须落进紧凑档", m.compact)
    }

    @Test
    fun `大屏车机保持常规档`() {
        val m = carMetricsFor(1920f, 900f)
        assertFalse(m.compact)
    }

    @Test
    fun `宽度够但高度不足也走紧凑档`() {
        // 高度是真正的约束：1707dp 宽、只有 700dp 高时同样会挤
        assertTrue(carMetricsFor(1707f, 700f).compact)
    }

    @Test
    fun `紧凑档每一项都不大于常规档`() {
        val c = carMetricsFor(1280f, 656f)
        val r = carMetricsFor(1920f, 900f)
        assertTrue(c.navWidth < r.navWidth)
        assertTrue(c.navIcon < r.navIcon)
        assertTrue(c.navTextSp.value < r.navTextSp.value)
        assertTrue(c.barHeight < r.barHeight)
        assertTrue(c.sideTile < r.sideTile)
        assertTrue(c.mainButton < r.mainButton)
        assertTrue(c.rowMinHeight < r.rowMinHeight)
        assertTrue(c.titleSp.value < r.titleSp.value)
    }

    @Test
    fun `紧凑档仍高于最小触控目标`() {
        val c = carMetricsFor(1280f, 656f)
        assertEquals("侧键不小于 48dp", true, c.sideTile.value >= 48f)
        assertEquals("主键不小于 60dp", true, c.mainButton.value >= 60f)
        assertEquals("设置行不小于 60dp", true, c.rowMinHeight.value >= 60f)
    }

    /** E2：阈值本身——760dp 高 / 1400dp 宽是「刚好不进紧凑」的分界。 */
    @Test
    fun `760dp 边界与 1400dp 宽度分界`() {
        // 高恰好 760 且够宽 → 常规档（条件是 height < 760）
        assertFalse(carMetricsFor(1920f, 760f).compact)
        // 高 759 → 紧凑
        assertTrue(carMetricsFor(1920f, 759f).compact)
        // 宽恰好 1400 且够高 → 常规（条件是 width < 1400）
        assertFalse(carMetricsFor(1400f, 900f).compact)
        // 宽 1399 → 紧凑
        assertTrue(carMetricsFor(1399f, 900f).compact)
    }
}
