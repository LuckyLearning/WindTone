package com.luckylearning.windtone.playback

/**
 * 后台预取的**纯规则**（v1.1.46）：能预取谁、什么时候允许开始。
 *
 * 为什么单独抽出来：并发预取一旦乱来，会直接抢掉当前曲的带宽，听感从「慢」变成
 * 「卡」，比不做还糟。这几条判断是它唯一的安全边界，所以用可测的纯函数钉住。
 */
object CachePrefetchPolicy {

    /** 一次最多预取几首。缓存 512MB，两首无损约 60MB，够覆盖切歌的两个间隙 */
    const val MAX_SONGS = 2

    /**
     * 播放中「已缓冲但还没播」的时长达到这个值才允许开始预取。
     *
     * 20 秒是保守值：即使预取把可用带宽吃掉大半，当前这首也还有 20 秒余量去补齐。
     * 缓冲不够时宁可这首也慢一点，也不能让它中途断。
     */
    const val MIN_AHEAD_MS = 20_000L

    /** 两次「规划」之间的最小间隔 —— 播放器每 200ms 走一次 tick，不需要每次都想一遍 */
    const val PLAN_INTERVAL_MS = 3_000L

    /**
     * 每个预取连接的限速。
     *
     * 百度对第三方应用本身就有单连接限速（实测量级在 100~300 KB/s），这个上限只是
     * 兜住「线路很快」的情况：不加限制时两条预取连接能把家用宽带占满，正在播的那首
     * 反而要等。
     */
    const val BYTES_PER_SECOND = 320 * 1024

    fun isRemote(scheme: String?): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    /**
     * 从「接下来的曲目」里挑出要预取的下标。
     *
     * 只认 http(s)：本地文件已经是本地的，预取等于白拷一份；`content://` 走缓存层还可能
     * 因为不可随机读而失败。跳过的本地曲目不占名额，所以队列里「本地+在线」混排时也能
     * 真正找到两首远程歌。
     */
    fun candidates(schemes: List<String?>, max: Int = MAX_SONGS): List<Int> {
        val out = ArrayList<Int>(max)
        for (i in schemes.indices) {
            if (out.size >= max) break
            if (isRemote(schemes[i])) out += i
        }
        return out
    }

    /**
     * 现在允许开始预取吗。
     *
     * [currentTailOpen] 表示当前曲从播放位置往前的缺口还没补到 [MIN_AHEAD_MS]，
     * 这时带宽留给这一条连接。文件后半段还没进缓存不算缺口：播放器最多只垫 120 秒。
     * 暂停且缺口已补上时可以预取。播放中则要缓冲领先足够。
     */
    fun shouldStart(playing: Boolean, bufferAheadMs: Long, currentTailOpen: Boolean = false): Boolean {
        if (currentTailOpen) return false
        return !playing || bufferAheadMs >= MIN_AHEAD_MS
    }
}
