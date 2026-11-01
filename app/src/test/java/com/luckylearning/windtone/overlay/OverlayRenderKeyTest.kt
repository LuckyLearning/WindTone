package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [OverlayRenderKey]：字不变则键不变；角色 / 样式入参变则键变。
 */
class OverlayRenderKeyTest {

    private val line = OverlayTextComposer.StyledLine("你好", OverlayTextComposer.Role.CURRENT)

    @Test
    fun `相同入参得到相同键`() {
        val a = OverlayRenderKey.build(listOf(line), false, true, 0xFFFFFFFF.toInt(), false, 0, 42)
        val b = OverlayRenderKey.build(listOf(line), false, true, 0xFFFFFFFF.toInt(), false, 0, 42)
        assertEquals(a, b)
    }

    @Test
    fun `角色变化键变化（间奏压暗）`() {
        val current = OverlayRenderKey.build(
            listOf(line), false, true, 0xFFFFFFFF.toInt(), false, 0, 42,
        )
        val idle = OverlayRenderKey.build(
            listOf(OverlayTextComposer.StyledLine("你好", OverlayTextComposer.Role.CONTEXT)),
            false, true, 0xFFFFFFFF.toInt(), false, 0, 42,
        )
        assertNotEquals(current, idle)
    }

    @Test
    fun `正文色变化键变化`() {
        val a = OverlayRenderKey.build(listOf(line), false, true, 0xFFFFFFFF.toInt(), false, 0, 42)
        val b = OverlayRenderKey.build(listOf(line), false, true, 0xFFFFAA00.toInt(), false, 0, 42)
        assertNotEquals(a, b)
    }

    @Test
    fun `文案变化键变化`() {
        val a = OverlayRenderKey.build(listOf(line), false, true, 0xFFFFFFFF.toInt(), false, 0, 42)
        val b = OverlayRenderKey.build(
            listOf(OverlayTextComposer.StyledLine("下一句", OverlayTextComposer.Role.CURRENT)),
            false, true, 0xFFFFFFFF.toInt(), false, 0, 42,
        )
        assertNotEquals(a, b)
    }
}
