package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesBasicLinesInOrder() {
        val lines = LrcParser.parse("[00:01.00]第一行\n[00:03.50]第二行")
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        assertEquals(3_500L, lines[1].timeMs)
    }

    @Test
    fun expandsMultipleTimeTagsOnOneLine() {
        val lines = LrcParser.parse("[00:01.00][00:05.00]副歌")
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals(5_000L, lines[1].timeMs)
        assertEquals("副歌", lines[0].text)
        assertEquals("副歌", lines[1].text)
    }

    @Test
    fun appliesPositiveOffset() {
        val lines = LrcParser.parse("[offset:+500]\n[00:02.00]A")
        assertEquals(1, lines.size)
        assertEquals(1_500L, lines[0].timeMs)
    }

    @Test
    fun appliesNegativeOffset() {
        val lines = LrcParser.parse("[offset:-300]\n[00:02.00]A")
        assertEquals(2_300L, lines[0].timeMs)
    }

    @Test
    fun stripsWordLevelTags() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>你<00:01.50>好")
        assertEquals(1, lines.size)
        assertEquals("你好", lines[0].text)
    }

    @Test
    fun dropsUntimedAndBlankLines() {
        val lines = LrcParser.parse("[ti:标题]\n[ar:歌手]\n\n[00:01.00]A\n[00:02.00]   ")
        assertEquals(1, lines.size)
        assertEquals("A", lines[0].text)
    }

    @Test
    fun sortsOutOfOrderLines() {
        val lines = LrcParser.parse("[00:05.00]B\n[00:01.00]A")
        assertEquals("A", lines[0].text)
        assertEquals("B", lines[1].text)
    }

    @Test
    fun handlesFractionDigits() {
        // 1 秒 + 0.5 秒 = 1500ms；三种小数位数应得到同一结果
        assertEquals(1_500L, LrcParser.parse("[00:01.5]A")[0].timeMs)
        assertEquals(1_500L, LrcParser.parse("[00:01.50]A")[0].timeMs)
        assertEquals(1_500L, LrcParser.parse("[00:01.500]A")[0].timeMs)
    }

    @Test
    fun detectsLrcShape() {
        assertTrue(LrcParser.looksLikeLrc("[00:12.34]歌词"))
        assertFalse(LrcParser.looksLikeLrc("普通的一段文字"))
        assertFalse(LrcParser.looksLikeLrc(null))
    }

    @Test
    fun returnsEmptyForUnusableInput() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("没有时间轴的纯文本").isEmpty())
    }
}
