package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.lyrics.LyricSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手动搜词结果的解析口径：有时间轴按 LRC，无则纯文本，来源统一标记「手动选择」。 */
class LyricsSearchTest {

    @Test
    fun `带时间轴按 LRC 解析`() {
        val lyrics = LyricsSearch.parse("[00:01.00]第一句\n[00:03.50]第二句")
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals("第一句", lyrics.lines[0].text)
        assertEquals(1_000L, lyrics.lines[0].timeMs)
        assertEquals(LyricSource.MANUAL, lyrics.source)
    }

    @Test
    fun `纯文本歌词按无时间轴处理`() {
        val lyrics = LyricsSearch.parse("只有文字\n没有时间")
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(LyricSource.MANUAL, lyrics.source)
    }

    @Test
    fun `空正文不会被当成有效歌词`() {
        assertTrue(LyricsSearch.parse("   \n").isEmpty)
    }
}
