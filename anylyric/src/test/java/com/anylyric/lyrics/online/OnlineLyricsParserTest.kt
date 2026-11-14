package com.anylyric.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * 候选列表：0.2.22 及以前只取第一条，结果经常是翻唱 / 剪辑版。
     * 现在把整页都交给打分器，所以字段必须抠全（歌名、歌手、时长）。
     */
    @Test
    fun `kugou songs returns every candidate with fields`() {
        val json = """{"data":{"info":[
            {"hash":"A","songname":"晴天","singername":"周杰伦","duration":269},
            {"hash":"B","songname":"晴天 (Live)","singername":"某歌手","duration":300}
        ]}}"""
        val songs = OnlineLyricsParser.kugouSongs(json)
        assertEquals(2, songs.size)
        assertEquals("晴天", songs[0].title)
        assertEquals("周杰伦", songs[0].artist)
        assertEquals(269L, songs[0].durationSec)
    }

    @Test
    fun `kugou lyric candidates convert duration from milliseconds`() {
        val json = """{"candidates":[
            {"id":"1","accesskey":"K","song":"稻香","singer":"周杰伦","duration":269000}
        ]}"""
        val list = OnlineLyricsParser.kugouLyricCandidates(json)
        assertEquals(1, list.size)
        assertEquals("K", list[0].accessKey)
        assertEquals(269L, list[0].durationSec)
    }

    @Test
    fun `netease songs join artists and convert duration`() {
        val json = """{"result":{"songs":[
            {"id":1,"name":"稻香","duration":269500,
             "artists":[{"name":"周杰伦"}]},
            {"id":2,"name":"合唱","duration":200000,
             "artists":[{"name":"甲"},{"name":"乙"}]}
        ]}}"""
        val songs = OnlineLyricsParser.neteaseSongs(json)
        assertEquals(2, songs.size)
        assertEquals("周杰伦", songs[0].artist)
        assertEquals(269L, songs[0].durationSec)
        assertEquals("甲/乙", songs[1].artist)
    }

    @Test
    fun `candidate lists are empty for malformed payloads`() {
        assertTrue(OnlineLyricsParser.kugouSongs("not json").isEmpty())
        assertTrue(OnlineLyricsParser.kugouLyricCandidates("""{}""").isEmpty())
        assertTrue(OnlineLyricsParser.neteaseSongs("""{"result":{}}""").isEmpty())
    }
}
