package com.anylyric.lyrics

/** TITLE 通道的判定结果。 */
enum class TitleVerdict {
    /** 标题稳定，是真正的歌名 */
    STABLE,

    /** 标题高频变化且曲目指纹不变 —— 播放器把歌词写进了 TITLE（伪歌名） */
    PSEUDO,

    /** 证据不足（刚换歌、只有一两条记录） */
    UNKNOWN
}

/**
 * 记录 `onMetadataChanged` 里的 TITLE 变化，判定发布端是否在「逐行推送歌词」。
 *
 * 判据（三条同时成立）：
 *  1. 窗口内 TITLE 变化次数 >= [minChanges]；
 *  2. duration / mediaId / artist 未变（变了就是真换歌，不是逐行歌词）；
 *  3. 相邻变化间隔的中位数 <= [maxMedianGapMs]（歌词行通常 2~5 秒一句）。
 *
 * 纯逻辑，无 Android 依赖，可直接 JVM 单测。
 */
class TitleTimeline(
    private val windowMs: Long = 12_000L,
    private val minChanges: Int = 3,
    private val maxMedianGapMs: Long = 6_000L
) {

    data class Event(val atMs: Long, val title: String, val identity: String)

    private val events = ArrayDeque<Event>()
    private var identity: String = ""
    private var identityChanged: Boolean = false

    /**
     * 记录一次元数据回调。返回本次之后的判定。
     * [mediaId] 允许为空：此时指纹退化为 duration + artist。
     */
    fun record(
        nowMs: Long,
        title: String?,
        durationMs: Long,
        mediaId: String?,
        artist: String?
    ): TitleVerdict {
        val normalized = title?.trim().orEmpty()
        if (normalized.isEmpty()) return verdict(nowMs)

        val id = "$durationMs|${mediaId.orEmpty()}|${artist.orEmpty()}"
        if (identity.isNotEmpty() && id != identity) {
            // 真换歌：清空历史，避免把上一首的歌词行算进本首
            identityChanged = true
            events.clear()
        }
        identity = id

        if (events.lastOrNull()?.title == normalized) return verdict(nowMs)

        events.addLast(Event(nowMs, normalized, id))
        trim(nowMs)
        return verdict(nowMs)
    }

    /** 读取并清除「刚发生真换歌」标记，供责任链清空 L1 缓冲。 */
    fun consumeIdentityChanged(): Boolean {
        val changed = identityChanged
        identityChanged = false
        return changed
    }

    fun reset() {
        events.clear()
        identity = ""
        identityChanged = false
    }

    fun snapshot(): List<Event> = events.toList()

    private fun trim(nowMs: Long) {
        while (events.isNotEmpty() && nowMs - events.first().atMs > windowMs) {
            events.removeFirst()
        }
    }

    private fun verdict(nowMs: Long): TitleVerdict {
        trim(nowMs)
        if (events.size < 2) return TitleVerdict.UNKNOWN
        if (events.size < minChanges) return TitleVerdict.STABLE

        val gaps = events.zipWithNext { a, b -> b.atMs - a.atMs }.sorted()
        val median = gaps[gaps.size / 2]
        return if (median <= maxMedianGapMs) TitleVerdict.PSEUDO else TitleVerdict.STABLE
    }
}
