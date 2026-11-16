package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class KrcParserTest {

    private val key = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
    )

    /** 按 KRC 的封装反向编码，用来验证解码链路（明文 → zlib → XOR → "krc1"）。 */
    private fun encode(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        deflater.end()
        val body = out.toByteArray()
        val masked = ByteArray(body.size) { i ->
            (body[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return "krc1".toByteArray(Charsets.UTF_8) + masked
    }

    @Test
    fun `decodes a full krc payload`() {
        val plain = "[0,7433]<0,2192,0>孤<2192,219,0>勇<2411,159,0>者"
        assertEquals(plain, KrcParser.decodeBytes(encode(plain)))
    }

    @Test
    fun `rejects payload without the magic header`() {
        val broken = encode("whatever").copyOfRange(2, 20)
        assertNull(KrcParser.decodeBytes(broken))
        assertNull(KrcParser.decodeBytes(null))
        assertNull(KrcParser.decodeBytes(ByteArray(0)))
    }

    @Test
    fun `truncated payload does not throw`() {
        // 接口被截断 / 内容不是 zlib：必须安静失败，让调用方回退 lrc。
        val full = encode("[0,1000]<0,100,0>啊")
        assertNull(KrcParser.decodeBytes(full.copyOfRange(0, full.size - 6)))
    }

    @Test
    fun `parses per-character timings relative to the line start`() {
        val text = "[1000,2000]<0,500,0>孤<500,300,0>勇<800,200,0>者\r\n" +
            "[3000,2000]<0,400,0>都<400,300,0>是\r\n"
        val lines = KrcParser.parse(text)
        assertEquals(2, lines.size)

        assertEquals(1000L, lines[0].timeMs)
        assertEquals("孤勇者", lines[0].text)
        val words = lines[0].words
        assertNotNull(words)
        assertEquals(3, words!!.size)
        // 字时间是**相对行起始**的偏移，必须加上行起始。
        assertEquals(1000L, words[0].timeMs)
        assertEquals(1500L, words[1].timeMs)
        assertEquals(1800L, words[2].timeMs)
        assertEquals("勇", words[1].text)

        assertEquals(3000L, lines[1].timeMs)
        assertEquals(3400L, lines[1].words!![1].timeMs)
    }

    @Test
    fun `skips metadata lines`() {
        val text = "\uFEFF[id:\$00000000]\r\n[ar:陈奕迅]\r\n[ti:孤勇者]\r\n" +
            "[total:0]\r\n[offset:0]\r\n" +
            "[0,1000]<0,100,0>甲<100,100,0>乙\r\n" +
            "[2000,1000]<0,100,0>丙<100,100,0>丁\r\n"
        val lines = KrcParser.parse(text)
        assertEquals(2, lines.size)
        assertEquals("甲乙", lines[0].text)
    }

    @Test
    fun `line without word tags still parses as plain text`() {
        val text = "[0,1000]没有字级\r\n[2000,1000]第二句\r\n"
        val lines = KrcParser.parse(text)
        assertEquals(2, lines.size)
        assertNull(lines[0].words)
        assertEquals("没有字级", lines[0].text)
    }

    @Test
    fun `single line is not treated as lyrics`() {
        // 单行多半是「纯音乐，请欣赏」，收下只会让屏上多一句莫名其妙的字。
        assertTrue(KrcParser.parse("[0,1000]<0,100,0>纯音乐").isEmpty())
        assertTrue(KrcParser.parse("").isEmpty())
        assertTrue(KrcParser.parse(null).isEmpty())
    }

    @Test
    fun `words round trip through the lrc writer`() {
        // 取到 KRC 后要落盘成 LRC（保留字级）：写完再读回来，字级不能丢。
        val lines = KrcParser.parse("[1000,2000]<0,500,0>孤<500,300,0>勇\r\n[3000,2000]<0,400,0>都<400,300,0>是\r\n")
        val stored = LrcParser.build(lines)
        val again = LrcParser.parse(stored)
        assertEquals(2, again.size)
        assertEquals(2, again[0].words?.size)
        assertEquals(1500L, again[0].words!![1].timeMs)
    }
}
