package com.luckylearning.windtone.overlay

import android.view.Gravity
import com.luckylearning.windtone.data.SettingsDefaults
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

    /**
     * v1.2.54：标题三档对齐。
     *
     * 与歌词的两档对齐**刻意分开**：歌词只有居中 / 居左，标题多一档居右，
     * 两个函数各自忽略对方的档位（歌词传 2 仍居中，标题传 2 才靠右）。
     */
    @Test
    fun `alignGravity3 三档各自落到对应 gravity`() {
        assertEquals(
            Gravity.START or Gravity.CENTER_VERTICAL,
            OverlayAppearance.alignGravity3(OverlayPositioner.ALIGN_LEFT),
        )
        assertEquals(Gravity.CENTER, OverlayAppearance.alignGravity3(OverlayPositioner.ALIGN_CENTER))
        assertEquals(
            Gravity.END or Gravity.CENTER_VERTICAL,
            OverlayAppearance.alignGravity3(OverlayPositioner.ALIGN_RIGHT),
        )
        // 越界值必须回落到居中，不能发散成别的对齐
        assertEquals(Gravity.CENTER, OverlayAppearance.alignGravity3(-1))
        assertEquals(Gravity.CENTER, OverlayAppearance.alignGravity3(9))
    }

    @Test
    fun `titleTextSp 直接用磅值，不再按正文百分比换算`() {
        // v1.2.54：标题字号是独立磅值 —— 传 13 就是 13sp，与正文多少磅无关
        assertEquals(13f, OverlayAppearance.titleTextSp(13), 1e-4f)
        assertEquals(12f, OverlayAppearance.titleTextSp(SettingsDefaults.OVERLAY_TITLE_SP), 1e-4f)
        // 不设上限：车机上要 40 磅也给 40 磅
        assertEquals(40f, OverlayAppearance.titleTextSp(40), 1e-4f)
    }

    @Test
    fun `titleTextSp 只挡 0 与负数`() {
        // 0 / 负数会让标题彻底消失，夹到 1sp 保底；正数一律原样
        assertEquals(1f, OverlayAppearance.titleTextSp(0), 1e-4f)
        assertEquals(1f, OverlayAppearance.titleTextSp(-5), 1e-4f)
        assertEquals(999f, OverlayAppearance.titleTextSp(999), 1e-4f)
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
