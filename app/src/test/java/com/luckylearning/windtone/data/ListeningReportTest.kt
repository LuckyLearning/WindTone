package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 收听报告的聚合口径回归。
 *
 * 纯逻辑测试，**不需要 Robolectric**——这一层的全部风险都在日历换算上：
 * 「日边界到底按不按本地午夜」「跨零点的桶算哪一天」「没听的那天是 0 还是缺项」。
 * 这几条一旦错了，用户看到的是"早八点就变成新的一天""昨天的时长跑到今天"，
 * 而且不会有任何崩溃或日志，只能靠单测钉。
 *
 * 时区一律显式传 `Asia/Shanghai`（+08:00 且无夏令时），不依赖跑测试那台机器的时区，
 * 否则在 UTC 的 CI 上通过、在 +08 的开发机上失败（或反过来）。
 *
 * 两个时区变量是有意分开的：`zone` 是**被测代码**用的 `java.util.TimeZone`
 * （受测层不能用 `java.time`，minSdk 24 见 `ListeningReport.kt` 文件头），
 * `zoneId` 只是**测试自己**造时间戳用的 `java.time`（测试跑在 JDK 上，随便用）。
 */
class ListeningReportTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()

    /** 本地某时刻所属的「小时桶」编号（与写入侧 `System.currentTimeMillis() / HOUR_MS` 同口径） */
    private fun bucket(date: LocalDate, hour: Int, minute: Int = 0): ListenBucket =
        ListenBucket(at(date, hour, minute) / HOUR_MS, 60_000L)

    private val today = LocalDate.of(2026, 10, 15)

    // ---------------------------------------------------------------- 窗口与日边界

    @Test
    fun windowEndsAtLocalMidnightNotUtcMidnight() {
        // 本地 07:00 时，UTC 还是前一天的 23:00。
        // 若按 UTC 划天，窗口最后一天会是「本地当天 08:00 起算」——差 8 小时。
        val now = at(today, 7)
        val report = buildListeningReport(emptyList(), now, 7, zone)
        assertEquals(
            "窗口最后一天必须是**本地午夜**",
            today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            report.days.last().dayStartMs,
        )
        assertNotEquals(
            "不能是 UTC 零点（= 本地当天 08:00）",
            at(today, 8),
            report.days.last().dayStartMs,
        )
    }

    @Test
    fun windowHasExactlyRequestedNumberOfDaysInAscendingOrder() {
        val report = buildListeningReport(emptyList(), at(today, 12), 30, zone)
        assertEquals(30, report.days.size)
        assertEquals(today.minusDays(29).atStartOfDay(zoneId).toInstant().toEpochMilli(), report.days.first().dayStartMs)
        assertEquals(today.atStartOfDay(zoneId).toInstant().toEpochMilli(), report.days.last().dayStartMs)
        // 升序且每天恰好相差一天
        report.days.zipWithNext { a, b ->
            assertEquals(86_400_000L, b.dayStartMs - a.dayStartMs)
        }
    }

    /**
     * 跨零点的桶必须落在**它所属的本地日**。
     *
     * 本地 00:00 在 UTC 上是前一天 16:00——按 UTC 取日期就会整整错一天，
     * 这是这套聚合最容易踩、也最难被发现的一处。
     */
    @Test
    fun bucketJustAfterLocalMidnightCountsAsThatLocalDay() {
        val zero = bucket(today, 0, 0)   // 本地 10/15 00:00 == UTC 10/14 16:00
        val report = buildListeningReport(listOf(zero), at(today, 7), 2, zone)
        assertEquals("10/15 00:00 必须算在 10/15，而不是 UTC 的 10/14", 60_000L, report.days.last().ms)
        assertEquals("10/14 不能有记录", 0L, report.days.first().ms)
        assertEquals(1, report.activeDays)
    }

    @Test
    fun bucketJustBeforeLocalMidnightCountsAsPreviousDay() {
        val before = bucket(today.minusDays(1), 23, 59)
        val report = buildListeningReport(listOf(before), at(today, 7), 2, zone)
        assertEquals(60_000L, report.days.first().ms)
        assertEquals(0L, report.days.last().ms)
    }

    @Test
    fun daysWithoutListeningAreZeroNotMissing() {
        // 曲线要连续：没有收听的那天必须是 0 而不是被跳过，否则柱子数少于天数，
        // 「哪天没听」这件事在图上就看不出来了。
        val only = bucket(today.minusDays(3), 9)
        val report = buildListeningReport(listOf(only), at(today, 20), 5, zone)
        assertEquals(5, report.days.size)
        assertEquals(1, report.days.count { it.ms > 0L })
        assertEquals(60_000L, report.days[1].ms)
        assertEquals(0L, report.days[0].ms)
        assertEquals(0L, report.days[4].ms)
    }

    @Test
    fun bucketsOutsideTheWindowAreDropped() {
        val out = bucket(today.minusDays(10), 9)
        val inside = bucket(today, 9)
        val report = buildListeningReport(listOf(out, inside), at(today, 18), 7, zone)
        assertEquals("窗口外的桶不能计入总时长", 60_000L, report.totalMs)
        assertEquals(1, report.activeDays)
    }

    @Test
    fun zeroAndNegativeBucketsAreIgnoredEntirely() {
        val zero = ListenBucket(bucket(today, 9).hourEpoch, 0L)
        val negative = ListenBucket(bucket(today, 10).hourEpoch, -5_000L)
        val report = buildListeningReport(listOf(zero, negative), at(today, 18), 7, zone)
        assertEquals(0L, report.totalMs)
        assertEquals(
            "写了个 0 的桶不该被当成「这天听过」",
            0,
            report.activeDays,
        )
        assertNull(report.peakHour)
    }

    // ---------------------------------------------------------------- 时段分布

    @Test
    fun hourDistributionUsesBucketStartHour() {
        val report = buildListeningReport(
            listOf(bucket(today, 9), bucket(today, 9), bucket(today, 21)),
            at(today, 23),
            1,
            zone,
        )
        assertEquals(24, report.hours.size)
        assertEquals(120_000L, report.hours[9].ms)
        assertEquals(60_000L, report.hours[21].ms)
        assertEquals(0L, report.hours[0].ms)
        assertEquals(9, report.peakHour?.hour)
        assertEquals(120_000L, report.peakHour?.ms)
    }

    @Test
    fun peakHourIsNullWhenNothingWasPlayed() {
        assertNull(buildListeningReport(emptyList(), at(today, 12), 7, zone).peakHour)
    }

    // ---------------------------------------------------------------- 汇总

    @Test
    fun averageDividesByRequestedSpanNotByActiveDays() {
        // 日均的语义是「这段时间里平均每天听多久」，分母是**范围天数**。
        // 用活跃天数当分母会把「只听了 1 天」算成"日均 3 小时"，读数完全失真。
        val report = buildListeningReport(
            listOf(ListenBucket(bucket(today, 9).hourEpoch, 3 * HOUR_MS)),
            at(today, 23),
            30,
            zone,
        )
        assertEquals(3 * HOUR_MS, report.totalMs)
        assertEquals(1, report.activeDays)
        assertEquals(3 * HOUR_MS / 30, report.averageDayMs)
        assertEquals(3 * HOUR_MS, report.maxDayMs)
    }

    @Test
    fun multipleBucketsOnSameDaySumUpAndCountOnce() {
        val report = buildListeningReport(
            listOf(bucket(today, 8), bucket(today, 9), bucket(today, 10)),
            at(today, 23),
            7,
            zone,
        )
        assertEquals(180_000L, report.totalMs)
        assertEquals("同一天三个桶只算一天活跃", 1, report.activeDays)
    }

    @Test
    fun spanIsClampedToAtLeastOneDay() {
        // days <= 0 时不能让 dayStarts 为空——否则 maxOfOrNull / 首尾取值全线崩溃
        val report = buildListeningReport(emptyList(), at(today, 12), 0, zone)
        assertEquals(1, report.days.size)
        assertEquals(0L, report.averageDayMs)
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun durationFormattingCoversTheWholeScale() {
        assertEquals("0 分钟", formatListenDuration(0L))
        assertEquals("0 分钟", formatListenDuration(-1L))
        assertEquals("不足 1 分钟", formatListenDuration(30_000L))
        assertEquals("1 分钟", formatListenDuration(60_000L))
        assertEquals("59 分钟", formatListenDuration(59 * 60_000L))
        assertEquals("1 小时", formatListenDuration(HOUR_MS))
        assertEquals("2 小时", formatListenDuration(2 * HOUR_MS))
        assertEquals("2 小时 30 分", formatListenDuration(2 * HOUR_MS + 30 * 60_000L))
    }

    @Test
    fun dayLabelIsMonthSlashDay() {
        assertEquals("10/15", formatDayLabel(at(today, 0), zone))
        assertEquals("1/1", formatDayLabel(at(LocalDate.of(2026, 1, 1), 0), zone))
        assertEquals("12/31", formatDayLabel(at(LocalDate.of(2026, 12, 31), 23), zone))
    }

    /**
     * 日期加减必须走日历而不是"减 86_400_000 毫秒"。
     *
     * 中国没有夏令时，所以这个坑在国内永远不会暴露；但只要用户把时区设成有夏令时的
     * 地区（或用 VPN/漫游），切换日只有 23 小时，按固定毫秒退天数就会与真实午夜错开
     * 一小时——而且随着天数累积越错越多。这条用纽约 2026-03-08（美东夏令时开始）
     * 钉住：那两天的本地午夜之间必须只有 23 小时。
     */
    @Test
    fun dayStartsUseRealLocalMidnightAcrossDaylightSaving() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // 美东 2026-03-08 02:00 跳到 03:00，那一"天"只有 23 小时
        val now = LocalDateTime.of(2026, 3, 10, 12, 0)
            .atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val report = buildListeningReport(emptyList(), now, 5, ny)

        val gcd = report.days.zipWithNext { a, b -> b.dayStartMs - a.dayStartMs }
        assertEquals("切换日只有 23 小时", 23 * HOUR_MS, gcd.min())
        assertEquals("其余日期是 24 小时", 24 * HOUR_MS, gcd.max())
        // 且每一天都必须是当地真实的午夜，而不是被推成 01:00
        report.days.forEach { d ->
            val c = java.util.Calendar.getInstance(ny).apply { timeInMillis = d.dayStartMs }
            assertEquals(0, c.get(java.util.Calendar.HOUR_OF_DAY))
        }
    }

    @Test
    fun zoneIsHonoredNotTakenFromTheMachine() {
        // 同一个绝对时刻：+08 已是 10/15 的早上，UTC 还在 10/14 的深夜。
        // 两边算出的「今天从哪一刻开始」必须**不同** —— 这条用来钉住 zone 参数
        // 真的被用上了，而不是被内部硬编码成 `ZoneId.systemDefault()`
        // （那样同一份代码在 CI（UTC）与开发机（+08）会得出不同的报告）。
        val instant = LocalDateTime.of(2026, 10, 15, 7, 0)
            .atZone(zoneId).toInstant().toEpochMilli()   // == UTC 2026-10-14 23:00
        val bucketAt = ListenBucket(instant / HOUR_MS, 60_000L)

        val sh = buildListeningReport(listOf(bucketAt), instant, 1, zone)
        val utcZone = TimeZone.getTimeZone("UTC")
        val utc = buildListeningReport(listOf(bucketAt), instant, 1, utcZone)

        assertEquals(at(today, 0), sh.days.last().dayStartMs)
        assertEquals(
            "UTC 视角下这一天从 UTC 零点开始（= +08 的 08:00）",
            LocalDate.of(2026, 10, 14).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            utc.days.last().dayStartMs,
        )
        assertNotEquals(sh.days.last().dayStartMs, utc.days.last().dayStartMs)
        // 两个时区里这一小时的桶都属于各自的「今天」，都不该被丢
        assertEquals(60_000L, sh.days.last().ms)
        assertEquals(60_000L, utc.days.last().ms)
    }
}
