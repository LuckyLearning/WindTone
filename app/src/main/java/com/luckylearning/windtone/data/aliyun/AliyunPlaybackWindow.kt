package com.luckylearning.windtone.data.aliyun

/**
 * 云盘队列取直链的滑动窗口。
 *
 * 整张歌单都进播放队列，但只有窗口里的歌才去要播放地址。播到窗口边缘时，
 * 下一轮再补新进来的那几首。直链大约 4 小时过期，提前把整表都取下来没有意义。
 */
object AliyunPlaybackWindow {

    /** 还没取到直链的占位地址。播放器不会拿它去下载。 */
    const val PENDING_SCHEME = "aliyun-pending"

    /** 当前曲后面预先准备的首数。播放器会提前打开下一首，所以至少要留一首余量。 */
    const val AHEAD = 2

    /** 当前曲前面保留的首数，点上一首时不用现等。 */
    const val BEHIND = 1

    fun isPending(scheme: String?): Boolean = scheme == PENDING_SCHEME

    /**
     * 以 [current] 为中心、夹在队列里的下标区间。
     * 队列为空或下标越界时返回空区间。
     */
    fun range(current: Int, size: Int, ahead: Int = AHEAD, behind: Int = BEHIND): IntRange {
        if (size <= 0 || current !in 0 until size) return IntRange.EMPTY
        val from = (current - behind).coerceAtLeast(0)
        val to = (current + ahead).coerceAtMost(size - 1)
        return from..to
    }

    /** 窗口里还没有直链的下标，按播放顺序。 */
    fun pendingIndices(schemes: List<String?>, current: Int): List<Int> {
        val span = range(current, schemes.size)
        if (span.isEmpty()) return emptyList()
        return span.filter { schemes[it] == PENDING_SCHEME }
    }

    /**
     * 从 [current] 起，窗口内第一首已经有直链的歌。
     * 当前这首失败时改从后面那首起播，而不是把整张表再请求一遍。
     */
    fun firstReady(isPending: List<Boolean>, current: Int): Int? {
        if (isPending.isEmpty()) return null
        val start = current.coerceIn(0, isPending.lastIndex)
        val span = range(start, isPending.size)
        return span.firstOrNull { it >= start && !isPending[it] }
    }
}
