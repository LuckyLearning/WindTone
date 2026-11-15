package com.anylyric.lyrics.online

import com.anylyric.lyrics.LyricLine

/**
 * 在线补词取回的一**版**歌词（0.2.27 起带来源）。
 *
 * 为什么必须带上来源：用户点「换一版」时看到的是第 1/2/3 版，但真正决定「这一版对不对」
 * 的是它从哪来 —— 同一首歌酷狗给的是原版时间轴，网易云可能给的是翻唱版。
 * 没有来源标记，用户只能靠猜；有了它，至少能说「这条是汽水来的，八成不对」。
 *
 * [source] 为 null 有两种情况：磁盘里是旧版本写的缓存（没有标记），或者是本地自己
 * 拼出来的词。两者都按「本机缓存」展示，不假装知道出处。
 */
data class OnlineVariant(
    val lines: List<LyricLine>,
    val source: OnlineSource? = null,
    /** 这版来自磁盘缓存（同一首歌上次的首选）。UI 上与网络结果分开表述。 */
    val fromCache: Boolean = false
)
