package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字歌词解析测试（KRC / YRC / 无逐字标签的整行）。
 *
 * 其中 [lastWordIsNotDuplicated] 是回归测试：
 * 曾因「行尾剩余文本重复拼接到末字」导致每行最后一个字渲染成双份。
 */
class WordLyricsParserTest {

    @Test
    fun parsesKrcStyleWordTags() {
        val lyrics = WordLyricsParser.parseWordText(
            "[0,1000]<0,500,0>你<500,500,0>好",
            LyricSource.SIDECAR_KRC,
        )
        assertTrue(lyrics.synced)
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines[0]
        assertEquals("你好", line.text)
        assertEquals(1_000L, line.lineDurationMs)
        assertEquals(2, line.words.size)
        assertEquals("你", line.words[0].text)
        assertEquals("好", line.words[1].text)
    }

    @Test
    fun lastWordIsNotDuplicated() {
        val lyrics = WordLyricsParser.parseWordText(
            "[0,1000]<0,500,0>你<500,500,0>好",
            LyricSource.SIDECAR_KRC,
        )
        val line = lyrics.lines[0]
        assertEquals("你好", line.text)                       // 而不是 "你好好"
        assertEquals("好", line.words.last().text)            // 而不是 "好好"
    }

    @Test
    fun parsesYrcStyleWordTags() {
        val lyrics = WordLyricsParser.parseWordText(
            "[100,2000](100,600,0)Hello(700,600,0)World",
            LyricSource.SIDECAR_KRC,
        )
        val line = lyrics.lines[0]
        assertEquals("HelloWorld", line.text)
        assertEquals(2, line.words.size)
        assertEquals(100L, line.words[0].startMs)
        assertEquals(600L, line.words[0].durationMs)
        assertEquals("World", line.words[1].text)
    }

    @Test
    fun parsesPlainLineWithoutWordTags() {
        val lyrics = WordLyricsParser.parseWordText("[5000,2000]整行歌词", LyricSource.SIDECAR_KRC)
        assertEquals(1, lyrics.lines.size)
        assertEquals(5_000L, lyrics.lines[0].timeMs)
        assertEquals("整行歌词", lyrics.lines[0].text)
        assertFalse(lyrics.lines[0].hasWords)
    }

    @Test
    fun appliesOffsetAndSorts() {
        val lyrics = WordLyricsParser.parseWordText(
            "[offset:100]\n[2000,1000]后\n[1000,1000]前",
            LyricSource.SIDECAR_KRC,
        )
        assertEquals(2, lyrics.lines.size)
        assertEquals("前", lyrics.lines[0].text)
        assertEquals(900L, lyrics.lines[0].timeMs)
        assertEquals(1_900L, lyrics.lines[1].timeMs)
    }

    @Test
    fun readsWordLyricsMetadata() {
        val lyrics = WordLyricsParser.parseWordText(
            "[ti:歌名]\n[ar:歌手]\n[0,500]正文",
            LyricSource.SIDECAR_KRC,
        )
        assertEquals("歌名", lyrics.title)
        assertEquals("歌手", lyrics.artist)
    }

    @Test
    fun looksWordTimedDetectsFormats() {
        assertTrue(WordLyricsParser.looksWordTimed("[0,1000]<0,500,0>你"))
        assertTrue(WordLyricsParser.looksWordTimed("[0,1000](0,500,0)你"))
        assertFalse(WordLyricsParser.looksWordTimed("[00:12.00]普通 LRC"))
        assertFalse(WordLyricsParser.looksWordTimed("纯文本"))
    }

    @Test
    fun malformedKrcBytesDoNotCrash() {
        val lyrics = WordLyricsParser.parseKrc(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), LyricSource.SIDECAR_KRC)
        assertTrue(lyrics.isEmpty)
    }

    @Test
    fun emptyInputYieldsEmptyLyrics() {
        assertTrue(WordLyricsParser.parseWordText("", LyricSource.SIDECAR_KRC).isEmpty)
    }
}
