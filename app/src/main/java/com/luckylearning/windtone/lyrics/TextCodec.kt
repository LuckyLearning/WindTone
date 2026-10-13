package com.luckylearning.windtone.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码识别。
 * 中文歌词文件历史上大量使用 GBK/GB18030，且很多 MP3 的 ID3 帧标称 ISO-8859-1 实际写的是 GBK，
 * 因此不能直接按标称编码解码，需要按「BOM -> UTF-8 严格校验 -> GB18030 严格校验 -> 兜底」的顺序嗅探。
 */
object TextCodec {

    val UTF8: Charset = Charsets.UTF_8
    val UTF16LE: Charset = Charsets.UTF_16LE
    val UTF16BE: Charset = Charsets.UTF_16BE
    val LATIN1: Charset = Charsets.ISO_8859_1
    val GB18030: Charset? = runCatching { Charset.forName("GB18030") }.getOrNull()

    /** 自动识别并解码整段字节 */
    fun decode(bytes: ByteArray): String {
        val (offset, charset) = detectBom(bytes)
        if (charset != null) {
            return safeDecode(bytes, offset, bytes.size - offset, charset) ?: ""
        }
        strictDecode(bytes, 0, bytes.size, UTF8)?.let { return it }
        GB18030?.let { gb -> strictDecode(bytes, 0, bytes.size, gb)?.let { return it } }
        // 兜底：宽容 UTF-8
        return safeDecode(bytes, 0, bytes.size, UTF8) ?: String(bytes, LATIN1)
    }

    /** 按 ID3 帧声明的编码字节解码（0=ISO-8859-1, 1=UTF-16+BOM, 2=UTF-16BE, 3=UTF-8） */
    fun decodeId3(encoding: Int, bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when (encoding) {
            0 -> {
                // 标称 Latin-1，实际可能是 UTF-8 / GBK（中文 tagger 常见）
                if (bytes.all { (it.toInt() and 0xFF) < 0x80 }) {
                    String(bytes, LATIN1)
                } else {
                    strictDecode(bytes, 0, bytes.size, UTF8)
                        ?: GB18030?.let { strictDecode(bytes, 0, bytes.size, it) }
                        ?: safeDecode(bytes, 0, bytes.size, UTF8)
                        ?: String(bytes, LATIN1)
                }
            }
            1 -> when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    safeDecode(bytes, 2, bytes.size - 2, UTF16LE) ?: ""
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    safeDecode(bytes, 2, bytes.size - 2, UTF16BE) ?: ""
                else -> safeDecode(bytes, 0, bytes.size, UTF16LE) ?: ""
            }
            2 -> safeDecode(bytes, 0, bytes.size, UTF16BE) ?: ""
            3 -> safeDecode(bytes, 0, bytes.size, UTF8) ?: ""
            else -> safeDecode(bytes, 0, bytes.size, UTF8) ?: String(bytes, LATIN1)
        }
    }

    /** 某个编码下「字符串终止符」的字节长度（用于解析 frame 中的 description 字段） */
    fun terminatorLength(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun detectBom(bytes: ByteArray): Pair<Int, Charset?> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return 3 to UTF8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            // UTF-16LE 的 BOM 也可能是 UTF-32LE 的前缀，此处按 UTF-16 处理即可
            return 2 to UTF16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return 2 to UTF16BE
        }
        return 0 to null
    }

    private fun strictDecode(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String? {
        if (length <= 0) return ""
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun safeDecode(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String? {
        if (length <= 0) return ""
        return try {
            String(bytes, offset, length, charset)
        } catch (e: Exception) {
            null
        }
    }
}
