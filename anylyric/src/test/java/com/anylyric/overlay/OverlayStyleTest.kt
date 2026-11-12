package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 样式默认值与预设展开的守门测试。
 *
 * 存在的意义是防「改了一处默认值、忘了另一处」——项目红线要求所有可调项的恢复默认
 * 只能写回同一个常量出处，这里逐项对质。
 */
class OverlayStyleTest {

    @Test
    fun defaultsMatchConstants() {
        val style = OverlayDefaults.STYLE
        assertEquals(OverlayDefaults.FONT_SP, style.fontSp, 0.001f)
        assertEquals(OverlayDefaults.MAX_LINES, style.maxLines)
        assertEquals(OverlayDefaults.OPACITY, style.opacity, 0.001f)
        assertEquals(OverlayDefaults.CORNER_DP, style.cornerDp, 0.001f)
        assertEquals(OverlayDefaults.SECONDARY_ALPHA, style.secondaryAlpha, 0.001f)
        assertEquals(OverlayDefaults.FADE_MS, style.fadeMs)
        assertEquals(OverlayDefaults.STATE_MARK, style.stateMark)
        assertEquals(OverlayDefaults.PRIMARY_COLOR, style.primaryColor)
        assertEquals(OverlayDefaults.SECONDARY_COLOR, style.secondaryColor)
        assertEquals(OverlayDefaults.BG_COLOR, style.bgColor)
        assertEquals(OverlayDefaults.WIDTH_RATIO, style.widthRatio, 0.001f)
        assertEquals(OverlayDefaults.HEIGHT_RATIO, style.heightRatio, 0.001f)
        assertEquals(Preset.GLASS, style.preset)
        assertEquals(Align.START, style.align)
        assertEquals(Secondary.NEXT, style.secondary)
    }

    /** 默认白字 + 深底：与旧版硬编码的观感一致，升级后不该突然换个样子。 */
    @Test
    fun defaultColorsMatchLegacyLook() {
        assertEquals(0xFFFFFFFF.toInt(), OverlayDefaults.STYLE.primaryColor)
        assertEquals(0xFFFFFFFF.toInt(), OverlayDefaults.STYLE.secondaryColor)
        assertEquals(0xFF14161A.toInt(), OverlayDefaults.STYLE.bgColor)
    }

    /** 0 是「自适应」的哨兵值，不能被 coerceIn 拉成下限，否则默认就变成固定宽度了。 */
    @Test
    fun autoSizeStaysAuto() {
        val safe = OverlayDefaults.sanitize(
            OverlayStyle(widthRatio = 0f, heightRatio = 0f)
        )
        assertEquals(0f, safe.widthRatio, 0.0001f)
        assertEquals(0f, safe.heightRatio, 0.0001f)
    }

    @Test
    fun sizeRatiosAreClamped() {
        val tiny = OverlayDefaults.sanitize(OverlayStyle(widthRatio = 0.02f, heightRatio = 0.001f))
        assertEquals(OverlayDefaults.WIDTH_MIN, tiny.widthRatio, 0.0001f)
        assertEquals(OverlayDefaults.HEIGHT_MIN, tiny.heightRatio, 0.0001f)

        val huge = OverlayDefaults.sanitize(OverlayStyle(widthRatio = 0.99f, heightRatio = 0.9f))
        assertEquals(OverlayDefaults.WIDTH_MAX, huge.widthRatio, 0.0001f)
        assertEquals(OverlayDefaults.HEIGHT_MAX, huge.heightRatio, 0.0001f)

        val nan = OverlayDefaults.sanitize(OverlayStyle(widthRatio = Float.NaN))
        assertEquals(0f, nan.widthRatio, 0.0001f)
    }

    /** 换预设不能把用户自定义的配色冲掉 —— 那是最容易被当成 bug 的行为。 */
    @Test
    fun presetKeepsCustomColors() {
        val base = OverlayDefaults.STYLE.copy(
            primaryColor = 0xFF9BE7FF.toInt(),
            bgColor = 0xFF000000.toInt()
        )
        val style = OverlayDefaults.preset(Preset.SOLID, base)
        assertEquals(0xFF9BE7FF.toInt(), style.primaryColor)
        assertEquals(0xFF000000.toInt(), style.bgColor)
    }

    @Test
    fun sanitizeClampsEveryRange() {
        val bad = OverlayStyle(
            fontSp = 200f,
            maxLines = 9,
            opacity = -5f,
            cornerDp = 100f,
            secondaryAlpha = 0f,
            fadeMs = 9999
        )
        val safe = OverlayDefaults.sanitize(bad)
        assertEquals(OverlayDefaults.FONT_MAX, safe.fontSp, 0.001f)
        assertEquals(OverlayDefaults.LINES_MAX, safe.maxLines)
        assertEquals(0f, safe.opacity, 0.001f)
        assertEquals(OverlayDefaults.CORNER_MAX, safe.cornerDp, 0.001f)
        assertEquals(OverlayDefaults.SECONDARY_ALPHA_MIN, safe.secondaryAlpha, 0.001f)
        assertEquals(OverlayDefaults.FADE_MAX, safe.fadeMs)
    }

    @Test
    fun sanitizeRaisesTooSmallValues() {
        val safe = OverlayDefaults.sanitize(
            OverlayStyle(fontSp = 2f, maxLines = 0, opacity = 0f, cornerDp = -4f, fadeMs = -1)
        )
        assertEquals(OverlayDefaults.FONT_MIN, safe.fontSp, 0.001f)
        assertEquals(OverlayDefaults.LINES_MIN, safe.maxLines)
        assertEquals(0f, safe.opacity, 0.001f)
        assertEquals(0f, safe.cornerDp, 0.001f)
        assertEquals(0, safe.fadeMs)
    }

    @Test
    fun stripePresetDropsBackgroundAndUsesOutline() {
        val style = OverlayDefaults.preset(Preset.STRIPE, OverlayDefaults.STYLE)
        assertEquals(0f, style.opacity, 0.001f)
        assertTrue(style.stroke)
        assertFalse(style.stateMark)
        assertEquals(Preset.STRIPE, style.preset)
    }

    @Test
    fun naviPresetIsSingleCenteredLine() {
        val style = OverlayDefaults.preset(Preset.NAVI, OverlayDefaults.STYLE)
        assertEquals(1, style.maxLines)
        assertEquals(Align.CENTER, style.align)
        assertEquals(Preset.NAVI, style.preset)
    }

    /** 换预设只该改观感，不能把用户调好的字号与副行策略一起重置。 */
    @Test
    fun presetKeepsFontSizeAndSecondaryChoice() {
        val base = OverlayDefaults.STYLE.copy(fontSp = 34f, secondary = Secondary.PREVIOUS)
        val style = OverlayDefaults.preset(Preset.SOLID, base)
        assertEquals(34f, style.fontSp, 0.001f)
        assertEquals(Secondary.PREVIOUS, style.secondary)
    }

    @Test
    fun parsesStoredIds() {
        assertEquals(Preset.SOLID, Preset.of("solid"))
        assertEquals(Preset.GLASS, Preset.of(null))
        assertEquals(Preset.GLASS, Preset.of("nonsense"))
        assertEquals(Align.END, Align.of("end"))
        assertEquals(Secondary.TRACK, Secondary.of("track"))
        assertEquals(Secondary.NEXT, Secondary.of(null))
    }
}
