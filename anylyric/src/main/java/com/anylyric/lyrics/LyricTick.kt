package com.anylyric.lyrics

/**
 * 定时歌词的行推进节奏。
 *
 * 为什么需要它：带时间轴的歌词（L0）只能靠「有人来问一句现在到哪了」才会换行。
 * 之前那个「有人」是**别的应用发通知** —— 代理软件一秒一条，歌词看起来还算准；
 * 一旦通知安静下来（或者用户把那个应用卸了），歌词就停在某一句不动。反过来，
 * 为了这一个功能把整条责任链挂在通知上，代价是每秒一次全量重算。
 *
 * 正确做法是按「下一行还有多久」定一个定时器：只在行边界唤醒一次，代价与通知量无关，
 * 也不再依赖外部应用的心情。本文件只做纯计算，不碰 Handler，可直接 JVM 单测。
 */
object LyricTick {

    /** 最短间隔：同一时刻堆了多行（合唱句）时，别退化成忙等。 */
    const val MIN_DELAY_MS = 50L

    /** 最长间隔：间奏可能几十秒，隔一秒复核一次就够，不必算到精确毫秒。 */
    const val MAX_DELAY_MS = 1_000L

    /** 额外余量：定时器早到 1~2ms 很常见，落到上一行会导致「该换没换」。 */
    const val MARGIN_MS = 20L

    /**
     * 距离「需要重新定位」还有多久。
     *
     * 返回 -1 表示**不需要**定时器，调用方应当停掉：
     *  · 空列表 / 没有时间轴（逐行流由元数据事件驱动，不需要自己推进）；
     *  · 进度非法（发布端给了 `PLAYBACK_POSITION_UNKNOWN`）；
     *  · 已经停在最后一行（再来也不会有变化）。
     */
    fun nextDelayMs(lines: List<LyricLine>, posMs: Long): Long {
        if (lines.isEmpty() || posMs < 0L) return -1L
        // 逐行流没有时间轴：任何时刻定位结果都是最后一行，定时器只会白转
        if (lines.any { it.timeMs == null }) return -1L

        val next = lines.firstOrNull { (it.timeMs ?: 0L) > posMs } ?: return -1L
        val distance = (next.timeMs ?: 0L) - posMs
        return (distance + MARGIN_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }
}
