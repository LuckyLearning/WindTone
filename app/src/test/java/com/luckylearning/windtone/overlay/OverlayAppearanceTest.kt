package com.luckylearning.windtone.overlay

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayAppearance] 纯函数单测。
 *
 * 这个对象不依赖 Android 运行时（用到的 `Gravity` 等都是编译期内联的整型常量），
 * 所以在普通 JVM 单测里直接验证，不需要 Robolectric 起模拟环境 —— 跑得快、也不脆。
 */
class OverlayAppearanceTest {

    @Test
    fun `alignGravity 居左返回起始加垂直居中`() {
        val g = OverlayAppearance.alignGravity(OverlayPositioner.ALIGN_LEFT)
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, g)
    }

    @Test
    fun `alignGravity 非居左返回居中`() {
        // 0（默认居中档）、2（居右）都该落到居中，不能因为传错值就发散
        assertEquals(Gravity.CENTER, OverlayAppearance.alignGravity(0))
        assertEquals(Gravity.CENTER, OverlayAppearance.alignGravity(2))
    }

    @Test
    fun `bodyTextSp 三档倍率正确`() {
        // 20f * [0.82, 1.0, 1.25]
        assertEquals(20f * 0.82f, OverlayAppearance.bodyTextSp(0), 1e-6f)
        assertEquals(20f * 1.0f, OverlayAppearance.bodyTextSp(1), 1e-6f)
        assertEquals(20f * 1.25f, OverlayAppearance.bodyTextSp(2), 1e-6f)
    }

    @Test
    fun `bodyTextSp 越界档位被夹取到端点`() {
        // -1 当成最小档 0；5 当成最大档 2
        assertEquals(OverlayAppearance.bodyTextSp(0), OverlayAppearance.bodyTextSp(-1), 1e-6f)
        assertEquals(OverlayAppearance.bodyTextSp(2), OverlayAppearance.bodyTextSp(5), 1e-6f)
    }

    @Test
    fun `titleTextSp 在正文基础上再乘标题缩小比例`() {
        // 20 * 倍率 * 0.62
        assertEquals(20f * 0.82f * 0.62f, OverlayAppearance.titleTextSp(0), 1e-6f)
        assertEquals(20f * 1.0f * 0.62f, OverlayAppearance.titleTextSp(1), 1e-6f)
        assertEquals(20f * 1.25f * 0.62f, OverlayAppearance.titleTextSp(2), 1e-6f)
    }

    @Test
    fun `titleTextSp 越界同样夹取`() {
        assertEquals(OverlayAppearance.titleTextSp(0), OverlayAppearance.titleTextSp(-9), 1e-6f)
        assertEquals(OverlayAppearance.titleTextSp(2), OverlayAppearance.titleTextSp(99), 1e-6f)
    }

    @Test
    fun `targetAlpha 播放满亮、暂停压暗`() {
        assertEquals(1f, OverlayAppearance.targetAlpha(opaque = true), 1e-6f)
        assertEquals(OverlayAppearance.PAUSED_ALPHA, OverlayAppearance.targetAlpha(opaque = false), 1e-6f)
        assertEquals(0.55f, OverlayAppearance.targetAlpha(opaque = false), 1e-6f)
    }

    @Test
    fun `shadowFor 无底板用更紧的深色描边`() {
        val s = OverlayAppearance.shadowFor(OverlayAppearance.BACKDROP_NONE)
        assertEquals(2, s.radiusDp)
        assertEquals(0, s.offsetYDp)
        assertEquals(255, s.colorAlpha)
    }

    @Test
    fun `shadowFor 有底板用柔光`() {
        // 半透明（1）/ 实底（2）都走柔光分支
        val half = OverlayAppearance.shadowFor(1)
        assertEquals(3, half.radiusDp)
        assertEquals(1, half.offsetYDp)
        assertEquals(220, half.colorAlpha)

        val solid = OverlayAppearance.shadowFor(OverlayAppearance.BACKDROP_SOLID)
        assertEquals(3, solid.radiusDp)
        assertEquals(1, solid.offsetYDp)
    }

    @Test
    fun `fullHeightLineSpacing 返回全屏行距`() {
        val (extra, mult) = OverlayAppearance.fullHeightLineSpacing()
        assertEquals(10, extra)
        assertEquals(1.2f, mult, 1e-6f)
    }

    @Test
    fun `底板档位常量语义稳定`() {
        assertEquals(0, OverlayAppearance.BACKDROP_NONE)
        assertEquals(2, OverlayAppearance.BACKDROP_SOLID)
        assertTrue(OverlayAppearance.BACKDROP_NONE < OverlayAppearance.BACKDROP_SOLID)
        // 不等于两档的任意中间值都不应被误判为「无底板」
        assertFalse(1 == OverlayAppearance.BACKDROP_NONE)
    }
}
