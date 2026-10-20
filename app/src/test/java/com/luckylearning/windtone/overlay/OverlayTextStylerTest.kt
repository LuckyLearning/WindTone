package com.luckylearning.windtone.overlay

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 悬浮歌词文本样式的回归。
 *
 * 这段逻辑从 1600 行的 `LyricOverlayService` 抽出来后，第一次可以单独断言
 * 「主角 / 配角 / 译文分别套了什么 span」——以前只能靠肉眼在桌面上看。
 */
@RunWith(RobolectricTestRunner::class)
class OverlayTextStylerTest {

    private val white = Color.WHITE

    @Test
    fun singleCurrentLineUsesPlainStringFastPath() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("hello", OverlayTextComposer.Role.CURRENT)),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
        )
        assertTrue("单行主角不应构造 span", result is String)
        assertEquals("hello", result.toString())
    }

    @Test
    fun fullHeightCurrentLineIsBoldAndLarger() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("hello", OverlayTextComposer.Role.CURRENT)),
            fullHeight = true,
            baseColor = white,
            noBackdrop = false,
        ) as Spanned

        val size = result.getSpans(0, result.length, RelativeSizeSpan::class.java).single()
        assertEquals(1.13f, size.sizeChange, 0.0001f)
        val style = result.getSpans(0, result.length, StyleSpan::class.java).single()
        assertEquals(Typeface.BOLD, style.style)
    }

    @Test
    fun contextLineOutsideFullHeightUsesDimColor() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("prev", OverlayTextComposer.Role.CONTEXT)),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
        ) as Spanned

        val color = result.getSpans(0, result.length, ForegroundColorSpan::class.java).single()
        assertEquals(Color.argb(150, 255, 255, 255), color.foregroundColor)
    }

    @Test
    fun translationLineIsSmaller() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("译", OverlayTextComposer.Role.TRANSLATION)),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
        ) as Spanned

        val size = result.getSpans(0, result.length, RelativeSizeSpan::class.java).single()
        assertEquals(0.82f, size.sizeChange, 0.0001f)
    }

    @Test
    fun withAlphaKeepsRgbAndSetsAlpha() {
        assertEquals(
            0x80336699.toInt(),
            OverlayTextStyler.withAlpha(0xFF336699.toInt(), 0x80),
        )
    }
}
