package com.luckylearning.windtone.playback.parallel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段规划（v1.1.47）。
 *
 * 这里钉的三件事，任何一条错都会毁掉播放：段重叠会写坏同一段字节、段之间有缝会让
 * 播放卡在空洞上、总和不对会让最后一段读不到结尾。
 */
class SegmentPlannerTest {

    private val mb = 1024L * 1024

    @Test
    fun `大文件切三段且首尾相接`() {
        val total = 28 * mb
        val segments = SegmentPlanner.plan(total)
        assertEquals(3, segments.size)
        assertEquals(0L, segments.first().start)
        assertEquals(total - 1, segments.last().endInclusive)
        segments.zipWithNext().forEach { (a, b) ->
            // 不重叠、不留缝
            assertEquals(a.endInclusive + 1, b.start)
        }
        // 总和必须精确等于文件长度，误差 1 字节都会让最后一段越界或读不全
        assertEquals(total, segments.sumOf { it.length })
    }

    @Test
    fun `余数摊到前几段`() {
        val total = 5 * mb + 3
        val segments = SegmentPlanner.plan(total)
        assertEquals(3, segments.size)
        assertEquals(total, segments.sumOf { it.length })
        val lengths = segments.map { it.length }.distinct()
        assertTrue("段长只应差 1 字节：$lengths", lengths.max() - lengths.min() <= 1)
    }

    @Test
    fun `小文件不切分`() {
        // 300KB 的 mp3 用多条连接，握手比下载还贵
        assertTrue(SegmentPlanner.plan(300 * 1024).isEmpty())
        // 1.5MB 只够一段 -> 等于不并行
        assertTrue(SegmentPlanner.plan(mb + mb / 2).isEmpty())
        // 长度未知
        assertTrue(SegmentPlanner.plan(0).isEmpty())
        assertTrue(SegmentPlanner.plan(-1).isEmpty())
    }

    @Test
    fun `中等文件段数按 1MB 下限收缩`() {
        // 2.5MB -> 每段至少 1MB，因此只有 2 段
        val segments = SegmentPlanner.plan(2 * mb + mb / 2)
        assertEquals(2, segments.size)
        assertEquals(2 * mb + mb / 2, segments.sumOf { it.length })
    }

    @Test
    fun `定位 position 所属分段`() {
        val total = 10 * mb
        val segments = SegmentPlanner.plan(total)
        assertEquals(0, SegmentPlanner.indexOf(segments, 0))
        assertEquals(0, SegmentPlanner.indexOf(segments, segments[0].endInclusive))
        assertEquals(1, SegmentPlanner.indexOf(segments, segments[1].start))
        assertEquals(2, SegmentPlanner.indexOf(segments, total - 1))
        assertEquals(-1, SegmentPlanner.indexOf(segments, total))
        assertEquals(-1, SegmentPlanner.indexOf(segments, -1))
    }
}
