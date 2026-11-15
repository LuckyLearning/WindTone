package com.anylyric.session

/**
 * 「换一版歌词」的索引推进（0.2.26）。
 *
 * 抽成纯函数只有一个理由：这条规则出错的后果很具体 —— 要么点了没反应（用户以为坏），
 * 要么在只有 1 版时反复清屏重取（屏上歌词闪）。三种结局必须各归各位：
 *  · 本地还有下一版 → 直接给新索引；
 *  · 已经到版本上限 → 循环回第 0 版（「再点一下回到最初那版」是可以预期的）；
 *  · 还有余量但没有现成的 → -1，调用方去联网再找一版。
 */
object VariantCycle {

    /** 返回下一版的索引；-1 表示本地没有更多，需要联网（见 [SessionHub.nextLyricVariant]）。 */
    fun nextIndex(current: Int, size: Int, max: Int): Int {
        if (size <= 0) return -1
        if (current < 0) return 0
        if (current + 1 < size) return current + 1
        // 已到上限：循环。**不能**返回 -1 —— 上限是「最多取 3 版」，不是「没有更多」。
        if (size >= max) return 0
        return -1
    }
}
