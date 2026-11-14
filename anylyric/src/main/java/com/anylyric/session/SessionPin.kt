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

    /**
     * 选择列表用：同一应用的多个会话**合并成一条**，正在播放的排在前面。
     *
     * 一个 App 同时挂两三个媒体会话是常态（阅读类尤其明显：正文朗读一个、通知栏一个），
     * `getActiveSessions` 会原样返回全部 —— 直接铺进列表就是用户看到的「同一个应用
     * 出现两次、状态还不一样」。选择器按**应用**选，所以按包名去重；
     * 保留「更像在放」的那条（在播放 > 有标题 > 先出现的）。
     *
     * 注意：这里只影响**展示**。真正取歌词时 [narrow] 仍保留该包的全部会话，
     * 由 `pickBest` 在里面挑，不能把「同包多会话」当成重复砍掉。
     */
    fun collapse(infos: List<SessionInfo>): List<SessionInfo> {
        val best = LinkedHashMap<String, SessionInfo>()
        infos.forEach { info ->
            val current = best[info.packageName]
            if (current == null || rank(info) > rank(current)) best[info.packageName] = info
        }
        // 稳定排序：同档次里保持「先出现的在前」，列表不会每次刷新都跳。
        return best.values.sortedByDescending { rank(it) }
    }

    private fun rank(info: SessionInfo): Int =
        (if (info.playing) 2 else 0) + (if (!info.title.isNullOrBlank()) 1 else 0)
}
