package com.luckylearning.windtone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI v2 一体化设计系统地基（FormFactor / Density / Tokens / Typography）。
 *
 * 这些是纯 JVM 可断言的纯函数与常量，锁住三件最容易改坏的事：
 *  · 形态判定优先级（用户意图 > 设备识别 > 尺寸）；
 *  · 车机紧凑档沿用已验证的真机尺寸、且不小于最小触控目标；
 *  · 手机与车机读的是**同一张** UiDensity 表（字段一致，只是档位不同）。
 */
class DesignSystemTest {

    // ---------------------------------------------------------------- 形态判定

    @Test
    fun `显式车载模式优先级最高`() {
        assertEquals(
            UiFormFactor.CAR,
            FormFactor.resolve(
                widthDp = 360f,
                heightDp = 780f,
                carMode = true,
                carHardware = false,
            ),
        )
    }

    @Test
    fun `设备识别为车机即走 CAR`() {
        // 修复旧割裂：DiLink 上没拨开关却按手机竖屏渲染
        assertEquals(
            UiFormFactor.CAR,
            FormFactor.resolve(
                widthDp = 1280f,
                heightDp = 656f,
                carMode = false,
                carHardware = true,
            ),
        )
    }

    @Test
    fun `宽屏横屏走 TABLET`() {
        assertEquals(
            UiFormFactor.TABLET,
            FormFactor.resolve(
                widthDp = 1000f,
                heightDp = 700f,
                carMode = false,
                carHardware = false,
            ),
        )
    }

    @Test
    fun `普通手机竖屏走 PHONE`() {
        assertEquals(
            UiFormFactor.PHONE,
            FormFactor.resolve(
                widthDp = 360f,
                heightDp = 780f,
                carMode = false,
                carHardware = false,
            ),
        )
    }

    @Test
    fun `宽度够但竖屏不算宽形态`() {
        assertEquals(
            UiFormFactor.PHONE,
            FormFactor.resolve(
                widthDp = 900f,
                heightDp = 1200f,
                carMode = false,
                carHardware = false,
            ),
        )
    }

    @Test
    fun `840dp 是宽屏阈值边界`() {
        assertFalse(
            FormFactor.resolve(839f, 700f, false, false).isLarge,
        )
        assertTrue(
            FormFactor.resolve(840f, 700f, false, false).isLarge,
        )
    }

    @Test
    fun `只有车机需要焦点环与侧导航`() {
        assertTrue(UiFormFactor.CAR.needsFocusRing)
        assertTrue(UiFormFactor.CAR.hasSideNav)
        assertFalse(UiFormFactor.PHONE.needsFocusRing)
        assertFalse(UiFormFactor.PHONE.hasSideNav)
        assertTrue(UiFormFactor.TABLET.hasSideNav)
    }

    // ---------------------------------------------------------------- 密度表

    @Test
    fun `DiLink 实测 1280x656 落车机紧凑档`() {
        val d = Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f)
        assertTrue(d.compact)
        assertEquals(UiFormFactor.CAR, d.formFactor)
        assertEquals(84f, d.nowPlayingBar.value, 0f)
        assertEquals(208f, d.navWidth.value, 0f)
    }

    @Test
    fun `大屏车机走常规档`() {
        val d = Density.forFormFactor(UiFormFactor.CAR, 1920f, 900f)
        assertFalse(d.compact)
        assertEquals(112f, d.nowPlayingBar.value, 0f)
    }

    @Test
    fun `紧凑档每一项都不大于常规档`() {
        val c = Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f)
        val r = Density.forFormFactor(UiFormFactor.CAR, 1920f, 900f)
        assertTrue(c.spaceUnit.value <= r.spaceUnit.value)
        assertTrue(c.touchMin.value <= r.touchMin.value)
        assertTrue(c.rowMin.value <= r.rowMin.value)
        assertTrue(c.nowPlayingBar.value <= r.nowPlayingBar.value)
        assertTrue(c.mainButton.value <= r.mainButton.value)
        assertTrue(c.sideButton.value <= r.sideButton.value)
        assertTrue(c.navWidth.value <= r.navWidth.value)
        assertTrue(c.titleSp.value <= r.titleSp.value)
        assertTrue(c.bodySp.value <= r.bodySp.value)
    }

    @Test
    fun `所有档位触控目标不小于 48dp`() {
        val all = listOf(
            Density.forFormFactor(UiFormFactor.PHONE, 360f, 780f),
            Density.forFormFactor(UiFormFactor.TABLET, 1000f, 700f),
            Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f),
            Density.forFormFactor(UiFormFactor.CAR, 1920f, 900f),
        )
        all.forEach { d ->
            assertTrue("${d.formFactor} 触控目标 ${d.touchMin}", d.touchMin.value >= 48f)
            assertTrue("${d.formFactor} 行高 ${d.rowMin}", d.rowMin.value >= d.touchMin.value)
        }
    }

    @Test
    fun `Dock 让位只在车机出现`() {
        assertEquals(56f, Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f).dockReserved.value, 0f)
        assertEquals(0f, Density.forFormFactor(UiFormFactor.PHONE, 360f, 780f).dockReserved.value, 0f)
        assertEquals(0f, Density.forFormFactor(UiFormFactor.TABLET, 1000f, 700f).dockReserved.value, 0f)
    }

    @Test
    fun `手机用抽屉没有侧导航`() {
        val phone = Density.forFormFactor(UiFormFactor.PHONE, 360f, 780f)
        assertEquals(0f, phone.navWidth.value, 0f)
        assertEquals(0f, phone.focusRing.value, 0f)
        assertTrue(Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f).navWidth.value > 0f)
    }

    @Test
    fun `车机缩放系数大于手机`() {
        val phone = Density.forFormFactor(UiFormFactor.PHONE, 360f, 780f)
        val car = Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f)
        assertTrue(car.scale > phone.scale)
        assertTrue(car.titleSp.value >= phone.titleSp.value)
    }

    // ---------------------------------------------------------------- 令牌 / 排版

    @Test
    fun `圆角五档严格递增`() {
        val radii = listOf(Radius.xs, Radius.s, Radius.m, Radius.l, Radius.xl)
        for (i in 1 until radii.size) {
            assertTrue(radii[i].value > radii[i - 1].value)
        }
        assertEquals(6f, Radius.xs.value, 0f)
        assertEquals(28f, Radius.xl.value, 0f)
    }

    @Test
    fun `间距档严格递增且落在 4dp 栅格`() {
        val spacings = listOf(Spacing.xxs, Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl, Spacing.xxxl, Spacing.huge)
        for (i in 1 until spacings.size) {
            assertTrue(spacings[i].value > spacings[i - 1].value)
        }
        assertEquals(4f, Spacing.unit.value, 0f)
        assertTrue(Spacing.s.value % Spacing.unit.value == 0f)
    }

    @Test
    fun `图标尺寸档严格递增`() {
        val sizes = listOf(IconSize.xs, IconSize.s, IconSize.m, IconSize.base, IconSize.l, IconSize.xl, IconSize.xxl)
        for (i in 1 until sizes.size) {
            assertTrue(sizes[i].value > sizes[i - 1].value)
        }
        assertEquals(24f, IconSize.base.value, 0f)
    }

    @Test
    fun `海拔三档与 windShadow 一致且递增`() {
        assertTrue(Elevation.card.value < Elevation.floating.value)
        assertTrue(Elevation.floating.value < Elevation.modal.value)
        assertEquals(6f, Elevation.card.value, 0f)
        assertEquals(22f, Elevation.modal.value, 0f)
    }

    @Test
    fun `动效时长收敛了此前散落的魔法值`() {
        assertEquals(AppVisual.Motion.FastMs, Duration.fastMs)
        assertEquals(AppVisual.Motion.BaseMs, Duration.baseMs)
        assertEquals(AppVisual.Motion.SlowMs, Duration.slowMs)
        assertEquals(220, Duration.drawerMs)
        assertEquals(320, Duration.lyricsHighlightMs)
        assertEquals(1400, Duration.skeletonMs)
        assertEquals(2600, Duration.breathMs)
        assertEquals(24_000, Duration.vinylRotationMs)
    }

    @Test
    fun `排版字阶单调递减且已接线`() {
        assertTrue(AppTypography.titleLarge.fontSize.value > AppTypography.titleMedium.fontSize.value)
        assertTrue(AppTypography.titleMedium.fontSize.value > AppTypography.titleSmall.fontSize.value)
        assertTrue(AppTypography.bodyLarge.fontSize.value > AppTypography.bodyMedium.fontSize.value)
        assertTrue(AppTypography.bodyMedium.fontSize.value > AppTypography.bodySmall.fontSize.value)
        assertTrue(AppTypography.labelLarge.fontSize.value >= AppTypography.labelSmall.fontSize.value)
    }
}
