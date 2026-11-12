package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行预算的守门测试。
 *
 * 守的是这一条：**行数调到 3、4 时，屏幕上必须真的有 3、4 行字**。
 * 旧实现只给一条副行文本，于是无论怎么调都只有两行 —— 用户反馈的正是这个。
 */
class LinePlanTest {

    /** 假折行器：超过 `limit` 个字就按字数切开，够用来验预算分配。 */
    private fun wrap(text: String, lines: Int, chunk: Int = 5): List<String> {
        if (lines <= 0) return emptyList()
        val parts = text.chunked(chunk)
        return if (parts.size <= lines) parts else parts.take(lines - 1) + parts.drop(lines - 1).joinToString("")
    }

    private fun build(primary: String, context: List<String>, maxLines: Int, chunk: Int = 5) =
        LinePlan.build(primary, context, maxLines) { text, lines, _ -> wrap(text, lines, chunk) }

    @Test
    fun contextLinesFillTheBudget() {
        val rows = build("当前句", listOf("第二句", "第三句", "第四句"), maxLines = 4)
        assertEquals(4, rows.size)
        assertFalse(rows[0].secondary)
        assertTrue(rows.drop(1).all { it.secondary })
        assertEquals("第四句", rows[3].text)
    }

    /** 这就是老 bug 的现场：预算 4 行，只有一条副行文本时曾经只画出 2 行。 */
    @Test
    fun primaryWrapLeavesLessRoomForContext() {
        val rows = build("当前句折成了两行文字", listOf("第二句", "第三句", "第四句"), maxLines = 4)
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.secondary })
    }

    @Test
    fun budgetOneKeepsOnlyPrimary() {
        val rows = build("当前句", listOf("第二句", "第三句"), maxLines = 1)
        assertEquals(1, rows.size)
        assertFalse(rows[0].secondary)
    }

    @Test
    fun withoutContextOnlyPrimaryIsDrawn() {
        val rows = build("当前句", emptyList(), maxLines = 4)
        assertEquals(1, rows.size)
    }

    /** 空行不占预算：否则界面上会出现一道空档，后面真正的歌词被挤掉。 */
    @Test
    fun blankContextLinesAreSkipped() {
        val rows = build("当前句", listOf("", "  ", "第二句"), maxLines = 3)
        assertEquals(2, rows.size)
        assertEquals("第二句", rows[1].text)
    }

    /** 单条上下文行自己很长时，也只能吃到剩余预算为止。 */
    @Test
    fun longContextLineStopsAtBudget() {
        val rows = build("当前句", listOf("这一句特别特别特别特别长"), maxLines = 2)
        assertEquals(2, rows.size)
        assertTrue(rows[1].secondary)
    }

    /** 上下文条数比预算多：多出来的整条都不画，不做半截截断。 */
    @Test
    fun extraContextLinesAreDropped() {
        val rows = build("当前句", listOf("第二句", "第三句", "第四句", "第五句"), maxLines = 2)
        assertEquals(2, rows.size)
        assertEquals("第二句", rows[1].text)
    }
}
