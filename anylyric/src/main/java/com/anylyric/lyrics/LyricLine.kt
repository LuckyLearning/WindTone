package com.anylyric.lyrics

/**
 * 一个字（或一个不可切分的字组）及其起始时间（0.2.28）。
 *
 * 为什么按「字」而不是「词」：中文歌词的增强 LRC 与 KRC 都是**按字**给时间戳的，
 * 按词再切一次只会引入误差；而「逐字高亮」在视觉上本来就是字级别跳变。
 */
data class LyricWord(val text: String, val timeMs: Long)

/**
 * 一行歌词。
 *
 * [timeMs] 为 null 表示「逐行流」——来自 L1（逐行 TITLE 推送）或 L2（通知多行文本），
 * 这类通道本身没有时间轴，行的推进由元数据变更事件驱动，不做按时间定位。
 *
 * [translation] 是**同一时间戳第二行**的内容（0.2.27）。很多双语 LRC 就是把原文与
 * 译文写成两个同时间戳的行；只有用户显式开启「显示译文」时才会被合并进来，
 * 因为同一时间戳多行也可能是合唱声部，无条件合并会把歌词吃掉。
 *
 * [words] 是**字级时间轴**（0.2.28）。null = 这份歌词没有字级信息；开启逐字时
 * 由会话层按行时长均分出「伪字级」，保证任何一份歌词都能有逐字效果。
 * `text` 始终是**去掉字级标签后**的纯文本，两者不能互相推导。
 */
data class LyricLine(
    val timeMs: Long?,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null
)
