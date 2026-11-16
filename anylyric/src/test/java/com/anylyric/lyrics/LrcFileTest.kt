package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcFileTest {

    @Test
    fun `decodes plain utf8`() {
        val text = "[00:01.00]稻香"
        assertEquals(text, LrcFile.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `strips the utf8 bom`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val payload = "[00:01.00]x".toByteArray(Charsets.UTF_8)
        assertEquals("[00:01.00]x", LrcFile.decode(bom + payload))
    }

    @Test
    fun `falls back to gb18030 when the bytes are not valid utf8`() {
        // 车机上 U 盘拷来的 lrc 大量是 GBK，按 UTF-8 读会得到乱码并解析出零行。
        val text = "[00:01.00]稻香 - 周杰伦"
        val gbk = text.toByteArray(charset("GB18030"))
        assertEquals(text, LrcFile.decode(gbk))
    }

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", LrcFile.decode(ByteArray(0)))
    }

    @Test
    fun `display name strips path and extension`() {
        assertEquals("稻香", LrcFile.displayName("/sdcard/Music/稻香.lrc"))
        assertEquals("a", LrcFile.displayName("a.lrc"))
        assertEquals("noext", LrcFile.displayName("noext"))
        assertEquals("", LrcFile.displayName(null))
    }
}
