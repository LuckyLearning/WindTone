package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTitleFormatTest {

    @Test
    fun `mode 0 keeps title and artist on separate lines`() {
        assertEquals(
            "夜空中最亮的星\n逃跑计划",
            OverlayTitleFormat.format(0, "夜空中最亮的星", "逃跑计划"),
        )
    }

    @Test
    fun `mode 0 drops blank artist`() {
        assertEquals("歌名", OverlayTitleFormat.format(0, "歌名", ""))
        assertEquals("歌手", OverlayTitleFormat.format(0, "", "歌手"))
    }

    @Test
    fun `mode 1 title only and mode 2 artist only`() {
        assertEquals("歌名", OverlayTitleFormat.format(1, "歌名", "歌手"))
        assertEquals("歌手", OverlayTitleFormat.format(2, "歌名", "歌手"))
    }

    @Test
    fun `mode 3 single line with separator`() {
        // 歌名(2) + 歌手(3) = 5 ≤ 10 → 保留「歌名 - 歌手」
        assertEquals("稻香 - 周杰伦", OverlayTitleFormat.format(3, "稻香", "周杰伦"))
    }

    @Test
    fun `mode 3 drops artist when combined exceeds 10 chars`() {
        // 歌名(8) + 歌手(4) = 12 > 10 → 仅歌名
        assertEquals("夜空中最亮的星", OverlayTitleFormat.format(3, "夜空中最亮的星", "逃跑计划"))
    }

    @Test
    fun `mode 3 falls back to title when artist blank`() {
        assertEquals("歌名", OverlayTitleFormat.format(3, "歌名", ""))
    }

    @Test
    fun `mode 3 boundary at exactly 10 chars keeps both`() {
        // 5 + 5 = 10 ≤ 10 → 保留
        assertEquals("abcde - fghij", OverlayTitleFormat.format(3, "abcde", "fghij"))
    }
}
