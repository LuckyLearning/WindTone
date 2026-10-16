package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮歌词内容解析器单元测试（纯 JVM，无需设备）。
 *
 * 覆盖的是「字幕该显示哪一句」这条最容易出错、也最难在真机上复现的链路：
 * 前奏、间奏空行、首尾边界、歌词偏移、双语副行、以及最后的歌名回落。
 *
 * 这些用例的存在是有代价换来的——「字幕在前奏 / 间奏整体消失」曾是一个
 * 长期存在、自检全绿也发现不了的缺陷，只因为那段逻辑没法测试。
 */
class OverlayContentResolverTest {

    private fun synced(vararg lines: LyricLine) = Lyrics(lines = lines.toList(), synced = true)

    private fun line(sec: Int, text: String, translation: String? = null) =
        LyricLine(timeMs = sec * 1000L, text = text, translation = translation)

    // ------------------------------------------------------------ 基本路径

    @Test
    fun picksCurrentLineAndContext() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"), line(30, "第三句"))
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 21_000L, offsetMs = 0, title = "歌名")
        assertEquals("第二句", r.current)
        assertEquals("第一句", r.prev)
        assertEquals("第三句", r.next)
        assertFalse(r.idle)
        assertTrue(r.visible)
    }

    @Test
    fun firstLineHasNoPrev() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"))
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 10_000L, offsetMs = 0, title = "歌名")
        assertEquals("第一句", r.current)
        assertEquals("", r.prev)
        assertEquals("第二句", r.next)
    }

    @Test
    fun lastLineHasNoNext() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"))
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 99_000L, offsetMs = 0, title = "歌名")
        assertEquals("第二句", r.current)
        assertEquals("第一句", r.prev)
        assertEquals("", r.next)
    }

    // ------------------------------------------------------------ 前奏（回归：曾整窗消失）

    @Test
    fun introFallsBackToTitleAndPreviewsFirstLine() {
        val lyrics = synced(line(30, "第一句"), line(40, "第二句"))
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 5_000L, offsetMs = 0, title = "歌名")
        assertEquals("歌名", r.current)
        assertEquals("第一句", r.next)
        assertEquals("", r.prev)
        assertTrue("前奏不是正在演唱的行，必须标记 idle", r.idle)
        assertTrue("前奏也必须可见，否则字幕会整段消失", r.visible)
    }

    @Test
    fun introBeforeFirstLineWithZeroPosition() {
        val lyrics = synced(line(10, "第一句"))
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 0L, offsetMs = 0, title = "歌名")
        assertEquals("歌名", r.current)
        assertTrue(r.idle)
    }

    @Test
    fun introSkipsLeadingBlankLinesWhenPreviewingNext() {
        val lyrics = synced(
            LyricLine(timeMs = 10_000L, text = ""),
            LyricLine(timeMs = 12_000L, text = "真正第一句"),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 1_000L, offsetMs = 0, title = "歌名")
        assertEquals("歌名", r.current)
        assertEquals("真正第一句", r.next)
    }

    // ------------------------------------------------------------ 间奏空行（回归：曾整窗消失）

    @Test
    fun interludeKeepsPreviousLineDimmed() {
        val lyrics = synced(
            line(10, "上一句"),
            LyricLine(timeMs = 20_000L, text = ""),
            line(30, "下一句"),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 22_000L, offsetMs = 0, title = "歌名")
        assertEquals("上一句", r.current)
        assertEquals("下一句", r.next)
        assertTrue("间奏必须标记 idle，渲染侧据此压暗", r.idle)
        assertTrue(r.visible)
    }

    @Test
    fun interludeNextSkipsConsecutiveBlankLines() {
        val lyrics = synced(
            line(10, "上一句"),
            LyricLine(timeMs = 20_000L, text = ""),
            LyricLine(timeMs = 21_000L, text = ""),
            line(30, "下一句"),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 25_000L, offsetMs = 0, title = "歌名")
        assertEquals("上一句", r.current)
        assertEquals("下一句", r.next)
    }

    @Test
    fun interludePrevSkipsBlankLines() {
        val lyrics = synced(
            line(10, "前一句"),
            LyricLine(timeMs = 15_000L, text = ""),
            line(20, "上一句"),
            LyricLine(timeMs = 25_000L, text = ""),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 26_000L, offsetMs = 0, title = "歌名")
        assertEquals("上一句", r.current)
        assertEquals("前一句", r.prev)
    }

    @Test
    fun allBlankLyricsFallBackToTitle() {
        val lyrics = synced(
            LyricLine(timeMs = 1_000L, text = ""),
            LyricLine(timeMs = 2_000L, text = ""),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 5_000L, offsetMs = 0, title = "歌名")
        assertEquals("歌名", r.current)
        assertTrue(r.idle)
    }

    // ------------------------------------------------------------ 回落歌名

    @Test
    fun noLyricsFallsBackToTitle() {
        val r = OverlayContentResolver.resolve(Lyrics.EMPTY, 0L, 0, "歌名")
        assertEquals("歌名", r.current)
        assertTrue(r.idle)
    }

    @Test
    fun plainLyricsWithoutTimelineFallsBackToTitle() {
        val plain = Lyrics(lines = listOf(LyricLine(-1L, "静止的一行")), synced = false)
        val r = OverlayContentResolver.resolve(plain, 30_000L, 0, "歌名")
        assertEquals("歌名", r.current)
        assertTrue(r.idle)
    }

    @Test
    fun blankTitleAndNoLyricsIsInvisible() {
        val r = OverlayContentResolver.resolve(Lyrics.EMPTY, 0L, 0, "")
        assertFalse("什么都没有时窗口应隐藏", r.visible)
        assertTrue(r.current.isEmpty())
    }

    // ------------------------------------------------------------ 歌词偏移

    @Test
    fun positiveOffsetMovesTimelineForward() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"))
        // 偏移 +5s 表示「歌词提前 5 秒唱」：播放到 15s 时已经对应到 20s 那一句
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 15_000L, offsetMs = 5_000, title = "歌名")
        assertEquals("第二句", r.current)
    }

    @Test
    fun negativeOffsetKeepsEarlierLine() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"))
        // 偏移 -5s 表示「歌词延后 5 秒唱」：播放到 22s 时仍对应 10s 那一句
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 22_000L, offsetMs = -5_000, title = "歌名")
        assertEquals("第一句", r.current)
    }

    // ------------------------------------------------------------ 双语

    @Test
    fun carriesTranslationOfCurrentLine() {
        val lyrics = synced(
            line(10, "原文一", translation = "译文一"),
            line(20, "原文二"),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 10_000L, offsetMs = 0, title = "歌名")
        assertEquals("原文一", r.current)
        assertEquals("译文一", r.translation)
    }

    @Test
    fun translationOfInterludeAnchorIsCarried() {
        val lyrics = synced(
            line(10, "原文一", translation = "译文一"),
            LyricLine(timeMs = 20_000L, text = ""),
        )
        val r = OverlayContentResolver.resolve(lyrics, positionMs = 25_000L, offsetMs = 0, title = "歌名")
        assertEquals("原文一", r.current)
        assertEquals("译文一", r.translation)
    }

    @Test
    fun resolvedIsPositionIndependentWithinSameLine() {
        // v1.1.8 去掉了位置参数：只要还停在同一句歌词上，两次解析结果必须完全相等。
        // 这是「内容不变就不重组」的底线——若结果里混进了播放位置，悬浮窗会每
        // 200ms 重组一次，去重彻底失效。
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"))
        val a = OverlayContentResolver.resolve(lyrics, positionMs = 20_000L, offsetMs = 0, title = "歌名")
        val b = OverlayContentResolver.resolve(lyrics, positionMs = 29_999L, offsetMs = 0, title = "歌名")
        assertEquals(a, b)
    }
}
