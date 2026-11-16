package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KRC / YRC 解析测试（逐行 + 字级）。
 *
 * 行内字级标签既要**剥离干净**不能漏进正文（旧版曾把 `<00:12.34>退潮` 直接渲染给用户），
 * 也要解析出 [LyricWord] 供逐字高亮消费；拼不回正文时宁可丢逐字也不丢歌词。
 *
 * [wordTagsAreStrippedNotDuplicated] 是回归测试：曾因「行尾剩余文本重复拼接到末字」
 * 导致每行最后一个字渲染成双份。
 */
class KrcParserTest {

    @Test
    fun parsesKrcStyleWordTags() {
        val lyrics = KrcParser.parseText(
            "[0,1000]<0,500,0>你<500,500,0>好",
            LyricSource.SIDECAR_KRC,
        )
        assertTrue(lyrics.synced)
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines[0]
        assertEquals("你好", line.text)
        assertEquals(0L, line.timeMs)   // 时间轴取行首 start，而不是行内标签
        assertEquals(2, line.words?.size)
        assertEquals(0L, line.words!![0].timeMs)
        assertEquals(500L, line.words!![1].timeMs)
    }

    @Test
    fun wordTagsAreStrippedNotDuplicated() {
        val lyrics = KrcParser.parseText(
            "[0,1000]<0,500,0>你<500,500,0>好",
            LyricSource.SIDECAR_KRC,
        )
        val line = lyrics.lines[0]
        assertEquals("你好", line.text)                       // 而不是 "你好好"
        assertEquals(false, line.text.contains("<"))          // 标签不能漏进正文
        assertEquals(false, line.text.contains(">"))
    }

    @Test
    fun parsesYrcStyleWordTags() {
        val lyrics = KrcParser.parseText(
            "[100,2000](100,600,0)Hello(700,600,0)World",
            LyricSource.SIDECAR_KRC,
        )
        val line = lyrics.lines[0]
        assertEquals("HelloWorld", line.text)                 // 圆括号标签同样被剥离
        assertEquals(100L, line.timeMs)
        assertEquals(2, line.words?.size)
        assertEquals(200L, line.words!![0].timeMs)            // lineStart(100) + 相对(100)
        assertEquals(800L, line.words!![1].timeMs)
    }

    @Test
    fun parsesPlainLineWithoutWordTags() {
        val lyrics = KrcParser.parseText("[5000,2000]整行歌词", LyricSource.SIDECAR_KRC)
        assertEquals(1, lyrics.lines.size)
        assertEquals(5_000L, lyrics.lines[0].timeMs)
        assertEquals("整行歌词", lyrics.lines[0].text)
    }

    @Test
    fun appliesOffsetAndSorts() {
        val lyrics = KrcParser.parseText(
            "[offset:100]\n[2000,1000]后\n[1000,1000]前",
            LyricSource.SIDECAR_KRC,
        )
        assertEquals(2, lyrics.lines.size)
        assertEquals("前", lyrics.lines[0].text)
        assertEquals(900L, lyrics.lines[0].timeMs)
        assertEquals(1_900L, lyrics.lines[1].timeMs)
    }

    @Test
    fun appliesOffsetToWordTimesToo() {
        val lyrics = KrcParser.parseText(
            "[offset:100]\n[1000,1000]<0,500,0>你<500,500,0>好",
            LyricSource.SIDECAR_KRC,
        )
        val line = lyrics.lines[0]
        // [offset:] 必须同时平移行时间与字级时间，否则逐字高亮整体错位。
        assertEquals(900L, line.timeMs)
        assertEquals(2, line.words?.size)
        assertEquals(900L, line.words!![0].timeMs)
        assertEquals(1_400L, line.words!![1].timeMs)
    }

    @Test
    fun readsWordLyricsMetadata() {
        val lyrics = KrcParser.parseText(
            "[ti:歌名]\n[ar:歌手]\n[0,500]正文",
            LyricSource.SIDECAR_KRC,
        )
        assertEquals("歌名", lyrics.title)
        assertEquals("歌手", lyrics.artist)
    }

    @Test
    fun malformedKrcBytesDoNotCrash() {
        val lyrics = KrcParser.parseKrc(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), LyricSource.SIDECAR_KRC)
        assertTrue(lyrics.isEmpty)
    }

    @Test
    fun emptyInputYieldsEmptyLyrics() {
        assertTrue(KrcParser.parseText("", LyricSource.SIDECAR_KRC).isEmpty)
    }
}
