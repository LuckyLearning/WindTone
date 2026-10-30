package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * 听歌日历（H7）聚合口径。
 *
 * 与 [ListeningReportTest] 同套路：纯 JVM、显式 Asia/Shanghai，不依赖机器时区。
 * 钉死：周一开头、邻月补齐格、本月合计不含邻月、色块峰值只看本月。
 */
class ListenCalendarTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()

    private fun bucket(date: LocalDate, hour: Int, ms: Long = 60_000L): ListenBucket =
        ListenBucket(at(date, hour) / HOUR_MS, ms)

    @Test
    fun october2026StartsOnThursdaySoThreeLeadingPads() {
        // 2026-10-01 是周四 → 周一开头时前面补 一/二/三 三格
        val cal = buildListenCalendarMonth(emptyList(), 2026, 10, zone)
        assertEquals(2026, cal.year)
        assertEquals(10, cal.month)
        assertEquals(0, cal.cells.size % 7)
        assertFalse(cal.cells[0].inMonth)
        assertFalse(cal.cells[1].inMonth)
        assertFalse(cal.cells[2].inMonth)
        assertTrue(cal.cells[3].inMonth)
        assertEquals(1, cal.cells[3].dayOfMonth)
        // 10 月 31 天 + 3 前导 = 34，再凑满周 → 35
        assertEquals(35, cal.cells.size)
        assertEquals(31, cal.cells.count { it.inMonth })
    }

    @Test
    fun inMonthTotalsIgnoreNeighborMonthBuckets() {
        // 9/30 与 11/1 的桶不应计入 10 月合计
        val buckets = listOf(
            bucket(LocalDate.of(2026, 9, 30), 20, 3_600_000L),
            bucket(LocalDate.of(2026, 10, 5), 9, 120_000L),
            bucket(LocalDate.of(2026, 10, 5), 10, 60_000L),
            bucket(LocalDate.of(2026, 11, 1), 8, 9_000_000L),
        )
        val cal = buildListenCalendarMonth(buckets, 2026, 10, zone)
        assertEquals(180_000L, cal.totalMs)
        assertEquals(1, cal.activeDays)
        assertEquals(180_000L, cal.maxDayMs)
        val day5 = cal.cells.first { it.inMonth && it.dayOfMonth == 5 }
        assertEquals(180_000L, day5.ms)
    }

    @Test
    fun emptyMonthHasZeroActiveAndZeroMax() {
        val cal = buildListenCalendarMonth(emptyList(), 2026, 2, zone)
        assertEquals(0L, cal.totalMs)
        assertEquals(0, cal.activeDays)
        assertEquals(0L, cal.maxDayMs)
        assertEquals(28, cal.cells.count { it.inMonth })
    }

    @Test
    fun formatHelpers() {
        assertEquals("2026年10月", formatYearMonth(2026, 10))
        val day = LocalDate.of(2026, 10, 15).atStartOfDay(zoneId).toInstant().toEpochMilli()
        // 2026-10-15 是周四
        assertEquals("10月15日 周四", formatCalendarDayTitle(day, zone))
    }
}
