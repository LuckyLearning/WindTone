package com.anylyric.lyrics

/**
 * 逐行 LRC 解析器（一期唯一需要的解析能力）。
 *
 * 约定与 WindTone 保持一致：**只有逐行精度**。增强 LRC / KRC / YRC 的行内字级标签
 * （`<00:12.34>` 这类）一律剥离，时间轴只取行首。
 *
 * 支持：
 *  · 一行多个时间标签 `[00:01.00][00:05.00]副歌` → 展开成多行；
 *  · `[offset:+500]` 整体偏移（正数表示歌词提前，故实际时间 = 原时间 - offset）；
 *  · 毫秒 1 / 2 / 3 位（`.5` / `.50` / `.500`）；
 *  · 乱序行按时间排序，同时间同文本去重，纯空白行丢弃。
 */
object LrcParser {

    /** `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]` */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** `[offset:+500]` / `[offset:-200]` */
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** 行内字级时间标签，如 `<00:12.34>` */
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val offset = OFFSET_TAG.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val collected = ArrayList<LyricLine>(64)
        for (rawLine in raw.lineSequence()) {
            var rest = rawLine.trimStart()
            val times = ArrayList<Long>(2)
            // 时间标签必须从行首连续出现，避免把歌词正文里的方括号当时间轴
            while (true) {
                val m = TIME_TAG.find(rest) ?: break
                if (m.range.first != 0) break
                times += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue

            val text = WORD_TAG.replace(rest, "").trim()
            if (text.isEmpty()) continue
            for (t in times) {
                collected += LyricLine((t - offset).coerceAtLeast(0L), text)
            }
        }
        if (collected.isEmpty()) return emptyList()

        collected.sortBy { it.timeMs ?: 0L }
        val deduped = ArrayList<LyricLine>(collected.size)
        for (line in collected) {
            val last = deduped.lastOrNull()
            if (last != null && last.timeMs == line.timeMs && last.text == line.text) continue
            deduped += line
        }
        return deduped
    }

    /**
     * 把行表重新写成 LRC 文本（只保留时间轴，丢掉 `[ti:]` 之类的标签）。
     *
     * 用途只有一个：在线取到的结果要落盘缓存，下次冷启动直接读，不再联网。
     * 只存 `timeMs` + 文本就够 —— 我们本来就只支持逐行精度，字级标签解析时已经剥掉。
     */
    fun build(lines: List<LyricLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder(lines.size * 24)
        for (line in lines) {
            val time = line.timeMs
            if (time != null) {
                val total = time.coerceAtLeast(0L)
                val minutes = total / 60_000L
                val seconds = (total % 60_000L) / 1_000L
                val millis = total % 1_000L
                sb.append('[')
                if (minutes < 10) sb.append('0')
                sb.append(minutes).append(':')
                if (seconds < 10) sb.append('0')
                sb.append(seconds).append('.')
                if (millis < 100) sb.append('0')
                if (millis < 10) sb.append('0')
                sb.append(millis)
                sb.append(']')
            }
            sb.append(line.text).append('\n')
        }
        return sb.toString()
    }

    /**
     * 内容特征判定：是否像一段带时间轴的歌词。
     * 用于 L0 / L2 在没有明确键名时做兜底识别，必须配合「解析出 >= 2 行」再采纳。
     */
    fun looksLikeLrc(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (!raw.contains('[') || !raw.contains(']')) return false
        return TIME_TAG.containsMatchIn(raw)
    }

    private fun toMs(min: String, sec: String, frac: String?): Long {
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L
        val ms = when {
            frac.isNullOrEmpty() -> 0L
            frac.length == 1 -> (frac.toLongOrNull() ?: 0L) * 100L
            frac.length == 2 -> (frac.toLongOrNull() ?: 0L) * 10L
            else -> (frac.substring(0, 3).toLongOrNull() ?: 0L)
        }
        return m * 60_000L + s * 1_000L + ms
    }
}
