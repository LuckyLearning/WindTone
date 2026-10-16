package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRC 歌词解析器单元测试（纯 JVM，无需设备）。
 * 覆盖：时间轴、多标签、元信息、offset、增强标签剥离、双语合并、纯文本回落。
 */
class LrcParserTest {

    @Test
    fun parsesStandardTimeline() {
        val lyrics = LrcParser.parse("[00:12.00]第一行\n[01:05.50]第二行", LyricSource.SIDECAR_LRC)
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(12_000L, lyrics.lines[0].timeMs)
        assertEquals("第一行", lyrics.lines[0].text)
        assertEquals(65_500L, lyrics.lines[1].timeMs)
        assertEquals("第二行", lyrics.lines[1].text)
        assertEquals(LyricSource.SIDECAR_LRC, lyrics.source)
    }

    @Test
    fun parsesMillisecondAndCentisecondFractions() {
        val a = LrcParser.parse("[00:01.5]x", LyricSource.SIDECAR_LRC)
        assertEquals(1_500L, a.lines[0].timeMs) // 1 位 = 百毫秒
        val b = LrcParser.parse("[00:01.25]x", LyricSource.SIDECAR_LRC)
        assertEquals(1_250L, b.lines[0].timeMs) // 2 位 = 十毫秒
        val c = LrcParser.parse("[00:01.125]x", LyricSource.SIDECAR_LRC)
        assertEquals(1_125L, c.lines[0].timeMs) // 3 位 = 毫秒
    }

    @Test
    fun expandsMultipleTimeTagsOnOneLine() {
        val lyrics = LrcParser.parse("[00:10.00][01:20.00]副歌", LyricSource.SIDECAR_LRC)
        assertEquals(2, lyrics.lines.size)
        assertEquals(10_000L, lyrics.lines[0].timeMs)
        assertEquals(80_000L, lyrics.lines[1].timeMs)
        assertEquals("副歌", lyrics.lines[0].text)
        assertEquals("副歌", lyrics.lines[1].text)
    }

    @Test
    fun readsMetadataTags() {
        val lyrics = LrcParser.parse(
            "[ti:七里香]\n[ar:周杰伦]\n[al:七里香]\n[00:01.00]正文",
            LyricSource.SIDECAR_LRC,
        )
        assertEquals("七里香", lyrics.title)
        assertEquals("周杰伦", lyrics.artist)
        assertEquals("七里香", lyrics.album)
        assertEquals(1, lyrics.lines.size)
    }

    @Test
    fun appliesOffsetMetadata() {
        val lyrics = LrcParser.parse("[offset:500]\n[00:12.00]A\n[00:30.00]B", LyricSource.SIDECAR_LRC)
        assertEquals(500, lyrics.offsetMs)
        assertEquals(11_500L, lyrics.lines[0].timeMs)
        assertEquals(29_500L, lyrics.lines[1].timeMs)
    }

    @Test
    fun offsetNeverProducesNegativeTime() {
        val lyrics = LrcParser.parse("[offset:5000]\n[00:01.00]A", LyricSource.SIDECAR_LRC)
        assertEquals(0L, lyrics.lines[0].timeMs)
    }

    @Test
    fun mergesNearbyLineAsTranslation() {
        val lyrics = LrcParser.parse(
            "[00:12.00]I love you\n[00:12.02]我爱你\n[00:20.00]alone",
            LyricSource.SIDECAR_LRC,
        )
        assertEquals(2, lyrics.lines.size)
        assertEquals("I love you", lyrics.lines[0].text)
        assertEquals("我爱你", lyrics.lines[0].translation)
        assertEquals(20_000L, lyrics.lines[1].timeMs)
    }

    @Test
    fun stripsEnhancedWordTagsButKeepsLineTimeline() {
        val lyrics = LrcParser.parse("[00:12.00]<00:12.00>你<00:12.50>好", LyricSource.SIDECAR_LRC)
        assertTrue(lyrics.synced)
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines[0]
        // 标签必须剥离干净，否则会把 "<00:12.00>" 原样显示给用户
        assertEquals("你好", line.text)
        // 时间轴取行首标签，不是行内的字级标签
        assertEquals(12_000L, line.timeMs)
    }

    @Test
    fun stripsEnhancedTagsInUntimedLines() {
        // 增强 LRC 偶尔写成一行里多个字级标签但只有一个行首时间戳；只要行首有
        // 时间轴就不会走纯文本分支，正文仍须干净。
        val lyrics = LrcParser.parse(
            "[00:01.00]<00:01.00>Hello <00:01.40>world",
            LyricSource.SIDECAR_LRC,
        )
        assertEquals(1, lyrics.lines.size)
        assertEquals("Hello world", lyrics.lines[0].text)
        assertEquals(1_000L, lyrics.lines[0].timeMs)
    }

    @Test
    fun plainTextFallsBackWithoutTimeline() {
        val lyrics = LrcParser.parse("第一行歌词\n第二行歌词", LyricSource.SIDECAR_TXT)
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals("第一行歌词", lyrics.lines[0].text)
        assertEquals(LyricSource.SIDECAR_TXT, lyrics.source)
    }

    @Test
    fun hasTimelineDetectsBothForms() {
        assertTrue(LrcParser.hasTimeline("[00:01.00]x"))
        assertTrue(LrcParser.hasTimeline("[1:2.3]x"))
        assertFalse(LrcParser.hasTimeline("[ti:只有元信息]"))
        assertFalse(LrcParser.hasTimeline("纯文本"))
    }

    @Test
    fun emptyInputYieldsEmptyLyrics() {
        val lyrics = LrcParser.parse("", LyricSource.SIDECAR_LRC)
        assertTrue(lyrics.isEmpty)
        assertEquals(LyricSource.NONE, lyrics.source)
    }

    @Test
    fun indexAtFindsCurrentLine() {
        val lyrics = LrcParser.parse("[00:10.00]A\n[00:20.00]B\n[00:30.00]C", LyricSource.SIDECAR_LRC)
        assertEquals(-1, lyrics.indexAt(5_000L))
        assertEquals(0, lyrics.indexAt(10_000L))
        assertEquals(0, lyrics.indexAt(19_999L))
        assertEquals(1, lyrics.indexAt(20_000L))
        assertEquals(2, lyrics.indexAt(999_999L))
    }
}
