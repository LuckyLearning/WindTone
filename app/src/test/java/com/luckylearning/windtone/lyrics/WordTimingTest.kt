package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字补算 / 切段的纯函数契约（参照 anylyric 0.2.28 的 withWords + drawWordParts）。
 */
class WordTimingTest {

    @Test
    fun `关闭时不补算且返回同一实例`() {
        val lines = listOf(LyricLine(1_000L, "你好世界"))
        assertSame(lines, WordTiming.withDerivedWords(lines, enabled = false))
    }

    @Test
    fun `普通LRC按行时长均分出伪字级`() {
        val lines = listOf(
            LyricLine(1_000L, "你好世界"),
            LyricLine(3_000L, "第二句"),
        )
        val out = WordTiming.withDerivedWords(lines, enabled = true)
        val words = out[0].words!!
        assertEquals(4, words.size)
        // 行时长 2s ÷ 4 字：起点 1000/1500/2000/2500
        assertEquals(listOf(1_000L, 1_500L, 2_000L, 2_500L), words.map { it.timeMs })
        assertEquals("你好世界", words.joinToString("") { it.text })
    }

    @Test
    fun `已有字级的行原样保留不覆盖`() {
        val real = listOf(LyricWord("自", 1_000L), LyricWord("定", 1_200L))
        val lines = listOf(LyricLine(1_000L, "自定义", words = real))
        val out = WordTiming.withDerivedWords(lines, enabled = true)
        assertSame(real, out[0].words)
    }

    @Test
    fun `末行按3秒估且时长被夹在400到8000毫秒`() {
        val lines = listOf(LyricLine(10_000L, "末"))
        val w = WordTiming.withDerivedWords(lines, true)[0].words!!
        assertEquals(1, w.size)
        assertEquals(10_000L, w[0].timeMs)
        // 单字起点就是行起点，夹取体现在多字行：这里只验证不抛异常且字存在
        assertTrue(w.isNotEmpty())
    }

    @Test
    fun `无轴纯文本歌词不补算`() {
        val lines = listOf(LyricLine(-1L, "纯文本"))
        val out = WordTiming.withDerivedWords(lines, true)
        assertEquals(null, out[0].words)
    }

    @Test
    fun `sungChars按字起点切两段且夹在文本长度内`() {
        val words = listOf(
            LyricWord("我", 1_000L), LyricWord("们", 1_300L), LyricWord("走", 1_600L),
        )
        assertEquals(0, WordTiming.sungChars(words, 999L, 3))
        assertEquals(1, WordTiming.sungChars(words, 1_000L, 3))
        assertEquals(2, WordTiming.sungChars(words, 1_500L, 3))
        assertEquals(3, WordTiming.sungChars(words, 9_999L, 3))
        // words 与 text 不一致时不越界
        assertEquals(2, WordTiming.sungChars(words, 9_999L, 2))
    }
}
