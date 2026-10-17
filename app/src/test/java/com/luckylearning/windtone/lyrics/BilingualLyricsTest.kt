package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「双语歌词」显示裁剪的口径（v1.1.18）。
 *
 * 重点不是「过滤掉几行」，而是几个**反直觉的边界**：
 *  - 空行（间奏标记）永远原样保留；
 *  - 两边都不是目标语言时保留主行（宁可显示"不对"的语言，也不让整行消失）；
 *  - 混写行（`Yeah 我是`）算两种语言都匹配 —— 它是用户自己的混写，换掉反而丢信息。
 * 这些边界没有崩溃、没有日志，只有单测能钉住。
 */
class BilingualLyricsTest {

    private fun ly(vararg lines: LyricLine) = Lyrics(lines = lines.toList(), synced = true)

    // ------------------------------------------------ 基本三种模式

    @Test
    fun `both returns input as is`() {
        val lyrics = ly(LyricLine(1000, "Hello", "你好"))
        assertSame(lyrics, BilingualLyrics.apply(lyrics, BilingualMode.BOTH))
    }

    @Test
    fun `chinese only promotes translation when main is foreign`() {
        val out = BilingualLyrics.apply(
            ly(LyricLine(1000, "Hello", "你好"), LyricLine(2000, "世界", "World")),
            BilingualMode.CHINESE_ONLY,
        )
        assertEquals("你好", out.lines[0].text)
        assertNull("被提上来的副行要清干净", out.lines[0].translation)
        assertEquals("主行本来就是中文 → 只去副行", "世界", out.lines[1].text)
        assertNull(out.lines[1].translation)
    }

    @Test
    fun `english only mirrors the chinese case`() {
        val out = BilingualLyrics.apply(
            ly(LyricLine(1000, "你好", "Hello")),
            BilingualMode.ENGLISH_ONLY,
        )
        assertEquals("Hello", out.lines[0].text)
        assertNull(out.lines[0].translation)
    }

    // ------------------------------------------------ 边界

    @Test
    fun `interlude blank lines survive untouched`() {
        val interlude = LyricLine(5000, "")
        val out = BilingualLyrics.apply(
            ly(LyricLine(1000, "Hello", "你好"), interlude),
            BilingualMode.CHINESE_ONLY,
        )
        assertSame("间奏空行必须原样（同一个实例），空档判定靠它", interlude, out.lines[1])
    }

    @Test
    fun `no translation means line is kept as is`() {
        val plain = LyricLine(1000, "Hello")
        val out = BilingualLyrics.apply(ly(plain), BilingualMode.CHINESE_ONLY)
        assertSame("没有副行的单语歌词不该被复制/改动", plain, out.lines[0])
    }

    @Test
    fun `neither side matches target keeps main line`() {
        // 日文主行 + 罗马音副行，用户选「只显示中文」：没有中文可提，
        // 只能留日文主行 —— 整行消失比显示"不对"的语言更糟
        val out = BilingualLyrics.apply(
            ly(LyricLine(1000, "夜に駆ける", "yo ni kakeru")),
            BilingualMode.CHINESE_ONLY,
        )
        assertEquals("夜に駆ける", out.lines[0].text)
        assertNull(out.lines[0].translation)
    }

    @Test
    fun `mixed script line counts as both languages`() {
        val mixed = LyricLine(1000, "Yeah 我是", "Yeah it's me")
        val cn = BilingualLyrics.apply(ly(mixed), BilingualMode.CHINESE_ONLY)
        assertEquals("混写行在「只中文」下留主行", "Yeah 我是", cn.lines[0].text)
        val en = BilingualLyrics.apply(ly(mixed), BilingualMode.ENGLISH_ONLY)
        assertEquals("混写行在「只英文」下也留主行", "Yeah 我是", en.lines[0].text)
    }

    @Test
    fun `blank translation is treated as absent`() {
        val out = BilingualLyrics.apply(
            ly(LyricLine(1000, "Hello", "  ")),
            BilingualMode.CHINESE_ONLY,
        )
        assertEquals("Hello", out.lines[0].text)
    }

    @Test
    fun `empty lyrics pass through`() {
        assertSame(Lyrics.EMPTY, BilingualLyrics.apply(Lyrics.EMPTY, BilingualMode.CHINESE_ONLY))
    }

    // ------------------------------------------------ script 判定

    @Test
    fun `script detection`() {
        assertEquals(LineScript.HAN, BilingualLyrics.scriptOf("你好世界"))
        assertEquals(LineScript.LATIN, BilingualLyrics.scriptOf("Hello World"))
        assertEquals(LineScript.MIXED, BilingualLyrics.scriptOf("Yeah 我是"))
        assertEquals(LineScript.OTHER, BilingualLyrics.scriptOf("夜に駆ける"))
        assertEquals(LineScript.OTHER, BilingualLyrics.scriptOf("사랑"))
        assertEquals(LineScript.BLANK, BilingualLyrics.scriptOf("♪ ♪"))
        assertEquals(LineScript.BLANK, BilingualLyrics.scriptOf("... 123"))
    }

    // ------------------------------------------------ BilingualMode 还原

    @Test
    fun `mode of falls back to BOTH on illegal index`() {
        assertEquals(BilingualMode.BOTH, BilingualMode.of(0))
        assertEquals(BilingualMode.CHINESE_ONLY, BilingualMode.of(1))
        assertEquals(BilingualMode.ENGLISH_ONLY, BilingualMode.of(2))
        assertEquals(BilingualMode.BOTH, BilingualMode.of(-1))
        assertEquals(BilingualMode.BOTH, BilingualMode.of(99))
    }
}
