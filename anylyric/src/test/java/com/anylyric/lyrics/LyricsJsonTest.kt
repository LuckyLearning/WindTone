package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JSON 歌词解包。
 *
 * 这类输入在真机上很少见但一旦出现就是「播放器有歌词、我们显示暂无」，
 * 所以既要能取出常见结构，也要保证「不是歌词的 JSON」不被当成歌词。
 */
class LyricsJsonTest {

    /** JSON 字符串里不能直接塞真实换行，用单行示例证明结构解包即可。 */
    private val line = "[00:01.00]第一句"

    private val lrc = "$line\n[00:05.00]第二句"

    @Test
    fun `plain lrc is returned unchanged`() {
        assertEquals(lrc, LyricsJson.unwrap(lrc))
    }

    @Test
    fun `lyrics key is extracted`() {
        assertEquals(line, LyricsJson.unwrap("""{"lyrics":"$line"}"""))
    }

    @Test
    fun `nested data lyric is extracted`() {
        assertEquals(line, LyricsJson.unwrap("""{"data":{"lyric":"$line"}}"""))
    }

    @Test
    fun `array wrapped content is extracted`() {
        assertEquals(line, LyricsJson.unwrap("""[{"content":"$line"}]"""))
    }

    @Test
    fun `json without lyric keys falls back to raw`() {
        val raw = """{"foo":"bar"}"""
        assertEquals(raw, LyricsJson.unwrap(raw))
    }

    @Test
    fun `malformed json falls back to raw`() {
        val raw = """{"lyrics": """
        assertEquals(raw, LyricsJson.unwrap(raw))
    }

    @Test
    fun `null and blank stay null`() {
        assertNull(LyricsJson.unwrap(null))
        assertNull(LyricsJson.unwrap("   "))
    }
}
