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

    /** 行内字级时间标签，如 `<00:12.34>`。捕获组用于取出字级时间。 */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

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
            // 字级时间轴（0.2.28）：增强 LRC / KRC 用 `<mm:ss.xx>` 标每个字的起点。
            // 只有一行一个时间标签时才认 —— 一行多个 `[..]` 是「同一句重复出现在多处」，
            // 字级时间只对应其中一处，硬套会把另一处的时间弄错。
            val words = if (times.size == 1) parseWords(rest, offset) else null
            for (t in times) {
                collected += LyricLine((t - offset).coerceAtLeast(0L), text, words = words)
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
            if (time != null) appendStamp(sb, time, '[', ']')
            // 字级时间轴（0.2.28）必须原样写回：否则缓存一轮之后逐字就退化成逐行了。
            // 但要求 words 拼起来**确实等于** text —— 中间被 stripTitleLine / 译文合并
            // 动过的行不满足这个条件，那种情况退回纯文本，宁可丢逐字也不丢歌词。
            val words = line.words
            if (!words.isNullOrEmpty() && words.joinToString("") { it.text } == line.text) {
                for (word in words) {
                    appendStamp(sb, word.timeMs, '<', '>')
                    sb.append(word.text)
                }
            } else {
                sb.append(line.text)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 写一个时间标签：`[mm:ss.SSS]` 或 `<mm:ss.SSS>`。毫秒固定三位，避免精度损失。 */
    private fun appendStamp(sb: StringBuilder, timeMs: Long, open: Char, close: Char) {
        val total = timeMs.coerceAtLeast(0L)
        val minutes = total / 60_000L
        val seconds = (total % 60_000L) / 1_000L
        val millis = total % 1_000L
        sb.append(open)
        if (minutes < 10) sb.append('0')
        sb.append(minutes).append(':')
        if (seconds < 10) sb.append('0')
        sb.append(seconds).append('.')
        if (millis < 100) sb.append('0')
        if (millis < 10) sb.append('0')
        sb.append(millis).append(close)
    }

    /**
     * 去掉在线 LRC 开头的「歌名 - 歌手」行。
     *
     * 酷狗的 LRC 第一行常常是 `[00:00.00]周杰伦 - 稻香`（2026-11 实测「稻香」就是这样），
     * 画出来等于整首歌从第一句起就写着歌名，用户看到的第一反应就是「当前行对不上」。
     *
     * 判据刻意保守：只在**开头 2 秒内**、且该行归一后包含完整歌名（歌手也包含或未知）、
     * 长度也不比「歌名+歌手+分隔符」长太多时才删一行。宁可漏删，不能误删真歌词。
     */
    fun stripTitleLine(
        lines: List<LyricLine>,
        title: String?,
        artist: String?
    ): List<LyricLine> {
        if (lines.size < 2) return lines
        val first = lines.first()
        val time = first.timeMs ?: return lines
        if (time > 2_000L) return lines
        val titleN = LyricMatch.normalize(LyricMatch.coreTitle(title))
        if (titleN.length < 2) return lines
        val artistN = LyricMatch.normalize(LyricMatch.coreTitle(artist))
        val textN = LyricMatch.normalize(first.text)
        if (!textN.contains(titleN)) return lines
        if (artistN.isNotEmpty() && !textN.contains(artistN)) return lines
        // 长句里恰好出现歌名（「我唱了一首稻香」）不是标题行。
        if (textN.length > titleN.length + artistN.length + 8) return lines
        return lines.drop(1)
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

    /**
     * 无时间轴的整段文本 → 按固定间隔铺成行（Cardash `T2` 的 2000ms 口径）。
     *
     * 在线源偶尔只给纯文本（VIP 歌、新歌最常见）。旧实现解析出 0 行就判「没取到」，
     * 于是同一首歌永远停在「暂无歌词」；现在宁可给一份按 2 秒一句推进的词 ——
     * 时间轴不准，但用户能跟着唱，而且落盘后每次进歌都是同一份，不会忽有忽无。
     *
     * 少于 2 行不算歌词：单行多半是「纯音乐，请欣赏」这类提示语。
     */
    fun spread(raw: String?, stepMs: Long = 2_000L): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val step = stepMs.coerceAtLeast(200L)
        val out = ArrayList<LyricLine>(32)
        var index = 0
        for (rawLine in raw.lineSequence()) {
            val text = rawLine.trim()
            if (text.isEmpty()) continue
            // `[ti:…]` `[ar:…]` `[by:…]` 这类元信息不是歌词
            if (text.startsWith("[") && text.endsWith("]")) continue
            out += LyricLine(index * step, text)
            index++
        }
        return if (out.size >= 2) out else emptyList()
    }

    /**
     * 解析行内的字级时间轴。
     *
     * 每个 `<time>` 之后、下一个 `<time>` 之前的所有字符都属于该字 —— 这样
     * `<00:12.00>我<00:12.30>们` 得到「我@12.00、们@12.30」，而
     * `<00:12.00>Hello <00:12.50>world` 得到「Hello @12.00、world@12.50」（含空格）。
     *
     * 一个字都解不出来时返回 null（调用方据此退回「按行时间均分」）。
     */
    private fun parseWords(raw: String, offset: Long): List<LyricWord>? {
        val matches = WORD_TAG.findAll(raw).toList()
        if (matches.isEmpty()) return null
        val out = ArrayList<LyricWord>(matches.size)
        for ((index, match) in matches.withIndex()) {
            val from = match.range.last + 1
            val to = matches.getOrNull(index + 1)?.range?.first ?: raw.length
            if (to <= from) continue
            val text = raw.substring(from, to)
            if (text.isBlank()) continue
            // 与行首时间一致：`[offset:]` 表示「歌词整体提前」，字级时间同样要减。
            val time = (toMs(
                match.groupValues[1], match.groupValues[2], match.groupValues[3]
            ) - offset).coerceAtLeast(0L)
            out += LyricWord(text, time)
        }
        return out.ifEmpty { null }
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
