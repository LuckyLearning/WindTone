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

    // ---- v1.1.52：当前行颜色与压暗百分比 ----

    @Test
    fun `单行主角设了当前行颜色后走染色路径`() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("hello", OverlayTextComposer.Role.CURRENT)),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
            currentColor = 0xFFFF8800.toInt(),
        ) as Spanned
        val color = result.getSpans(0, result.length, ForegroundColorSpan::class.java).single()
        assertEquals(0xFFFF8800.toInt(), color.foregroundColor)
    }

    @Test
    fun `当前行颜色为零时保持纯字符串快路径`() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("hello", OverlayTextComposer.Role.CURRENT)),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
            currentColor = 0,
        )
        assertTrue("没设当前行颜色就不该构造 span", result is String)
    }

    @Test
    fun `全屏当前行颜色与字号加粗共存`() {
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("hello", OverlayTextComposer.Role.CURRENT)),
            fullHeight = true,
            baseColor = white,
            noBackdrop = false,
            currentColor = 0xFF66CCFF.toInt(),
        ) as Spanned
        assertEquals(
            0xFF66CCFF.toInt(),
            result.getSpans(0, result.length, ForegroundColorSpan::class.java).single().foregroundColor,
        )
        assertEquals(1, result.getSpans(0, result.length, StyleSpan::class.java).size)
    }

    @Test
    fun `单条模式上下句跟随自定义文字颜色`() {
        // v1.1.79 回归：上下句曾写死白色，用户改文字颜色后只有歌名变色。
        val orange = 0xFFFF8800.toInt()
        fun colorOf(role: OverlayTextComposer.Role): Int {
            val result = OverlayTextStyler.build(
                listOf(OverlayTextComposer.StyledLine("line", role)),
                fullHeight = false,
                baseColor = orange,
                noBackdrop = false,
            ) as Spanned
            return result
                .getSpans(0, result.length, ForegroundColorSpan::class.java)
                .single().foregroundColor
        }
        // alpha 与旧版一致（默认压暗 42% → 150），RGB 换成用户选的色相
        assertEquals(Color.argb(150, 255, 136, 0), colorOf(OverlayTextComposer.Role.CONTEXT))
        assertEquals(Color.argb(150, 255, 136, 0), colorOf(OverlayTextComposer.Role.TRANSLATION))
    }

    @Test
    fun `压暗百分比反映到全屏上下句`() {
        fun contextAlphaOf(dim: Int): Int {
            val result = OverlayTextStyler.build(
                listOf(OverlayTextComposer.StyledLine("prev", OverlayTextComposer.Role.CONTEXT)),
                fullHeight = true,
                baseColor = white,
                noBackdrop = false,
                dimPercent = dim,
            ) as Spanned
            return result
                .getSpans(0, result.length, ForegroundColorSpan::class.java)
                .single().foregroundColor ushr 24
        }
        assertEquals(0x6B, contextAlphaOf(OverlayLegibility.DEFAULT_DIM_PERCENT))
        assertTrue(
            "压暗加深后 alpha 要变大",
            contextAlphaOf(80) > contextAlphaOf(OverlayLegibility.DEFAULT_DIM_PERCENT),
        )
    }
}
