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
 * ⚠️ **v1.1.8 起不再解析字级时间轴** —— 逐字歌词功能已整体移除。这两种格式仍然
 * 完整支持，只是当普通同步歌词用：行内的时间标签一律剥离，文本原样保留，
 * 时间轴取行首的 `start`。**不要再往这里加回字级解析**，除非逐字染色一并恢复
 * （两份实现必须同进同退，否则会重新出现「有数据但没人消费」的死代码）。
 */
object KrcParser {

    /** KRC 解密密钥（社区公开） */
    private val KRC_KEY = byteArrayOf(
        0x40, 0x4D, 0x47, 0x61, 0x49, 0x40, 0x54, 0x7D,
        0x5E, 0x75, 0x35, 0x68, 0x48, 0x80.toByte(), 0x2D, 0x5E,
    )

    /** 行头：`[行起点,行时长]正文`（行时长已不再使用，能匹配上即可） */
    private val KRC_LINE = Regex("^\\[(\\d+),(\\d+)](.*)$")

    /** 行内的字级标签：KRC 用尖括号、YRC 用圆括号 —— 只用于剥离 */
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

            // 剥离行内的全部时间标签，剩下的就是这一行的正文
            val plain = m.groupValues[3]
                .replace(KRC_WORD, "")
                .replace(YRC_WORD, "")
                .trim()
            if (plain.isNotEmpty()) lines.add(LyricLine(lineStart, plain))
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
