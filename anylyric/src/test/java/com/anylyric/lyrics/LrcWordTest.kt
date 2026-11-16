package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcWordTest {

    @Test
    fun `parses per-character timings`() {
        val lines = LrcParser.parse("[00:10.00]<00:10.00>我<00:10.30>们<00:10.60>走")
        assertEquals(1, lines.size)
        // text 必须是剥掉字级标签之后的纯文本，否则屏上会把标签也画出来。
        assertEquals("我们走", lines[0].text)
        val words = lines[0].words
        assertNotNull(words)
        assertEquals(3, words!!.size)
        assertEquals(10_000L, words[0].timeMs)
        assertEquals(10_300L, words[1].timeMs)
        assertEquals(10_600L, words[2].timeMs)
        assertEquals("我", words[0].text)
    }

    @Test
    fun `keeps spaces that belong to a word`() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world")
        val words = lines[0].words!!
        // 英文逐字常见「词 + 尾随空格」：空格属于前一个词，丢了会让单词粘在一起。
        assertEquals("Hello ", words[0].text)
        assertEquals("world", words[1].text)
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun `plain lyrics have no word timings`() {
        val lines = LrcParser.parse("[00:01.00]普通歌词")
        assertNull(lines[0].words)
    }

    @Test
    fun `offset tag shifts word timings too`() {
        val lines = LrcParser.parse("[offset:500]\n[00:10.00]<00:10.00>我<00:10.30>们")
        val words = lines[0].words!!
        // 行首时间减 500，字级时间同样要减 —— 否则逐字会比整行慢半拍。
        assertEquals(9_500L, words[0].timeMs)
        assertEquals(9_800L, words[1].timeMs)
        assertEquals(9_500L, lines[0].timeMs)
    }

    @Test
    fun `build writes word timings back`() {
        val lines = LrcParser.parse("[00:10.00]<00:10.00>我<00:10.30>们")
        val built = LrcParser.build(lines)
        assertTrue(built.contains("<00:10.000>"))
        assertTrue(built.contains("<00:10.300>"))
        // 落盘再读回来，逐字必须还在 —— 否则缓存一轮之后就退化成逐行。
        val again = LrcParser.parse(built)
        assertEquals(2, again[0].words!!.size)
    }

    @Test
    fun `build falls back to plain text when words do not match the line`() {
        // words 与 text 不一致（中间被译文合并 / 标题行剥离动过）：宁可丢逐字，不能丢歌词。
        val line = LyricLine(1_000L, "实际文本", words = listOf(LyricWord("别的", 1_000L)))
        assertEquals("[00:01.000]实际文本\n", LrcParser.build(listOf(line)))
    }

    @Test
    fun `multiple line tags do not reuse word timings`() {
        // 一行多个 [..] 是「同一句出现在多处」，字级时间只对应其中一处，硬套会错。
        val lines = LrcParser.parse("[00:01.00][00:05.00]<00:01.00>重<00:01.30>复")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.words == null })
    }
}
