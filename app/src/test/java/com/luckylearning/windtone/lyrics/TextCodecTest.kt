package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词文本编码嗅探测试。
 * 中文 .lrc 大量是 GBK/GB18030，识别错就是满屏乱码，因此单独覆盖。
 */
class TextCodecTest {

    @Test
    fun decodesUtf8Chinese() {
        val bytes = "中文歌词测试".toByteArray(Charsets.UTF_8)
        assertEquals("中文歌词测试", TextCodec.decode(bytes))
    }

    @Test
    fun decodesUtf8WithBom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "带 BOM 的歌词".toByteArray(Charsets.UTF_8)
        assertEquals("带 BOM 的歌词", TextCodec.decode(bytes))
    }

    @Test
    fun decodesGb18030Chinese() {
        val bytes = "这是GBK编码的中文歌词".toByteArray(charset("GB18030"))
        assertEquals("这是GBK编码的中文歌词", TextCodec.decode(bytes))
    }

    @Test
    fun decodesAsciiUnchanged() {
        val bytes = "[00:12.00]hello world".toByteArray(Charsets.US_ASCII)
        assertEquals("[00:12.00]hello world", TextCodec.decode(bytes))
    }

    @Test
    fun decodesMixedTimelineContent() {
        val raw = "[00:01.00]如果有一天\n[00:05.00]我们都老了"
        val bytes = raw.toByteArray(charset("GB18030"))
        assertEquals(raw, TextCodec.decode(bytes))
    }

    @Test
    fun emptyBytesReturnEmptyString() {
        assertTrue(TextCodec.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun terminatorLengthMatchesEncoding() {
        assertEquals(2, TextCodec.terminatorLength(1)) // UTF-16
        assertEquals(2, TextCodec.terminatorLength(2)) // UTF-16BE
        assertEquals(1, TextCodec.terminatorLength(0)) // ISO-8859-1
        assertEquals(1, TextCodec.terminatorLength(3)) // UTF-8
    }
}
