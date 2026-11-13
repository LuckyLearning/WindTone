package com.anylyric.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * 在线歌词响应解析。
 *
 * 这几个函数把「网络返回什么」与「我们怎么取字段」隔离开：线上接口偶尔改字段名或
 * 返回空结构时，这里能第一时间看出是哪一层退化了，而不是笼统地报「在线取词失败」。
 */
class OnlineLyricsParserTest {

    @Test
    fun `kugou song extracts hash and duration`() {
        val json = """{"data":{"info":[{"hash":"ABC123","duration":240}]}}"""
        assertEquals("ABC123" to 240L, OnlineLyricsParser.kugouSong(json))
    }

    @Test
    fun `kugou song skips entries without hash`() {
        val json = """{"data":{"info":[{"duration":240},{"hash":"OK","duration":200}]}}"""
        assertEquals("OK" to 200L, OnlineLyricsParser.kugouSong(json))
    }

    @Test
    fun `kugou candidate extracts id and accesskey`() {
        val json = """{"candidates":[{"id":"123","accesskey":"KEY"}]}"""
        assertEquals("123" to "KEY", OnlineLyricsParser.kugouCandidate(json))
    }

    @Test
    fun `kugou content decodes base64 lrc`() {
        val lrc = "[00:01.00]第一句"
        val encoded = Base64.getEncoder().encodeToString(lrc.toByteArray(Charsets.UTF_8))
        val json = """{"content":"$encoded"}"""
        assertEquals(lrc, OnlineLyricsParser.kugouContent(json))
    }

    @Test
    fun `netease song id is extracted`() {
        val json = """{"result":{"songs":[{"id":456,"name":"x"}]}}"""
        assertEquals("456", OnlineLyricsParser.neteaseSongId(json))
    }

    @Test
    fun `netease lyric is extracted`() {
        val json = """{"lrc":{"lyric":"[00:01.00]x"}}"""
        assertEquals("[00:01.00]x", OnlineLyricsParser.neteaseLyric(json))
    }

    @Test
    fun `malformed responses return null`() {
        assertNull(OnlineLyricsParser.kugouSong("not json"))
        assertNull(OnlineLyricsParser.kugouCandidate("""{"candidates":[]}"""))
        assertNull(OnlineLyricsParser.kugouContent("""{"content":""}"""))
        assertNull(OnlineLyricsParser.neteaseSongId("""{"result":{}}"""))
        assertNull(OnlineLyricsParser.neteaseLyric("""{}"""))
    }
}
