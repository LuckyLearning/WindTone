package com.anylyric.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 酷狗 KRC 歌词解析（0.2.27）。
 *
 * 为什么单独一个解析器：KRC 是目前**唯一能匿名拿到的真逐字**格式。
 * 五个在线源里，网易云的 `yrc`/`klyric` 匿名访问是空串，LRCLIB 只有行级，
 * lyrics.ovh 连时间轴都没有 —— 只有酷狗的 `fmt=krc` 给字级时间戳。
 *
 * 容器格式（公开的封装，不含任何权限绕过）：
 * ```
 * "krc1" + XOR(明文, 16 字节循环密钥)  →  zlib 压缩后的文本
 * ```
 * 文本每行：`[行起始ms,行时长ms]<字偏移ms,字时长ms,0>字<...>`
 * 注意**字时间是相对行起始的偏移**，不是绝对时间 —— 忘了加 `lineStart` 会让整首逐字全挤在开头。
 *
 * 任何一步失败都返回 null，调用方回退到普通 `fmt=lrc`，行为与之前完全一致。
 */
object KrcParser {

    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
    )

    private const val MAGIC = "krc1"

    /** `[行起始,行时长]` —— 两个整数，逗号分隔。 */
    private val LINE_TAG = Regex("""^\[(\d+),(\d+)]""")

    /** `<字偏移,字时长,保留>` */
    private val WORD_TAG = Regex("""<(\d+),(\d+),(-?\d+)>""")

    /**
     * 解码 KRC 二进制载荷 → 明文字本。
     *
     * @return null 表示不是 KRC（magic 不符）或解密/解压失败 —— 调用方应回退 LRC。
     */
    fun decodeBytes(raw: ByteArray?): String? {
        if (raw == null || raw.size <= MAGIC.length) return null
        for (i in MAGIC.indices) {
            if (raw[i] != MAGIC[i].code.toByte()) return null
        }
        val body = ByteArray(raw.size - MAGIC.length)
        for (i in body.indices) {
            body[i] = (raw[i + MAGIC.length].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        val decoded: String? = runCatching {
            val inflater = Inflater()
            inflater.setInput(body)
            val out = ByteArrayOutputStream(body.size * 4)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            // 必须确认流是**完整**解开的：截断的 zlib 会照样吐出前半截明文，
            // 拿它当歌词比没有歌词更糟 —— 整首后半段会凭空消失。
            val complete = inflater.finished()
            inflater.end()
            if (complete) out.toString("UTF-8") else null
        }.getOrNull()
        return decoded?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析 KRC 文本 → 行表。
     *
     * 元信息行（`[ar:]` `[ti:]` `[language:…]`）的时间标签不是两个整数，一并跳过。
     * 解析出不到 2 行时返回空表（单行多半是「纯音乐，请欣赏」这类提示）。
     */
    fun parse(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<LyricLine>(64)
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("[")) continue
            val match = LINE_TAG.find(line) ?: continue
            val start = match.groupValues[1].toLongOrNull() ?: continue
            val body = line.substring(match.range.last + 1)
            if (body.isBlank()) continue
            val words = parseWords(body, start)
            val plain = if (words == null) WORD_TAG.replace(body, "").trim() else
                words.joinToString("") { it.text }.trim()
            if (plain.isEmpty()) continue
            out += LyricLine(start, plain, words = words)
        }
        return if (out.size >= 2) out else emptyList()
    }

    /**
     * 一行内的字级时间轴。字时间是**相对行起始的偏移**，这里就加上 [lineStart]。
     *
     * 一个字都解不出来时返回 null，调用方按纯文本行处理。
     */
    private fun parseWords(body: String, lineStart: Long): List<LyricWord>? {
        val matches = WORD_TAG.findAll(body).toList()
        if (matches.isEmpty()) return null
        val out = ArrayList<LyricWord>(matches.size)
        for ((index, match) in matches.withIndex()) {
            val from = match.range.last + 1
            val to = matches.getOrNull(index + 1)?.range?.first ?: body.length
            if (to <= from) continue
            val text = body.substring(from, to)
            if (text.isEmpty()) continue
            val offset = match.groupValues[1].toLongOrNull() ?: continue
            out += LyricWord(text, lineStart + offset)
        }
        return out.ifEmpty { null }
    }
}
