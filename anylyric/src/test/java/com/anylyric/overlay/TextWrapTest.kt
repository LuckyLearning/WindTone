package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折行逻辑测试。用「每字 10px」的假测量器，不依赖 Android 字体。
 */
class TextWrapTest {

    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun emptyInputReturnsEmptyList() {
        assertTrue(TextWrap.wrap("", 100f, 2, measure).isEmpty())
        assertTrue(TextWrap.wrap("   ", 100f, 2, measure).isEmpty())
    }

    @Test
    fun keepsShortLineIntact() {
        assertEquals(listOf("abc"), TextWrap.wrap("abc", 100f, 2, measure))
    }

    @Test
    fun breaksWhenTooWide() {
        // 30px 只装得下 3 个字
        assertEquals(listOf("abc", "def"), TextWrap.wrap("abcdef", 30f, 3, measure))
    }

    @Test
    fun lastLineIsEllipsizedWithinWidth() {
        val lines = TextWrap.wrap("abcdefghi", 30f, 2, measure)
        assertEquals(2, lines.size)
        assertEquals("abc", lines[0])
        assertTrue(lines[1].endsWith("…"))
        assertTrue(measure(lines[1]) <= 30f)
    }

    @Test
    fun singleLineModeEllipsizes() {
        val lines = TextWrap.wrap("abcdef", 30f, 1, measure)
        assertEquals(listOf("ab…"), lines)
    }

    @Test
    fun prefersBreakingAfterSpace() {
        // 60px 装得下 6 个字符，第 7 个超宽；应在空格后断开而不是硬切
        val lines = TextWrap.wrap("ab cdef", 60f, 3, measure)
        assertEquals("ab", lines[0])
        assertEquals("cdef", lines[1])
    }

    /** 单字都比可用宽度还宽时不能死循环，且要老实收尾成省略号。 */
    @Test
    fun narrowWidthStillTerminates() {
        val lines = TextWrap.wrap("abcdef", 5f, 3, measure)
        assertEquals(3, lines.size)
        assertEquals(listOf("a", "b", "…"), lines)
    }

    @Test
    fun ellipsizeFitsWithinWidth() {
        val out = TextWrap.ellipsize("abcdef", 30f, measure)
        assertTrue(measure(out) <= 30f)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun ellipsizeLeavesFittingTextAlone() {
        assertEquals("ab", TextWrap.ellipsize("ab", 30f, measure))
    }
}
