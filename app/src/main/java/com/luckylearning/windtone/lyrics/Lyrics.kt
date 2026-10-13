package com.luckylearning.windtone.lyrics

/** 歌词来源，用于界面提示 */
enum class LyricSource(val label: String) {
    NONE("无歌词"),
    SIDECAR_LRC("本地歌词文件"),
    SIDECAR_TXT("本地文本歌词"),
    SIDECAR_KRC("本地逐字歌词"),
    EMBEDDED_SYLT("内嵌同步歌词"),
    EMBEDDED("内嵌歌词"),
}

/** 逐字时间轴：一个字/词的起始时间与时长（毫秒） */
data class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

/** 一行歌词；timeMs < 0 表示无时间轴（纯文本歌词） */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    /** 双语歌词的翻译/音译副行 */
    val translation: String? = null,
    /** 逐字时间轴（KRC / YRC / 增强 LRC），为空表示逐行精度 */
    val words: List<LyricWord> = emptyList(),
    /** 行时长（逐字歌词用于收尾高亮） */
    val lineDurationMs: Long = 0L,
) {
    val hasWords: Boolean get() = words.isNotEmpty()
}

data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val synced: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val offsetMs: Int = 0,
    val source: LyricSource = LyricSource.NONE,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** 是否含逐字时间轴 */
    val hasWordTiming: Boolean get() = lines.any { it.hasWords }

    /** 返回 positionMs 时刻应当高亮的行号；无匹配返回 -1 */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty() || !synced) return -1
        if (positionMs < lines[0].timeMs) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        val EMPTY = Lyrics()
    }
}
