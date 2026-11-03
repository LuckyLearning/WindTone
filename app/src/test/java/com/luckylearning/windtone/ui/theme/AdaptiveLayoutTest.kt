package com.luckylearning.windtone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段三「统一判据接线」的行为锁。
 *
 * 这一阶段把 MainScreen 的 `screenWidthDp >= 840`、四个子页各自的 `wide`、
 * 以及 56dp Dock 常量全部换成 `LocalUiDensity` / `LocalUiFormFactor`。
 * CompositionLocal 本身是 Compose 侧无法在 JVM 里断言的，但它的**取值来源**
 * （FormFactor.resolve + Density.forFormFactor）是纯函数，这里锁住接线后
 * 真正会被读到的那几个字段，防止以后改表时把车机安全区悄悄改没。
 */
class AdaptiveLayoutTest {

    private data class Case(
        val width: Float,
        val height: Float,
        val carMode: Boolean,
        val carHardware: Boolean,
    )

    private fun densityOf(c: Case): UiDensity {
        val form = FormFactor.resolve(c.width, c.height, c.carMode, c.carHardware)
        return Density.forFormFactor(form, c.width, c.height)
    }

    @Test
    fun `DiLink 真机 1280x656 让位 56dp 且限宽 1100dp`() {
        val d = densityOf(Case(1280f, 656f, false, true))
        assertEquals(UiFormFactor.CAR, d.formFactor)
        assertEquals(56f, d.dockReserved.value, 0f)
        assertEquals(1100f, d.contentMaxWidth.value, 0f)
    }

    @Test
    fun `车机窄竖屏也让位 —— 旧的 840 宽度判据会漏掉这种情况`() {
        // 旧实现：widthDp >= 840 才让 56dp。车机竖屏 / 分屏宽度不足时会被 Dock 盖住。
        val d = densityOf(Case(700f, 1100f, true, false))
        assertEquals(UiFormFactor.CAR, d.formFactor)
        assertEquals(56f, d.dockReserved.value, 0f)
    }

    @Test
    fun `手机横屏宽屏不再白让 56dp`() {
        // 旧实现：手机横屏 widthDp >= 840 也会白让 56dp，底部平白少一块。
        val d = densityOf(Case(900f, 420f, false, false))
        assertEquals(UiFormFactor.TABLET, d.formFactor)
        assertEquals(0f, d.dockReserved.value, 0f)
    }

    @Test
    fun `四个全屏子页的限宽按形态查表`() {
        assertEquals(720f, densityOf(Case(360f, 780f, false, false)).contentMaxWidth.value, 0f)
        assertEquals(900f, densityOf(Case(1000f, 700f, false, false)).contentMaxWidth.value, 0f)
        assertEquals(1100f, densityOf(Case(1280f, 656f, false, true)).contentMaxWidth.value, 0f)
        assertEquals(1100f, densityOf(Case(1920f, 900f, true, false)).contentMaxWidth.value, 0f)
    }

    @Test
    fun `限宽永远不超过屏幕短边方向可用宽度的一半以上留白`() {
        // 防回归：车机紧凑档 1100dp 在 1280dp 宽屏上仍留 90dp/侧，不会顶到边。
        val d = densityOf(Case(1280f, 656f, false, true))
        assertTrue(d.contentMaxWidth.value < 1280f)
        assertTrue(d.horizontalPadding.value > 0f)
    }

    @Test
    fun `形态优先级在密度表里保持一致`() {
        // 用户显式拨车载模式：即便设备不是车机、屏幕是手机尺寸，也走车机密度
        val forced = densityOf(Case(360f, 780f, true, false))
        assertEquals(UiFormFactor.CAR, forced.formFactor)
        assertEquals(56f, forced.dockReserved.value, 0f)
        assertTrue(forced.touchMin.value >= 56f)
    }
}
