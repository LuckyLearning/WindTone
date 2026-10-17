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
 * v1.1.15 起行数由用户调（1..5），上下文改用 `prevLines` / `nextLines` 列表承载。
 */
class OverlayTextComposerTest {

    private fun resolved(
        current: String = "当前句",
        translation: String = "",
        prevLines: List<String> = emptyList(),
        nextLines: List<String> = emptyList(),
        idle: Boolean = false,
    ) = OverlayContentResolver.Resolved(
        current = current,
        translation = translation,
        prevLines = prevLines,
        nextLines = nextLines,
        idle = idle,
    )

    private fun compose(
        r: OverlayContentResolver.Resolved,
        lines: Int = 1,
    ) = OverlayTextComposer.compose(
        resolved = r,
        lineCount = lines,
        showTranslation = true,
    )

    // ------------------------------------------------------------ 行数

    @Test
    fun singleLineModeIgnoresContext() {
        val lines = compose(resolved(prevLines = listOf("上一句"), nextLines = listOf("下一句")), lines = 1)
        assertEquals(1, lines.size)
        assertEquals("当前句", lines[0].text)
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[0].role)
    }

    @Test
    fun threeLinesEmitContextOnBothSides() {
        val lines = compose(
            resolved(prevLines = listOf("上一句"), nextLines = listOf("下一句")),
            lines = 3,
        )
        assertEquals(3, lines.size)
        assertEquals("上一句", lines[0].text)
        assertEquals(OverlayTextComposer.Role.CONTEXT, lines[0].role)
        assertEquals("当前句", lines[1].text)
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[1].role)
        assertEquals("下一句", lines[2].text)
    }

    @Test
    fun twoLinesPutTheExtraOneBelow() {
        // 偶数行时下方多一行：当前行尽量靠中间，而不是贴在最上面
        val lines = compose(
            resolved(prevLines = listOf("上一句"), nextLines = listOf("下一句")),
            lines = 2,
        )
        assertEquals(2, lines.size)
        assertEquals("当前句", lines[0].text)
        assertEquals("下一句", lines[1].text)
    }

    @Test
    fun fiveLinesUseTwoContextLinesEachSide() {
        val lines = compose(
            resolved(
                prevLines = listOf("上二", "上一"),
                nextLines = listOf("下一", "下二"),
            ),
            lines = 5,
        )
        assertEquals(listOf("上二", "上一", "当前句", "下一", "下二"), lines.map { it.text })
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[2].role)
    }

    @Test
    fun fourLinesTakeTheNearestContextOnTop() {
        // 上 1 + 当前 + 下 2：上方的「上二」不该挤进来
        val lines = compose(
            resolved(
                prevLines = listOf("上二", "上一"),
                nextLines = listOf("下一", "下二"),
            ),
            lines = 4,
        )
        assertEquals(listOf("上一", "当前句", "下一", "下二"), lines.map { it.text })
    }

    @Test
    fun missingContextShrinksInsteadOfPadding() {
        val lines = compose(resolved(prevLines = emptyList(), nextLines = emptyList()), lines = 5)
        assertEquals(1, lines.size)
        assertEquals(OverlayTextComposer.Role.CURRENT, lines[0].role)
    }

    @Test
    fun idleMakesMainLineDimmed() {
        val lines = compose(resolved(idle = true), lines = 1)
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
            resolved(translation = "译文", nextLines = listOf("下一句")),
            lines = 3,
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
        val lines = compose(resolved(translation = "译文"), lines = 1)
        assertEquals(2, lines.size)
        assertEquals(OverlayTextComposer.Role.TRANSLATION, lines[1].role)
    }

    @Test
    fun translationDoesNotEatTheContextBudget() {
        // 3 行额度 = 上 1 + 当前 + 下 1，副行另算，不能被它顶掉「下一句」
        val lines = compose(
            resolved(
                translation = "译文",
                prevLines = listOf("上一句"),
                nextLines = listOf("下一句"),
            ),
            lines = 3,
        )
        assertEquals(listOf("上一句", "当前句", "译文", "下一句"), lines.map { it.text })
    }

    @Test
    fun noTranslationNoExtraLine() {
        val lines = compose(resolved(current = "只有原文"), lines = 3)
        assertEquals(1, lines.size)
    }
}
