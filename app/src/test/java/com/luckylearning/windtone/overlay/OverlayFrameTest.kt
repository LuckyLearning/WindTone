package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.data.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗几何 + 背景色（v1.1.48）。
 *
 * 这里钉的四件事都会直接毁掉功能：窗口跑到屏幕外、宽度算成 0、自动高度把纵向位置
 * 算错、自定义背景的「全透明」没被当成无底板（描边会变柔，浅底上就糊）。
 */
class OverlayFrameTest {

    private val sw = 1080
    private val sh = 2400

    private fun frame(
        widthPercent: Int = 80,
        heightPercent: Int = 0,
        xPercent: Int = 50,
        yPercent: Int = 50,
        minWidthPx: Int = 1,
        reserved: Int = 0,
        autoHeightPx: Int = 0,
    ) = OverlayFrame.frame(
        OverlayFrame.Params(
            screenW = sw,
            screenH = sh,
            widthPercent = widthPercent,
            heightPercent = heightPercent,
            xPercent = xPercent,
            yPercent = yPercent,
            minWidthPx = minWidthPx,
            bottomReservedPx = reserved,
            autoHeightPx = autoHeightPx,
        ),
    )

    @Test
    fun `宽高按百分比计算且位置落在余量里`() {
        val f = frame(widthPercent = 80, heightPercent = 30, xPercent = 50, yPercent = 50)
        assertEquals(864, f.width)
        assertEquals(720, f.height)
        assertEquals(108, f.x) // (1080-864) * 50%
        assertEquals(840, f.y) // (2400-720) * 50%
        assertFalse(f.autoHeight)
    }

    @Test
    fun `自动高度按内容高度参与夹取`() {
        val f = frame(heightPercent = OverlayFrame.AUTO_HEIGHT, yPercent = 100, autoHeightPx = 300)
        assertEquals(300, f.height)
        assertTrue(f.autoHeight)
        assertEquals(2100, f.y) // 2400-300
    }

    @Test
    fun `全屏形态扣掉底部让位后高度不再溢出`() {
        val f = frame(widthPercent = 100, heightPercent = 100, yPercent = 0, reserved = 168)
        assertEquals(1080, f.width)
        assertEquals(2232, f.height)
        assertEquals(0, f.x)
        assertEquals(0, f.y)
        // 高度 100% 时纵向没有余量：调到最下也必须贴顶，否则会盖住车机 Dock
        val full = frame(widthPercent = 100, heightPercent = 100, yPercent = 100, reserved = 168)
        assertEquals(0, full.y)

        // 高度 60% 时才有纵向余量；调到最下，底边正好停在让位线上
        val short = frame(widthPercent = 100, heightPercent = 60, yPercent = 100, reserved = 168)
        assertEquals(sh - 168, short.y + short.height)
        assertEquals(1339, short.height)
    }

    @Test
    fun `极窄宽度不会压成一条缝`() {
        // 1% 在 1080 屏上只有 10px：既看不见也拖不着，必须抬到下限
        val f = frame(widthPercent = 1, minWidthPx = 200)
        assertEquals(200, f.width)
        assertEquals((sw - 200) / 2, f.x)
    }

    @Test
    fun `拖拽落盘的百分比可逆`() {
        assertEquals(50, OverlayFrame.percentOf(108, 216))
        assertEquals(0, OverlayFrame.percentOf(0, 216))
        assertEquals(100, OverlayFrame.percentOf(216, 216))
        // 满宽（没有余量）时不该抹掉用户原来的偏好
        assertEquals(37, OverlayFrame.percentOf(0, 0, fallback = 37))
    }

    @Test
    fun `背景只由颜色与不透明度决定，三档已合并`() {
        val red = 0xFFFF0000.toInt()

        // 不透明度 0 = 无底板，调用方据此把背景置空
        assertNull(OverlayBackground.resolve(red, alphaPercent = 0))

        // 55% ≈ 5.5 折，颜色位保持不变
        val half = OverlayBackground.resolve(red, alphaPercent = 55)!!
        assertEquals(140, (half ushr 24) and 0xFF)
        assertEquals(0xFF0000, half and 0xFFFFFF)

        // 100% = 实底
        val solid = OverlayBackground.resolve(red, alphaPercent = 100)!!
        assertEquals(0xFF, (solid ushr 24) and 0xFF)
    }

    @Test
    fun `不透明度为 0 时描边按无底板算`() {
        // 没有底板、描边却按有底板给柔光 → 浅色车机画面上就是一片糊（v1.1.25 的坑）
        assertEquals(
            OverlayAppearance.BACKDROP_NONE,
            OverlayBackground.legibilityKey(alphaPercent = 0),
        )
        assertEquals(
            OverlayAppearance.BACKDROP_SOLID,
            OverlayBackground.legibilityKey(alphaPercent = 30),
        )
        assertEquals(
            OverlayAppearance.BACKDROP_SOLID,
            OverlayBackground.legibilityKey(alphaPercent = 100),
        )
    }

    @Test
    fun `默认背景色与旧底板同色相`() {
        assertEquals(0xFF, (SettingsDefaults.OVERLAY_BG_COLOR ushr 24) and 0xFF)
    }
}
