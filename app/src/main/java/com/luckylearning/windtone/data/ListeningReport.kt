package com.luckylearning.windtone.data

import java.util.Calendar
import java.util.TimeZone

/**
 * 收听报告的**纯计算**部分。
 *
 * 为什么单拎出来：报告的输入只有「按小时聚合的收听时长桶」一个数据源，
 * 输出是「日曲线 + 时段分布」，中间全是日历换算与分桶——正是最容易差一天、
 * 差一个小时、被夏令时坑到的地方，而这些用 JVM 单测能全部钉死。
 *
 * 时间基准一律走 [TimeZone]（默认系统时区）：日边界必须是**本地午夜**，
 * 否则 UTC+8 的用户会在早上 8 点看到"新的一天"。
 *
 * ⚠ **这里刻意不用 `java.time`**：`minSdk = 24`，而 `java.time` 要 API 26。
 * 低版本上不是"降级"，是直接 `NoClassDefFoundError` 崩在打开报告那一刻
 * （lint 的 `NewApi` 就是这么报的）。要换成 `java.time` 得先开
 * `isCoreLibraryDesugaringEnabled` 并引入 `desugar_jdk_libs`——为了一个 P2 报表
 * 不值得全局脱糖，所以走 [Calendar] + [TimeZone]（API 1 起就有）。
 * 日期加减一律用 `Calendar.add(DAY_OF_MONTH, ±n)` 而不是加减 86_400_000 毫秒：
 * 夏令时切换日只有 23 或 25 小时，按固定毫秒退天数会慢慢错开。
 */

/** 一个小时桶：`hourEpoch` 是 `epochMillis / 3_600_000`（UTC 整点） */
data class ListenBucket(val hourEpoch: Long, val ms: Long)

/** 某一天（本地午夜起）的收听时长，`dayStartMs` 为该日本地零点的时间戳 */
data class DayStat(val dayStartMs: Long, val ms: Long)

/** 某个时钟小时（0..23）的累计时长 */
data class HourStat(val hour: Int, val ms: Long)

data class ListeningReport(
    /** 按天升序，长度 = 请求的天数；没有收听的那天是 0 而不是缺项（曲线要连续） */
    val days: List<DayStat>,
    /** 固定 24 项，`hour` 从 0 到 23 */
    val hours: List<HourStat>,
    /** 本次统计范围内（即传入的全部桶）的收听总时长 */
    val totalMs: Long,
    /** 有收听的桶所覆盖的天数（多少天里真的听过东西） */
    val activeDays: Int,
) {
    /** 一天里听得最多的时段；范围内没有收听时为 null */
    val peakHour: HourStat? get() = hours.maxByOrNull { it.ms }?.takeIf { it.ms > 0 }

    /** 单日峰值时长，用于把曲线归一化成柱高 */
    val maxDayMs: Long get() = days.maxOfOrNull { it.ms } ?: 0L

    /** 日均时长（按范围内全部天数算，含没听的日子） */
    val averageDayMs: Long get() = if (days.isEmpty()) 0L else totalMs / days.size
}

const val HOUR_MS = 3_600_000L

/**
 * 把小时桶聚合成日曲线与时段分布。
 *
 * @param buckets 传入前应已被数据库按 `hourEpoch >= 起算小时` 过滤；范围外的桶会被丢弃
 * @param nowMs   当前时间（用于确定"今天"是哪天）
 * @param days    要看多少天（含今天），例如 7 / 30
 * @param zone    本地时区（日边界与时段都按它算）
 */
fun buildListeningReport(
    buckets: List<ListenBucket>,
    nowMs: Long,
    days: Int,
    zone: TimeZone = TimeZone.getDefault(),
): ListeningReport {
    val span = days.coerceAtLeast(1)

    // 一个 Calendar 复用到底：先把「今天」的本地午夜算出来，再整日整日地退到范围首日。
    val cal = Calendar.getInstance(zone)
    cal.timeInMillis = nowMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_MONTH, -(span - 1))

    val dayStarts = LongArray(span)
    for (i in 0 until span) {
        dayStarts[i] = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    val rangeStart = dayStarts[0]
    // 走完 span 天后的同一时刻就是窗口上界（含今天的话即"明天本地零点"）
    val rangeEnd = cal.timeInMillis

    val perDay = LongArray(span)
    val perHour = LongArray(24)
    var total = 0L
    var activeDays = 0

    for (b in buckets) {
        if (b.ms <= 0L) continue
        val at = b.hourEpoch * HOUR_MS
        if (at < rangeStart || at >= rangeEnd) continue
        total += b.ms
        // 桶落在哪一天：dayStarts 升序，最后一个 <= at 的即所属天
        val idx = dayStarts.indexOfLast { it <= at }
        if (idx >= 0) {
            if (perDay[idx] == 0L) activeDays++
            perDay[idx] += b.ms
        }
        // 时段按桶**起点**的本地小时归属。桶起点本身是 UTC 整点，所以
        // 本地小时 = (UTC 毫秒 + 该时刻的时区偏移) 取模 24 小时；
        // `getOffset(at)` 是"就那一瞬间"的偏移，夏令时切换当天也是对的。
        // （UTC 半时区会有至多半小时的错位，可接受。）
        val shifted = at + zone.getOffset(at)
        val hour = Math.floorMod(shifted / HOUR_MS, 24L).toInt()
        perHour[hour] += b.ms
    }

    return ListeningReport(
        days = dayStarts.mapIndexed { i, start -> DayStat(start, perDay[i]) },
        hours = (0..23).map { HourStat(it, perHour[it]) },
        totalMs = total,
        activeDays = activeDays,
    )
}

/**
 * 把某天的起点时间戳写成「9/28」。
 *
 * 放在这一层（而不是界面里）是为了让界面彻底不碰日历 API —— 它一旦自己算，
 * 就又会顺手用上 `java.time`（见文件头的 minSdk 说明）。
 */
fun formatDayLabel(dayStartMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = dayStartMs }
    return "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
}

/** 把毫秒写成"3 小时 12 分"这种可读形式；不足 1 分钟写"不足 1 分钟" */
fun formatListenDuration(ms: Long): String {
    if (ms <= 0L) return "0 分钟"
    val minutes = ms / 60_000L
    if (minutes < 1L) return "不足 1 分钟"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h <= 0L -> "$m 分钟"
        m == 0L -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}
