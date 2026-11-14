package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机直连通道的整段歌词解析。
 *
 * 这条通道是「通知使用权拿不到」时唯一的出路（车厂拦授权页），所以它的判定必须
 * 保守：宁可空着（屏上显示「暂无歌词」），也不能把一句提示语当成歌词滚起来。
 */
class ExternalLyricsTest {

    @Test
    fun timedLrcKeepsTimeline() {
        val lines = ExternalLyrics.parse(
            """
            [00:00.00]第一句
            [00:03.50]第二句
            [00:07.00]第三句
            """.trimIndent()
        )
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(3_500L, lines[1].timeMs)
        assertEquals("第三句", lines[2].text)
    }

    /** 纯文本多行 → 逐行流：没有时间轴，行推进靠事件而不是时钟。 */
    @Test
    fun plainMultiLineBecomesUntimedStream() {
        val lines = ExternalLyrics.parse("第一句\n第二句\n\n第三句")
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.timeMs == null })
        assertEquals("第一句", lines[0].text)
        assertEquals("第三句", lines[2].text)
    }

    /** 单行文本不当歌词：多半是「暂无歌词」这类占位，滚起来比空着更糟。 */
    @Test
    fun singleLineIsNotLyrics() {
        assertTrue(ExternalLyrics.parse("暂无歌词").isEmpty())
    }

    @Test
    fun blankInputIsEmpty() {
        assertTrue(ExternalLyrics.parse(null).isEmpty())
        assertTrue(ExternalLyrics.parse("   \n\n ").isEmpty())
    }

    /** 只有头部标签（`[ti:]` 等）的文本解析不出正文时，继续按纯文本处理。 */
    @Test
    fun headerOnlyTagsFallBackToPlainText() {
        val lines = ExternalLyrics.parse("[ti:歌名]\n[ar:歌手]")
        assertEquals(2, lines.size)
        assertNull(lines[0].timeMs)
    }

    /** 时间轴歌词里夹的空行必须丢掉：空行会让当前行短暂变成空白。 */
    @Test
    fun blankLinesInsideLrcAreDropped() {
        val lines = ExternalLyrics.parse("[00:01.00]A\n\n[00:05.00]B")
        assertEquals(2, lines.size)
        assertEquals("B", lines[1].text)
    }

    /**
     * 键名未知时的兜底识别：宁可漏（判不像），也不能把专辑简介当歌词画到屏上。
     */
    @Test
    fun looksLikeLyricsAcceptsTimedLrc() {
        assertTrue(ExternalLyrics.looksLikeLyrics("[00:01.00]A\n[00:05.00]B"))
    }

    @Test
    fun looksLikeLyricsRequiresEnoughPlainLines() {
        assertFalse(ExternalLyrics.looksLikeLyrics("第一行\n第二行"))
        assertTrue(ExternalLyrics.looksLikeLyrics("一\n二\n三\n四"))
    }

    @Test
    fun looksLikeLyricsRejectsLongParagraph() {
        val paragraph = "这是一段很长的专辑介绍文字，".repeat(6) + "\n第二行"
        assertFalse(ExternalLyrics.looksLikeLyrics(paragraph))
    }
}
