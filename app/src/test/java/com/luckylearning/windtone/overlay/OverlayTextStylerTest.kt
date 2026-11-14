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

    // ------------------------------------------------ v1.2.54 分角色样式

    @Test
    fun `当前行字号百分比落到 RelativeSizeSpan`() {
        // 单条模式：当前行 150% → 1.5 倍；不设时走纯字符串快路径（见上面的用例）
        val result = OverlayTextStyler.build(
            listOf(OverlayTextComposer.StyledLine("now", OverlayTextComposer.Role.CURRENT)),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
            currentScalePercent = 150,
        ) as Spanned
        val size = result.getSpans(0, result.length, RelativeSizeSpan::class.java).single()
        assertEquals(1.5f, size.sizeChange, 0.0001f)
    }

    @Test
    fun `全屏模式当前行字号是基线 1_13 乘用户百分比`() {
        // 全屏的「当前行比别的行大 13%」是形态基线，用户百分比在它之上叠乘：
        // 120% → 1.13 × 1.2 ≈ 1.356，调小到 80% → 1.13 × 0.8 ≈ 0.904
        fun scaleOf(percent: Int): Float {
            val result = OverlayTextStyler.build(
                listOf(
                    OverlayTextComposer.StyledLine("now", OverlayTextComposer.Role.CURRENT),
                    OverlayTextComposer.StyledLine("next", OverlayTextComposer.Role.CONTEXT),
                ),
                fullHeight = true,
                baseColor = white,
                noBackdrop = false,
                currentScalePercent = percent,
            ) as Spanned
            // 当前行是第 0 段，取覆盖起点 0 的那个 span
            return result
                .getSpans(0, 3, RelativeSizeSpan::class.java)
                .single().sizeChange
        }
        assertEquals(1.13f * 1.2f, scaleOf(120), 0.0001f)
        assertEquals(1.13f * 0.8f, scaleOf(80), 0.0001f)
    }

    @Test
    fun `其他行字号百分比只作用在上下句与译文`() {
        val result = OverlayTextStyler.build(
            listOf(
                OverlayTextComposer.StyledLine("now", OverlayTextComposer.Role.CURRENT),
                OverlayTextComposer.StyledLine("prev", OverlayTextComposer.Role.CONTEXT),
            ),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
            contextScalePercent = 80,
        ) as Spanned
        val spans = result.getSpans(0, result.length, RelativeSizeSpan::class.java)
        assertEquals("只该有一处缩放 span（上下句）", 1, spans.size)
        assertEquals(0.8f, spans[0].sizeChange, 0.0001f)
        assertEquals(
            "缩放 span 必须落在上下句那一段，而不是当前行",
            "prev",
            result.subSequence(result.getSpanStart(spans[0]), result.getSpanEnd(spans[0])).toString(),
        )
    }

    @Test
    fun `其他行颜色只换色相，不透明度仍按压暗算`() {
        val gray = 0xFF888888.toInt()
        val result = OverlayTextStyler.build(
            listOf(
                OverlayTextComposer.StyledLine("now", OverlayTextComposer.Role.CURRENT),
                OverlayTextComposer.StyledLine("prev", OverlayTextComposer.Role.CONTEXT),
            ),
            fullHeight = false,
            baseColor = white,
            noBackdrop = false,
            contextColor = gray,
        ) as Spanned
        val color = result
            .getSpans(0, result.length, ForegroundColorSpan::class.java)
            .single().foregroundColor
        // RGB 换成用户选的灰，alpha 仍是默认压暗 42% 对应的 150
        assertEquals(gray and 0x00FFFFFF, color and 0x00FFFFFF)
        assertEquals(150, color ushr 24)
    }
}
