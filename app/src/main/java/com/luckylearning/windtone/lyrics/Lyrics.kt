package com.luckylearning.windtone.lyrics

/**
 * 歌词来源，用于界面提示。
 *
 * ⚠️ `SIDECAR_KRC` 与 `SIDECAR_LRC` 的**用户可见文案相同** —— 逐字歌词移除后
 * （v1.1.8），KRC / YRC 与普通 LRC 在界面上没有任何可感知差异，只有文件格式不同。
 * 保留两个枚举值是为了不丢失「这行歌词是从哪种文件解析出来的」这个诊断信息。
 */
enum class LyricSource(val label: String) {
    NONE("无歌词"),
    SIDECAR_LRC("本地歌词文件"),
    SIDECAR_TXT("本地文本歌词"),
    SIDECAR_KRC("本地歌词文件"),
    EMBEDDED_SYLT("内嵌同步歌词"),
    EMBEDDED("内嵌歌词"),
}

/** 一行歌词；timeMs < 0 表示无时间轴（纯文本歌词） */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    /** 双语歌词的翻译/音译副行 */
    val translation: String? = null,
)

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
