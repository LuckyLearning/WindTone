package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时歌词的推进节奏。
 *
 * 这一层的 bug 表现很隐蔽：定时器早到几毫秒会「该换没换」，间隔太短会退化成忙等，
 * 太长则歌词卡在上一句。所以边界值全部钉在单测里。
 */
class LyricTickTest {

    private fun timed(vararg at: Long): List<LyricLine> =
        at.map { LyricLine(it, "第 ${it}ms 句") }

    @Test
    fun emptyLinesNeedNoTick() {
        assertEquals(-1L, LyricTick.nextDelayMs(emptyList(), 0L))
    }

    /** 逐行流（无时间轴）由元数据事件驱动，定时器只会白转。 */
    @Test
    fun untimedLinesNeedNoTick() {
        val lines = listOf(LyricLine(null, "A"), LyricLine(null, "B"))
        assertEquals(-1L, LyricTick.nextDelayMs(lines, 0L))
    }

    /** 发布端给了 PLAYBACK_POSITION_UNKNOWN（-1）时不能排定时器。 */
    @Test
    fun unknownPositionNeedsNoTick() {
        assertEquals(-1L, LyricTick.nextDelayMs(timed(0, 1_000), -1L))
    }

    /** 已经到最后一行：再来也不会有变化。 */
    @Test
    fun lastLineNeedsNoTick() {
        assertEquals(-1L, LyricTick.nextDelayMs(timed(0, 1_000), 5_000L))
    }

    @Test
    fun waitsForNextLineBoundary() {
        // 下一行在 4_000，当前 3_600 → 等 400ms（+余量）
        val delay = LyricTick.nextDelayMs(timed(0, 4_000), 3_600L)
        assertEquals(4_000L - 3_600L + LyricTick.MARGIN_MS, delay)
    }

    /** 贴着行边界（还差 10ms）：按最短间隔唤醒，不空转也不早到。 */
    @Test
    fun boundaryWakesUpWithMinimumGap() {
        val delay = LyricTick.nextDelayMs(timed(0, 2_000), 1_990L)
        assertEquals(LyricTick.MIN_DELAY_MS, delay)
    }

    /** 大段间奏：封顶一秒复核一次，而不是精确等十几秒（防止进度被拖动后算错）。 */
    @Test
    fun longInterludeIsCapped() {
        val delay = LyricTick.nextDelayMs(timed(0, 60_000), 1_000L)
        assertEquals(LyricTick.MAX_DELAY_MS, delay)
    }

    /** 开头之前（进度还没到第一句）也要能正常排上。 */
    @Test
    fun beforeFirstLineStillSchedules() {
        assertTrue(LyricTick.nextDelayMs(timed(1_500, 4_000), 0L) > 0L)
    }

    /** 同一时刻堆了几行（合唱）：定位会落在最后一行，定时器要跳到下一个不同时刻。 */
    @Test
    fun duplicatedTimestampsSkipToNextBoundary() {
        val lines = listOf(
            LyricLine(0L, "A"),
            LyricLine(1_000L, "B"),
            LyricLine(1_000L, "C"),
            LyricLine(3_000L, "D")
        )
        assertEquals(3_000L - 2_500L + LyricTick.MARGIN_MS, LyricTick.nextDelayMs(lines, 2_500L))
    }
}
