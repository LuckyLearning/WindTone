package com.luckylearning.windtone.playback.parallel

/** 一个字节区间（闭区间），用于「把整首切成 N 段」 */
data class RangeSegment(val index: Int, val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

/**
 * 分片规划（v1.1.47）—— 纯函数，无网络无 Android，可完整单测。
 *
 * 为什么需要它：并行下载的所有风险都集中在「切出来的段是否合法」上 —— 段重叠会写坏数据、
 * 段漏掉会让播放卡在空洞上、段太小则多连接的开销大于收益。把这一段逻辑抽出来钉住，
 * 剩下那几百行线程代码才敢写。
 *
 * 几条规则：
 *  - 太小的文件不切（[MIN_TOTAL_BYTES]）：一首 300KB 的 mp3 用多连接，握手比下载还贵；
 *  - 每段至少 [MIN_BYTES_PER_SEGMENT]：避免出现「5 段各 40KB」这种没意义的分片；
 *  - 段必须**首尾相接、不重叠、不留缝**，最后一段收在 totalBytes-1；
 *  - 长度未知（服务器不给 Content-Length）一律返回空 -> 调用方降级单连接。
 */
object SegmentPlanner {

    /** 默认并发数（A11：5→3；车机省连接，设置开并行时也偏省） */
    const val DEFAULT_SEGMENTS = 3

    /** 单段最小 1MB：再小就只是把开销放大 */
    const val MIN_BYTES_PER_SEGMENT = 1L * 1024 * 1024

    /** 整首小于 512KB 不并行 */
    const val MIN_TOTAL_BYTES = 512L * 1024

    fun plan(totalBytes: Long, requested: Int = DEFAULT_SEGMENTS): List<RangeSegment> {
        if (totalBytes <= 0) return emptyList()
        if (totalBytes < MIN_TOTAL_BYTES) return emptyList()
        if (requested <= 0) return emptyList()

        val maxBySize = (totalBytes / MIN_BYTES_PER_SEGMENT).toInt().coerceAtLeast(1)
        val count = minOf(requested, maxBySize)
        if (count <= 1) return emptyList()

        val base = totalBytes / count
        val remainder = totalBytes % count
        val segments = ArrayList<RangeSegment>(count)
        var start = 0L
        for (i in 0 until count) {
            // 余数摊到前几段，保证总和精确等于 totalBytes
            val len = base + if (i < remainder) 1L else 0L
            val end = start + len - 1
            segments += RangeSegment(i, start, end)
            start = end + 1
        }
        return segments
    }

    /** position 落在哪一段；越界返回 -1 */
    fun indexOf(segments: List<RangeSegment>, position: Long): Int {
        if (position < 0) return -1
        // 段数很少，线性找足够，不必二分
        for (s in segments) if (position in s.start..s.endInclusive) return s.index
        return -1
    }
}
