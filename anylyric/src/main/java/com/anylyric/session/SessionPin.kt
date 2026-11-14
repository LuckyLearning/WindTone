package com.anylyric.session

/**
 * 「播放器频道」锁定的纯逻辑（0.2.17）。
 *
 * 单独拎出来是为了能脱离 `MediaController` 做单测 —— 锁定语义只有一条，
 * 但错一次就是「锁了反而没歌词」，必须有回归测试兜着。
 */
object SessionPin {

    /**
     * 把候选会话缩到锁定的那个包。
     *
     * - `pinned` 为空 = 自动模式，原样返回（一条都不能少）；
     * - 锁定但该包不在候选里 → 返回空列表，由调用方落回「不显示歌词」。
     *   **这是有意的**：车机上导航语音会临时发布会话，回退去读它就是用户抱怨的
     *   「切过去歌词变成别人的」。
     */
    fun <T> narrow(items: List<T>, pinned: String, packageOf: (T) -> String): List<T> {
        if (pinned.isBlank()) return items
        return items.filter { packageOf(it) == pinned }
    }
}
