package com.luckylearning.windtone.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 逐字歌词解析器，覆盖三种主流格式：
 *
 *  - KRC（酷狗）：整文件按 16 字节密钥 XOR 后接 zlib 压缩；
 *    行格式 `[start,duration]<start,duration,0>字<...>字`
 *  - YRC（网易云音乐）：明文；
 *    行格式 `[start,duration](start,duration,0)字(...)`，也可为 `[start,duration]整行`
 *  - 增强 LRC 的逐字标签 <mm:ss.xx> 由 LrcParser 处理，不在此处
 *
 * 解析结果里 text 已剥离所有时间标签，保留纯文本 + 逐字时间轴。
 */
object WordLyricsParser {

    /** KRC 解密密钥（社区公开） */
    private val KRC_KEY = byteArrayOf(
        0x40, 0x4D, 0x47, 0x61, 0x49, 0x40, 0x54, 0x7D,
        0x5E, 0x75, 0x35, 0x68, 0x48, 0x80.toByte(), 0x2D, 0x5E,
    )

    private val KRC_LINE = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val KRC_WORD = Regex("<(\\d+),(\\d+),\\d+>")
    private val YRC_WORD = Regex("\\((\\d+),(\\d+),\\d+\\)")
    private val META = Regex("\\[(ti|ar|al|by|offset|length|re|ve|au|kana|hash|total):([^]]*)]", RegexOption.IGNORE_CASE)

    /** 判断（已解出明文的）文本是否为逐字歌词格式 */
    fun looksWordTimed(text: String): Boolean =
        text.contains(",") && (KRC_WORD.containsMatchIn(text) || YRC_WORD.containsMatchIn(text)) &&
            Regex("\\[[0-9]+,[0-9]+]").containsMatchIn(text)

    /** 解析 KRC 原始字节（自动解密解压） */
    fun parseKrc(raw: ByteArray, source: LyricSource): Lyrics {
        val text = decodeKrc(raw) ?: return Lyrics.EMPTY.copy(source = source)
        return parseWordText(text, source)
    }

    /** 解析明文逐字歌词（KRC 已解密 / YRC） */
    fun parseWordText(text: String, source: LyricSource): Lyrics {
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
            val lineDur = m.groupValues[2].toLongOrNull() ?: 0L
            val content = m.groupValues[3]

            val krcWords = parseWords(content, KRC_WORD, '<')
            val yrcWords = if (krcWords == null) parseWords(content, YRC_WORD, '(') else null
            when {
                krcWords != null ->
                    lines.add(LyricLine(lineStart, wordsText(krcWords), words = krcWords, lineDurationMs = lineDur))
                yrcWords != null ->
                    lines.add(LyricLine(lineStart, wordsText(yrcWords), words = yrcWords, lineDurationMs = lineDur))
                else -> {
                    // 无逐字标签：整行普通文本
                    val plain = content.replace(KRC_WORD, "").replace(YRC_WORD, "").trim()
                    if (plain.isNotEmpty()) {
                        lines.add(LyricLine(lineStart, plain, lineDurationMs = lineDur))
                    }
                }
            }
        }

        if (lines.isEmpty()) return Lyrics.EMPTY.copy(source = source)
        val adjusted = lines.map { l ->
            val t = l.timeMs - offset
            l.copy(timeMs = if (t < 0) 0L else t)
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

    /** content 中按 wordTag 提取逐字序列；至少要有一个标签才返回 */
    private fun parseWords(content: String, wordTag: Regex, open: Char): List<LyricWord>? {
        if (!wordTag.containsMatchIn(content)) return null
        val words = ArrayList<LyricWord>(16)
        val matches = wordTag.findAll(content).toList()
        for (i in matches.indices) {
            val m = matches[i]
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val dur = m.groupValues[2].toLongOrNull() ?: 0L
            // KRC/YRC 的格式是「标签在前、字在后」：<start,dur,0>字
            // 因此第 i 个字的文本 = 第 i 个标签结束 → 第 i+1 个标签开始（末字到行尾）
            val from = m.range.last + 1
            val to = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            var text = if (from <= to && to <= content.length) content.substring(from, to) else ""
            text = text.trim()
            if (text.isEmpty()) text = " "
            words.add(LyricWord(start, dur, text))
        }
        // 注意：不要把「行尾剩余文本」再拼到最后一个字上——
        // 上一个字的取文范围已经覆盖到行尾，重复拼接会导致末字文本翻倍（历史 bug）
        return words.takeIf { it.isNotEmpty() && it.any { w -> w.text.isNotBlank() } }
    }

    private fun wordsText(words: List<LyricWord>): String =
        words.joinToString("") { it.text }.trim()

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
