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
    fun `titleTextSp 是正文乘百分比，不再有档位倍率`() {
        // 正文 20sp、标题 62% = 12.4sp
        assertEquals(12.4f, OverlayAppearance.titleTextSp(20, 62), 1e-4f)
        // 正文调到 28sp，标题跟着放大，不需要用户再动一次
        assertEquals(17.36f, OverlayAppearance.titleTextSp(28, 62), 1e-4f)
        // 100% = 与正文同号
        assertEquals(30f, OverlayAppearance.titleTextSp(30, 100), 1e-4f)
    }

    @Test
    fun `titleTextSp 的正文与百分比越界都被夹取`() {
        // 正文越界夹到 12..60sp，百分比越界夹到 1..200%
        assertEquals(OverlayAppearance.titleTextSp(12, 62), OverlayAppearance.titleTextSp(-9, 62), 1e-4f)
        assertEquals(OverlayAppearance.titleTextSp(60, 62), OverlayAppearance.titleTextSp(999, 62), 1e-4f)
        assertEquals(OverlayAppearance.titleTextSp(20, 1), OverlayAppearance.titleTextSp(20, 0), 1e-4f)
        assertEquals(OverlayAppearance.titleTextSp(20, 200), OverlayAppearance.titleTextSp(20, 999), 1e-4f)
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
