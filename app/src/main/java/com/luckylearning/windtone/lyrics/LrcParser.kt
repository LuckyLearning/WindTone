package com.luckylearning.windtone.lyrics

/**
 * LRC 歌词解析器。
 * 支持：
 *  - 标准时间轴 [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] / [mm:ss:xx]
 *  - 一行多个时间标签 [00:12.00][01:20.00]歌词
 *  - 元信息 [ti:] [ar:] [al:] [by:] [offset:] [length:]
 *  - 增强型 LRC 的字级标签 <mm:ss.xx>：解析成 [LyricWord]（逐字歌词）
 *  - 双语歌词：同一时间戳（±50ms）连续两行，第二行并入第一行的翻译副行
 *  - 无时间轴的纯文本歌词（返回 synced = false）
 *
 * 字级解析的三条坑规则（参照 anylyric 0.2.28）：
 *  ① 只有一行**单个** `[..]` 时间标签时才认字级 —— 一行多个 `[..]` 是同一句重复出现，
 *     字级时间只对应其中一处，硬套会让另一处错位；
 *  ② `[offset:]` 对字级同样生效（歌词整体提前，字级时间一并减）；
 *  ③ 字级必须能拼回纯文本（`words.joinToString == text`），否则宁可丢逐字也不丢歌词 ——
 *     被双语合并 / 空行过滤动过的行不满足这一条，此时退回纯文本行。
 *
 * 无论开关开不开，解析层都产出字级轴（有就存着）：开关是消费端的判据，不在这里耦合。
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META_TAG = Regex("\\[(ti|ar|al|by|offset|length|re|ve|au|kana):([^]]*)]", RegexOption.IGNORE_CASE)

    /** 增强 LRC 的字级标签（`<mm:ss.xx>`）：既用于解析字轴，也用于从正文剥离 */
    private val WORD_TAG = Regex("<(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val ANY_BRACKET = Regex("\\[[^]]*]")

    fun parse(raw: String, source: LyricSource): Lyrics {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0

        data class Stamp(val ms: Long, val text: String, val words: List<LyricWord>? = null)

        val timestamped = ArrayList<Stamp>(256)
        val plain = ArrayList<String>(256)

        for (line0 in raw.split('\n')) {
            val line = line0.trim('\r', '\uFEFF')
            if (line.isBlank()) {
                if (plain.isNotEmpty()) plain.add("")
                continue
            }

            // 元信息
            for (m in META_TAG.findAll(line)) {
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].trim()
                when (key) {
                    "ti" -> if (title.isNullOrBlank()) title = value
                    "ar" -> if (artist.isNullOrBlank()) artist = value
                    "al" -> if (album.isNullOrBlank()) album = value
                    "offset" -> offsetMs = value.trim().toIntOrNull() ?: 0
                }
            }

            val times = TIME_TAG.findAll(line).toList()
            if (times.isEmpty()) {
                val stripped = META_TAG.replace(line, "").trim()
                if (stripped.isNotEmpty()) plain.add(stripped)
                continue
            }

            var body = line
            for (t in times) body = body.replace(t.value, "")

            // 字级轴：只有单个行首时间标签才认（坑规则①），且 [offset:] 对字级同样生效（②）。
            // 解析出的 words 用原始 body（含字级标签）算，text 用剥离后的纯文本。
            val words = if (times.size == 1) parseWords(body, offsetMs) else null
            // 坑规则③：字级拼不回纯文本时宁可丢逐字（此处先按 text 校验一遍，
            // 双语合并那一层的校验在下面构造 LyricLine 时再做）。
            val usable = words?.takeIf { it.joinToString("") { w -> w.text } == body.let { b ->
                WORD_TAG.replace(b, "").let { s -> ANY_BRACKET.replace(s, "").trim() }
            } }

            var text = WORD_TAG.replace(body, "")
            text = ANY_BRACKET.replace(text, "")
            text = text.trim()

            for (t in times) {
                val ms = toMillis(t.groupValues[1], t.groupValues[2], t.groupValues[3]) ?: continue
                timestamped.add(Stamp(ms, text, usable))
            }
        }

        val synced = timestamped.isNotEmpty()
        val lines: List<LyricLine>
        if (synced) {
            val adjusted = timestamped.map { s ->
                val t = s.ms - offsetMs
                Stamp(if (t < 0) 0L else t, s.text, s.words)
            }.sortedBy { it.ms }

            // 双语合并（v1.2.1 修正）：把「同一句的译文」认成副行，而不是多出一行歌词。
            //
            // 原判据是 `next.ms - cur.ms in 1..50`，**恰好排除了时间戳完全相同**这一档 ——
            // 而这正是带翻译 LRC 最常见的写法（`[00:12.00]原文` 紧跟 `[00:12.00]译文`）。
            // 结果译文被当成独立歌词行：播放页一行行往下滚，用户看到的就是"译文不跟随 / 错行"。
            //
            // 现在的规则：
            //  1. 同一时间戳（差 0）或相差 ≤50ms 的相邻行，**文本不同**时，后一行作为译文；
            //  2. 因为整份歌词按时间排序，"原文全在前、译文全在后"那种块状写法也会落到相邻位置，
            //     所以不需要额外区分两种排版；
            //  3. 文本完全相同不合并 —— 重复副歌里双写同一句时不该被吃掉。
            val merged = ArrayList<LyricLine>(adjusted.size)
            var i = 0
            while (i < adjusted.size) {
                val cur = adjusted[i]
                if (cur.text.isEmpty()) {
                    merged.add(LyricLine(cur.ms, cur.text, words = cur.words))
                    i += 1
                    continue
                }
                var translation: String? = null
                var j = i + 1
                while (j < adjusted.size) {
                    val cand = adjusted[j]
                    val gap = cand.ms - cur.ms
                    if (gap !in 0..50) break
                    if (cand.text.isEmpty()) break
                    if (translation == null && cand.text != cur.text) {
                        translation = cand.text
                        j += 1
                        continue
                    }
                    break
                }
                // 双语合并动过的行 words 拼不回 text 时，宁可丢逐字也不丢歌词（坑规则③）
                val safeWords = cur.words?.takeIf { it.joinToString("") { w -> w.text } == cur.text }
                merged.add(
                    LyricLine(
                        timeMs = cur.ms,
                        text = cur.text,
                        translation = translation,
                        words = safeWords,
                    ),
                )
                i = j
            }
            lines = merged
        } else {
            lines = plain.map { LyricLine(-1L, it) }
        }

        return Lyrics(
            lines = lines,
            synced = synced,
            title = title,
            artist = artist,
            album = album,
            offsetMs = offsetMs,
            source = if (lines.isEmpty()) LyricSource.NONE else source,
        )
    }

    /** 把纯文本（无时间轴）包装成 Lyrics */
    fun plainText(raw: String, source: LyricSource): Lyrics {
        val lines = raw.split('\n').map { it.trim('\r', '\uFEFF').trim() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
            .map { LyricLine(-1L, it) }
        return Lyrics(
            lines = lines,
            synced = false,
            source = if (lines.isEmpty()) LyricSource.NONE else source,
        )
    }

    /** 判断一段文本是否含时间轴 */
    fun hasTimeline(raw: String): Boolean = TIME_TAG.containsMatchIn(raw)

    /**
     * 解析一行里的 `<mm:ss.xx>字<...>字` 字级时间轴。
     *
     * 每个字的时间 = 该标签时间 - [offsetMs]（与行首 `[..]` 同一口径），
     * 字的文本 = 从本标签之后到下一标签之前的全部字符（含英文尾随空格）。
     * 一个字都解不出时返回 null，交给上层按「伪字级」均分兜底。
     */
    private fun parseWords(body: String, offsetMs: Int): List<LyricWord>? {
        val matches = WORD_TAG.findAll(body).toList()
        if (matches.isEmpty()) return null
        val out = ArrayList<LyricWord>(matches.size)
        for ((index, match) in matches.withIndex()) {
            val from = match.range.last + 1
            val to = matches.getOrNull(index + 1)?.range?.first ?: body.length
            if (to <= from) continue
            val text = body.substring(from, to)
            if (text.isBlank()) continue
            val t = toMillis(
                match.groupValues[1], match.groupValues[2], match.groupValues[3],
            ) ?: continue
            val shifted = t - offsetMs
            out += LyricWord(text, if (shifted < 0L) 0L else shifted)
        }
        return out.ifEmpty { null }
    }

    private fun toMillis(min: String, sec: String, frac: String): Long? {
        val m = min.toLongOrNull() ?: return null
        val s = sec.toLongOrNull() ?: return null
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLongOrNull()?.times(100) ?: 0L
            2 -> frac.toLongOrNull()?.times(10) ?: 0L
            3 -> frac.toLongOrNull() ?: 0L
            else -> frac.substring(0, 3).toLongOrNull() ?: 0L
        }
        return m * 60_000L + s * 1_000L + f
    }
}
