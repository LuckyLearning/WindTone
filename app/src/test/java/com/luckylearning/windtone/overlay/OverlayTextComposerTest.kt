package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.lyrics.OverlayContentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮歌词排版组装单元测试（纯 JVM，无需设备）。
 *
 * 负责验证「该画几行、谁是主角」——
 * 颜色与字号不在这里测（它们依赖 `resources`，属于服务层的可调外观）。
 *
 * v1.1.8 起逐字染色已整体移除，主角与配角的区分**只靠 [OverlayTextComposer.Role]**。
 */
class OverlayTextComposerTest {

    private fun resolved(
        current: String = "当前句",
        translation: String = "",
        prev: String = "",
        next: String = "",
        idle: Boolean = false,
    ) = OverlayContentResolver.Resolved(
        current = current,
        translation = translation,
        prev = prev,
        next = next,
        idle = idle,
    )

    private fun compose(
        r: OverlayContentResolver.Resolved,
        multiLine: Boolean = false,
    ) = OverlayTextComposer.compose(
        resolved = r,
        multiLine = multiLine,
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
}
