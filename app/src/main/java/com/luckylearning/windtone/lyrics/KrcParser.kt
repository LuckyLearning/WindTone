package com.luckylearning.windtone.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * KRC / YRC 歌词解析器（**逐行**精度）。
 *
 *  - KRC（酷狗）：整文件按 16 字节密钥 XOR 后接 zlib 压缩；
 *    行格式 `[start,duration]<start,duration,0>字<...>字`
 *  - YRC（网易云音乐）：明文；
 *    行格式 `[start,duration](start,duration,0)字(...)`，也可为 `[start,duration]整行`
 *
 * 字级时间轴（逐字歌词）：KRC 的 `<start,dur,0>字` 与 YRC 的 `(start,dur,0)字`
 * 都会解析成 [LyricWord]。注意 KRC / YRC 的字时间是**相对行起始的偏移**，
 * 必须加 `lineStart` 才是绝对时间；`duration` 不用（切段只看下一个字的起点）。
 * 若字级拼不回正文，宁可丢逐字也不丢歌词（退回纯文本行）。
 */
object KrcParser {

    /** KRC 解密密钥（社区公开） */
    private val KRC_KEY = byteArrayOf(
        0x40, 0x4D, 0x47, 0x61, 0x49, 0x40, 0x54, 0x7D,
        0x5E, 0x75, 0x35, 0x68, 0x48, 0x80.toByte(), 0x2D, 0x5E,
    )

    /** 行头：`[行起点,行时长]正文`（行时长已不再使用，能匹配上即可） */
    private val KRC_LINE = Regex("^\\[(\\d+),(\\d+)](.*)$")

    /** 行内的字级标签：KRC 用尖括号、YRC 用圆括号（第 1 捕获组 = 相对行起始的毫秒） */
    private val KRC_WORD = Regex("<(\\d+),(\\d+),\\d+>")
    private val YRC_WORD = Regex("\\((\\d+),(\\d+),\\d+\\)")

    private val META = Regex(
        "\\[(ti|ar|al|by|offset|length|re|ve|au|kana|hash|total):([^]]*)]",
        RegexOption.IGNORE_CASE,
    )

    /** 解析 KRC 原始字节（自动解密解压） */
    fun parseKrc(raw: ByteArray, source: LyricSource): Lyrics {
        val text = decodeKrc(raw) ?: return Lyrics.EMPTY.copy(source = source)
        return parseText(text, source)
    }

    /** 解析明文（KRC 已解密 / YRC） */
    fun parseText(text: String, source: LyricSource): Lyrics {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offset = 0L
        val lines = ArrayList<LyricLine>(128)

        for (line0 in text.split('\n')) {
            val line = line0.trim('\r', '\uFEFF').trim()
            if (line.isEmpty()) continue

            META.findAll(line).forEach { m ->
                when (m.groupValues[1].lowercase()) {
                    "ti" -> if (title.isNullOrBlank()) title = m.groupValues[2].trim()
                    "ar" -> if (artist.isNullOrBlank()) artist = m.groupValues[2].trim()
                    "al" -> if (album.isNullOrBlank()) album = m.groupValues[2].trim()
                    "offset" -> offset = m.groupValues[2].trim().toLongOrNull() ?: 0L
                }
            }
            val body = META.replace(line, "").trim()
            if (body.isEmpty()) continue

            val m = KRC_LINE.find(body) ?: continue
            val lineStart = m.groupValues[1].toLongOrNull() ?: continue

            val wordBody = m.groupValues[3]
            // 字级：KRC 尖括号 / YRC 圆括号，时间相对行起始 —— 加 lineStart 才是绝对时间
            val words = parseWords(wordBody, lineStart)
            val plain = wordBody
                .replace(KRC_WORD, "")
                .replace(YRC_WORD, "")
                .trim()
            if (plain.isNotEmpty()) {
                // 拼不回正文就丢逐字（译文 / 元数据动过的行会走这里）
                val safe = words?.takeIf { it.joinToString("") { w -> w.text } == plain }
                lines.add(LyricLine(lineStart, plain, words = safe))
            }
        }

        if (lines.isEmpty()) return Lyrics.EMPTY.copy(source = source)
        val adjusted = lines.map { l ->
            val t = l.timeMs - offset
            // 行时间与字级时间必须同一口径：KRC 的 [offset:] 对两者都生效。
            // 只平移行时间会让逐字高亮整体错位（尤其 offset 为负时）。
            val shiftedWords = l.words?.map { w ->
                val wt = w.timeMs - offset
                w.copy(timeMs = if (wt < 0L) 0L else wt)
            }
            l.copy(timeMs = if (t < 0) 0L else t, words = shiftedWords)
        }.sortedBy { it.timeMs }
        return Lyrics(
            lines = adjusted,
            synced = true,
            title = title,
            artist = artist,
            album = album,
            offsetMs = offset.toInt(),
            source = source,
        )
    }

    /**
     * 解析 KRC / YRC 的行内字级标签。字时间 = `lineStart + 标签时间`（相对偏移）。
     * 字的文本 = 本标签之后到下一标签之前的全部字符；解不出返回 null。
     */
    private fun parseWords(body: String, lineStart: Long): List<LyricWord>? {
        // 两种括号按正文里出现的顺序统一收集，避免 KRC/YRC 混排时错位
        val matches = (KRC_WORD.findAll(body).toList() + YRC_WORD.findAll(body).toList())
            .sortedBy { it.range.first }
        if (matches.isEmpty()) return null
        val out = ArrayList<LyricWord>(matches.size)
        for ((index, match) in matches.withIndex()) {
            val from = match.range.last + 1
            val to = matches.getOrNull(index + 1)?.range?.first ?: body.length
            if (to <= from) continue
            val text = body.substring(from, to)
            if (text.isBlank()) continue
            val relative = match.groupValues[1].toLongOrNull() ?: continue
            out += LyricWord(text, lineStart + relative)
        }
        return out.ifEmpty { null }
    }

    /** KRC：XOR 解密 + zlib 解压 */
    private fun decodeKrc(raw: ByteArray): String? {
        if (raw.size < 8) return null
        val xored = ByteArray(raw.size)
        for (i in raw.indices) xored[i] = (raw[i].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
        return try {
            val inflater = Inflater()
            inflater.setInput(xored)
            val out = ByteArrayOutputStream(raw.size * 4)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            inflater.end()
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: DataFormatException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
