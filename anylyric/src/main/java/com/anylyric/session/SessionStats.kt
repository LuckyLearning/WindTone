package com.anylyric.session

/**
 * 最近若干次播放会话的本地统计（0.2.27）。
 *
 * **只在本进程内存里，不落盘、不上传**。产品的隐私定位决定了不能做远程埋点，
 * 但用户在车里确实需要一个东西来回答「今天是不是比之前差」。所以这里只算三个数：
 * 出词率、首句延迟 p50 / p90 —— 恰好覆盖「出不来词」和「出来得太慢」两类抱怨。
 *
 * 环形缓冲容量 50：够看出趋势，又不会因为一次长途几十首歌把内存撑起来。
 */
class SessionStats(private val capacity: Int = 50) {

    private class Entry(val durationMs: Long, val firstLineMs: Long?)

    private val entries = ArrayDeque<Entry>()

    private var startedAt = 0L
    private var firstLineAt: Long? = null
    private var recording = false

    /** 播放开始。同一次会话内的缓冲 / 短暂暂停不应重开计时。 */
    fun onPlayStart(nowMs: Long) {
        if (recording) return
        recording = true
        startedAt = nowMs
        firstLineAt = null
    }

    /** 第一行真实歌词上屏（不是「正在获取」这类提示）。只记第一次。 */
    fun onFirstLine(nowMs: Long) {
        if (!recording || firstLineAt != null) return
        firstLineAt = nowMs
    }

    /** 播放结束 / 换歌。会话太短不记 —— 试听与切歌路过会把出词率拉得毫无意义。 */
    fun onPlayStop(nowMs: Long) {
        if (!recording) return
        recording = false
        val duration = (nowMs - startedAt).coerceAtLeast(0L)
        if (duration < MIN_SESSION_MS) return
        val first = firstLineAt?.let { (it - startedAt).coerceAtLeast(0L) }
        entries.addLast(Entry(duration, first))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** 是否有正在计时的会话（诊断页显示「本曲计时中」）。 */
    fun isRecording(): Boolean = recording

    fun snapshot(): Snapshot {
        val total = entries.size
        if (total == 0) return Snapshot(0, 0, 0f, null, null)
        val withLyrics = entries.count { it.firstLineMs != null }
        val delays = entries.mapNotNull { it.firstLineMs }.sorted()
        return Snapshot(
            sessions = total,
            withLyrics = withLyrics,
            hitRate = withLyrics.toFloat() / total,
            p50FirstLineMs = percentile(delays, 0.50),
            p90FirstLineMs = percentile(delays, 0.90)
        )
    }

    fun clear() {
        entries.clear()
        recording = false
        startedAt = 0L
        firstLineAt = null
    }

    private fun percentile(sorted: List<Long>, ratio: Double): Long? {
        if (sorted.isEmpty()) return null
        // 最近秩法：取「至少有 ratio 比例的数据不超过它」的那个。
        val index = (kotlin.math.ceil(sorted.size * ratio).toInt() - 1)
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    data class Snapshot(
        val sessions: Int,
        val withLyrics: Int,
        val hitRate: Float,
        val p50FirstLineMs: Long?,
        val p90FirstLineMs: Long?
    )

    companion object {
        /** 短于 30 秒的会话不计入：这不是「一次播放」，只是路过。 */
        const val MIN_SESSION_MS = 30_000L
    }
}
