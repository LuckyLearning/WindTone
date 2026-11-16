package com.anylyric.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDefaultsTest {

    @Test
    fun `secondary font zero means auto and must survive sanitize`() {
        // 0 是「自动」的哨兵：被夹成下限的话，用户一改正文大小副行就再也不跟了。
        val out = OverlayDefaults.sanitize(
            OverlayDefaults.STYLE.copy(secondaryFontSp = 0f)
        )
        assertEquals(0f, out.secondaryFontSp, 0.001f)
    }

    @Test
    fun `secondary font clamps into range`() {
        val big = OverlayDefaults.sanitize(
            OverlayDefaults.STYLE.copy(secondaryFontSp = 999f)
        )
        assertEquals(OverlayDefaults.SECONDARY_FONT_MAX, big.secondaryFontSp, 0.001f)
        val small = OverlayDefaults.sanitize(
            OverlayDefaults.STYLE.copy(secondaryFontSp = 1f)
        )
        assertEquals(OverlayDefaults.SECONDARY_FONT_MIN, small.secondaryFontSp, 0.001f)
    }

    @Test
    fun `title alpha never goes fully transparent`() {
        val out = OverlayDefaults.sanitize(OverlayDefaults.STYLE.copy(titleAlpha = 0f))
        assertEquals(0.2f, out.titleAlpha, 0.001f)
    }

    @Test
    fun `word unsung alpha keeps the lyrics readable`() {
        val out = OverlayDefaults.sanitize(
            OverlayDefaults.STYLE.copy(wordUnsungAlpha = 0f)
        )
        assertEquals(OverlayDefaults.WORD_UNSUNG_ALPHA_MIN, out.wordUnsungAlpha, 0.001f)
    }

    @Test
    fun `sung colour zero is preserved so it can follow the main colour`() {
        val out = OverlayDefaults.sanitize(OverlayDefaults.STYLE)
        assertEquals(0, out.wordSungColor)
    }

    @Test
    fun `defaults keep the pre-0_2_28 look`() {
        // 默认值必须与旧行为一致，否则升级完所有人都会觉得「样式被改了」。
        assertEquals(OverlayDefaults.SECONDARY_SCALE, 0.72f, 0.001f)
        assertEquals(OverlayDefaults.SECONDARY_FONT_SP, 0f, 0.001f)
        assertEquals(false, OverlayDefaults.SECONDARY_BOLD)
        assertEquals(false, OverlayDefaults.TITLE_BOLD)
        assertEquals(1f, OverlayDefaults.TITLE_ALPHA, 0.001f)
    }
}
