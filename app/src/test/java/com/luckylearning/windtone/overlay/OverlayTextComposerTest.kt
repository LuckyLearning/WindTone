package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.lyrics.LyricWord
import com.luckylearning.windtone.lyrics.OverlayContentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮歌词排版组装单元测试（纯 JVM，无需设备）。
 *
 * 负责验证「该画几行、谁是主角、逐字唱到第几个字」——
 * 颜色与字号不在这里测（它们依赖 `resources`，属于服务层的可调外观）。
 */
class OverlayTextComposerTest {

    private fun resolved(
        current: String = "当前句",
        translation: String = "",
        prev: String = "",
        next: String = "",
        idle: Boolean = false,
        words: List<LyricWord> = emptyList(),
    ) = OverlayContentResolver.Resolved(
        current = current,
        translation = translation,
        prev = prev,
        next = next,
        idle = idle,
        words = words,
    )

    private fun compose(
        r: OverlayContentResolver.Resolved,
        multiLine: Boolean = false,
        positionMs: Long = 0L,
        wordByWord: Boolean = false,
    ) = OverlayTextComposer.compose(
        resolved = r,
        multiLine = multiLine,
        positionMs = positionMs,
        wordByWord = wordByWord,
        showTranslation = true,
    )

    // ------------------------------------------------------------ 行数

    @Test
    fun singleLineModeIgnoresContext() {
        val lines = compose(resolved(prev = "上一句", next = "下一句"), multiLine = false)
        assertEquals(1, lines.size)
        assertEquals("当前句", lines[0].text)
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[0].role)
    }

    @Test
    fun multiLineModeEmitsContextOnBothSides() {
        val lines = compose(resolved(prev = "上一句", next = "下一句"), multiLine = true)
        assertEquals(3, lines.size)
        assertEquals("上一句", lines[0].text)
        assertEquals(OverlayTextComposer.Role.CONTEXT, lines[0].role)
        assertEquals("当前句", lines[1].text)
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[1].role)
        assertEquals("下一句", lines[2].text)
    }

    @Test
    fun multiLineModeSkipsBlankContext() {
        val lines = compose(resolved(prev = "", next = ""), multiLine = true)
        assertEquals(1, lines.size)
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[0].role)
    }

    @Test
    fun idleMakesMainLineDimmed() {
        val lines = compose(resolved(idle = true), multiLine = false)
        assertEquals(OverlayTextComposer.Role.CONTEXT, lines[0].role)
    }

    @Test
    fun invisibleResolvedProducesNothing() {
        assertTrue(compose(resolved(current = "")).isEmpty())
    }

    // ------------------------------------------------------------ 双语副行

    @Test
    fun translationSitsRightUnderMainLine() {
        val lines = compose(
            resolved(translation = "译文", next = "下一句"),
            multiLine = true,
        )
        assertEquals(3, lines.size)
        assertEquals("当前句", lines[0].text)
        assertEquals("译文", lines[1].text)
        assertEquals(OverlayTextComposer.Role.TRANSLATION, lines[1].role)
        assertEquals("下一句", lines[2].text)
    }

    @Test
    fun translationShownInSingleLineModeAsWell() {
        // 副行是「同一句」的一部分，不是排版选择：选了 1 行也应当带上它
        val lines = compose(resolved(translation = "译文"), multiLine = false)
        assertEquals(2, lines.size)
        assertEquals(OverlayTextComposer.Role.TRANSLATION, lines[1].role)
    }

    @Test
    fun noTranslationNoExtraLine() {
        val lines = compose(resolved(current = "只有原文"), multiLine = true)
        assertEquals(1, lines.size)
    }

    // ------------------------------------------------------------ 逐字染色

    @Test
    fun sungCharsCountsWordsAlreadyStarted() {
        val words = listOf(
            LyricWord(1_000L, 500L, "你"),
            LyricWord(1_500L, 500L, "好"),
            LyricWord(2_000L, 500L, "吗"),
        )
        val r = resolved(current = "你好吗", words = words)

        assertEquals(0, OverlayTextComposer.sungChars(r, 500L))
        assertEquals(1, OverlayTextComposer.sungChars(r, 1_000L))
        assertEquals(2, OverlayTextComposer.sungChars(r, 1_600L))
        assertEquals(3, OverlayTextComposer.sungChars(r, 9_999L))
    }

    @Test
    fun sungCharsWithoutWordTimelineIsDisabled() {
        assertEquals(-1, OverlayTextComposer.sungChars(resolved(current = "整行"), 10_000L))
    }

    @Test
    fun sungCharsNeverExceedsTextLength() {
        // 解析时词文本可能被 trim 过，拼起来未必等于整行长度——绝不能越界
        val words = listOf(
            LyricWord(1_000L, 500L, "很长的词很长的词"),
            LyricWord(1_500L, 500L, "又一段"),
        )
        val r = resolved(current = "短", words = words)
        assertEquals(1, OverlayTextComposer.sungChars(r, 5_000L))
    }

    @Test
    fun wordByWordDisabledKeepsPlainLine() {
        val words = listOf(LyricWord(1_000L, 500L, "你"))
        val lines = compose(resolved(current = "你", words = words), positionMs = 5_000L, wordByWord = false)
        assertEquals(-1, lines[0].sungChars)
    }

    @Test
    fun wordByWordEnabledMarksSungChars() {
        val words = listOf(
            LyricWord(1_000L, 500L, "你"),
            LyricWord(1_500L, 500L, "好"),
        )
        val lines = compose(
            resolved(current = "你好", words = words),
            positionMs = 1_200L,
            wordByWord = true,
        )
        assertEquals(1, lines[0].sungChars)
    }

    @Test
    fun interludeLineNeverGetsWordHighlight() {
        // 间奏时显示的是上一句，用当前位置去染它毫无意义（会整句点亮）
        val words = listOf(LyricWord(1_000L, 500L, "你"))
        val lines = compose(
            resolved(current = "你", words = words, idle = true),
            positionMs = 9_999L,
            wordByWord = true,
        )
        assertEquals(OverlayTextComposer.Role.CONTEXT, lines[0].role)
        assertEquals(-1, lines[0].sungChars)
    }
}
