package com.anylyric.session

import com.anylyric.lyrics.online.OnlineSource

/**
 * 设置页「歌词版本」下拉里的一项（0.2.27）。
 *
 * 为什么把 `source` 和 `preview` 一起给出去：用户要判断的是「这一版对不对」，
 * 而判断依据只有两个 —— 谁给的、第一句长什么样。序号本身没有信息量。
 */
data class LyricVariantInfo(
    val index: Int,
    /** 来源；null = 本机缓存 / 旧缓存没有标记。 */
    val source: OnlineSource?,
    /** 这一版是不是从磁盘缓存读回来的。 */
    val fromCache: Boolean,
    val lineCount: Int,
    /** 第一句非空歌词，用于一眼认出是不是同一版（可能为空）。 */
    val preview: String,
    /** 当前正在显示的那一版。 */
    val current: Boolean
)
