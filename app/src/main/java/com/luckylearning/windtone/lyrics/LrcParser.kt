package com.luckylearning.windtone.lyrics

/**
 * LRC 歌词解析器。
 * 支持：
 *  - 标准时间轴 [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] / [mm:ss:xx]
 *  - 一行多个时间标签 [00:12.00][01:20.00]歌词
 *  - 元信息 [ti:] [ar:] [al:] [by:] [offset:] [length:]
 *  - 增强型 LRC 的字级标签 <mm:ss.xx>：**只剥离、不解析**（见下）
 *  - 双语歌词：同一时间戳（±50ms）连续两行，第二行并入第一行的翻译副行
 *  - 无时间轴的纯文本歌词（返回 synced = false）
 *
 * ⚠️ **v1.1.8 起不再解析字级时间轴** —— 逐字歌词功能已整体移除。增强型 LRC 仍被
 * 完整支持，只是当普通同步歌词用：行内的 `<mm:ss.xx>` 必须**剥离干净**（漏了的话
 * 歌词正文会直接把时间标签显示给用户），时间轴取行首的 `[mm:ss.xx]`。
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META_TAG = Regex("\\[(ti|ar|al|by|offset|length|re|ve|au|kana):([^]]*)]", RegexOption.IGNORE_CASE)

    /** 增强 LRC 的字级标签：只用于从正文里剥离，不再产出时间轴 */
    private val WORD_TAG = Regex("<(\\d{1,4}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val ANY_BRACKET = Regex("\\[[^]]*]")

    fun parse(raw: String, source: LyricSource): Lyrics {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs = 0

        data class Stamp(val ms: Long, val text: String)

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

            var text = line
            for (t in times) text = text.replace(t.value, "")

            // 增强 LRC 的字级标签：剥离即可，不再解析成时间轴
            text = WORD_TAG.replace(text, "")
            text = ANY_BRACKET.replace(text, "")
            text = text.trim()

            for (t in times) {
                val ms = toMillis(t.groupValues[1], t.groupValues[2], t.groupValues[3]) ?: continue
                timestamped.add(Stamp(ms, text))
            }
        }

        val synced = timestamped.isNotEmpty()
        val lines: List<LyricLine>
        if (synced) {
            val adjusted = timestamped.map { s ->
                val t = s.ms - offsetMs
                Stamp(if (t < 0) 0L else t, s.text)
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
                    merged.add(LyricLine(cur.ms, cur.text))
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
                merged.add(LyricLine(timeMs = cur.ms, text = cur.text, translation = translation))
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
