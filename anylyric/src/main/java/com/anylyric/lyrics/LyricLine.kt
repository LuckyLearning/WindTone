package com.anylyric.lyrics

/**
 * 一行歌词。
 *
 * [timeMs] 为 null 表示「逐行流」——来自 L1（逐行 TITLE 推送）或 L2（通知多行文本），
 * 这类通道本身没有时间轴，行的推进由元数据变更事件驱动，不做按时间定位。
 *
 * [translation] 是**同一时间戳第二行**的内容（0.2.27）。很多双语 LRC 就是把原文与
 * 译文写成两个同时间戳的行；只有用户显式开启「显示译文」时才会被合并进来，
 * 因为同一时间戳多行也可能是合唱声部，无条件合并会把歌词吃掉。
 */
data class LyricLine(
    val timeMs: Long?,
    val text: String,
    val translation: String? = null
)
