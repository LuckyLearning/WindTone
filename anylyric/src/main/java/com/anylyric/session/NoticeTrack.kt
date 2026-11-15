package com.anylyric.session

/**
 * 「媒体通知」当作播放源的纯逻辑（0.2.26）。
 *
 * 为什么需要它：车机上有一类播放器**不发标准 `MediaSession`**，或发了却只挂一个
 * 空壳（TITLE / ARTIST 全空、state 一直是 NONE）。系统媒体中心照样能从它的通知里
 * 读到「歌名 + 歌手 - 专辑」，而本应用只能看到一堆会话里没有它 —— 表现就是
 * 「连歌名都识别不到，屏上还挂着上一个 App 的歌名」。
 *
 * 这里只做三件事，全部是纯函数，方便单测：
 *  1. 判断一条通知算不算「媒体通知」（`CATEGORY_TRANSPORT` 或带 `EXTRA_MEDIA_SESSION`）；
 *  2. 判断通知标题能不能当歌名 —— 不能把「酷我音乐」这种常驻通知的应用名当成歌名；
 *  3. 在若干条媒体通知里挑「最新且还新鲜」的那一条。
 *
 * 不猜歌手、不猜歌词：标题取不到就整条放弃，回到「没有会话就不显示」的旧行为。
 */
data class NoticeTrack(
    val pkg: String,
    val title: String,
    val artist: String?,
    /** 通知正文（多行时可能是现成的歌词窗口）。 */
    val body: String?,
    /** 收到 / 内容变化的时刻（elapsedRealtime）。 */
    val updatedAtMs: Long
)

object NoticeTrackPick {

    /**
     * 媒体通知的有效期。
     *
     * 播放器暂停时通知不会消失、也不再刷新，所以这个值要能跨过一段暂停（电话、
     * 下车买东西），否则恢复播放前后那几秒会退回上一个 App 的歌词。真正的「已经
     * 不放了」由 `onNotificationRemoved` 立即清掉，这里只是防「播放器被杀死时
     * 系统没发取消回调」的残留。
     */
    const val FRESH_MS = 120_000L

    /**
     * 通知标题能不能当歌名。
     *
     * `appLabel` 过滤的是常驻通知：酷我、QQ 音乐这类应用在没有播放时也会留一条
     * 通知，标题就是应用名本身。把它当歌名去在线搜，只会搜到「酷我音乐」这首歌
     * 或者干脆没结果 —— 比空着更糟。
     */
    fun usableTitle(title: String?, appLabel: String?): String? {
        val text = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val label = appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        // 「酷我音乐」「酷我音乐 正在播放」都要挡掉；只挡完全相等会漏后一种
        if (text.equals(label, ignoreCase = true)) return null
        if (text.startsWith("$label ", ignoreCase = true)) return null
        return text
    }

    /**
     * 这条媒体通知该不该接管「当前绑定的会话」。
     *
     * 车机上最典型的一幕：上一个播放器停了，`MediaSession` 却没释放（状态还是 PAUSED，
     * 元数据还是上一首）。用户打开一个不发会话的播放器（酷我车机版），于是
     * `getActiveSessions` 里还是旧那个 —— 屏上就一直挂着旧名字。判据只有两条：
     *  · 同一个包 → 不接管（同一首歌，交回正常链路，那里有更完整的元数据）；
     *  · 会话在播且说得出歌名 → 不接管（真的有两个播放器在放，别乱抢）。
     *
     * 其余情况（会话停了 / 会话是空壳）都认为「正在更新的那条通知才是用户眼前的」。
     */
    fun shouldTakeOver(
        sessionPkg: String,
        sessionPlaying: Boolean,
        sessionTitle: String?,
        notice: NoticeTrack
    ): Boolean {
        if (notice.pkg == sessionPkg) return false
        if (!sessionPlaying) return true
        return sessionTitle.isNullOrBlank()
    }

    /**
     * 挑一个可用的媒体通知。
     *
     * @param pinned 手动锁定的包名；非空时只认它 —— 与 [SessionPin.narrow] 同一条语义：
     *   锁了谁就只读谁，锁的那个没在放就宁可不出歌词。
     */
    fun pick(
        all: Collection<NoticeTrack>,
        nowMs: Long,
        pinned: String? = null
    ): NoticeTrack? {
        var best: NoticeTrack? = null
        for (track in all) {
            val age = nowMs - track.updatedAtMs
            if (age < 0 || age > FRESH_MS) continue
            if (!pinned.isNullOrBlank() && track.pkg != pinned) continue
            if (best == null || track.updatedAtMs > best.updatedAtMs) best = track
        }
        return best
    }
}
