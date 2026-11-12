package com.anylyric.lyrics

/**
 * 一行歌词。
 *
 * [timeMs] 为 null 表示「逐行流」——来自 L1（逐行 TITLE 推送）或 L2（通知多行文本），
 * 这类通道本身没有时间轴，行的推进由元数据变更事件驱动，不做按时间定位。
 */
data class LyricLine(
    val timeMs: Long?,
    val text: String
)
