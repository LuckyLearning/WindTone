package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayStyleScope] 的纯函数部分（v1.2.52）。
 *
 * 这些判断错了，症状都在真机上、而且都不报错：
 *  - 键名映射错 → 夜里改的设置写进了白天那套（用户「改完重启又变回去」）；
 *  - 明暗判定错 → 车机上永远用白天那套（`uiMode` 的类型位没屏蔽干净就会这样）。
 * 所以先在这里把两件事钉死，不依赖 Android 环境。
 */
class OverlayStyleScopeTest {

    @Test
    fun `夜间键名带 d_ 前缀`() {
        assertEquals("d_lyric_overlay_text_sp", OverlayStyleScope.key("lyric_overlay_text_sp", true))
        assertTrue(OverlayStyleScope.key("lyric_overlay_text_sp", true).startsWith(OverlayStyleScope.DARK_PREFIX))
    }

    @Test
    fun `白天用原键`() {
        assertEquals("lyric_overlay_text_sp", OverlayStyleScope.key("lyric_overlay_text_sp", false))
    }

    @Test
    fun `两套的键名互不相同`() {
        val base = "lyric_overlay_bg_alpha_percent"
        assertNotEquals(
            OverlayStyleScope.key(base, false),
            OverlayStyleScope.key(base, true),
        )
    }

    @Test
    fun `夜间 uiMode 判定只看明暗位`() {
        assertTrue(OverlayStyleScope.isNightUiMode(0x20))
        assertFalse(OverlayStyleScope.isNightUiMode(0x10))
        // 高位类型位（车机 0x02 / 电视 0x04）不影响判定：车机不能永远被判成浅色
        assertTrue(OverlayStyleScope.isNightUiMode(0x20 or 0x02))
        assertFalse(OverlayStyleScope.isNightUiMode(0x10 or 0x02))
    }

    @Test
    fun `主题固定时不看系统`() {
        // 浅色主题：系统是深色也用白天那套
        assertFalse(OverlayStyleScope.isDark(ThemeMode.LIGHT, 0x20))
        // 深色主题：系统是浅色也用夜间那套
        assertTrue(OverlayStyleScope.isDark(ThemeMode.DARK, 0x10))
    }

    @Test
    fun `跟随系统时才看 uiMode`() {
        assertTrue(OverlayStyleScope.isDark(ThemeMode.SYSTEM, 0x20))
        assertFalse(OverlayStyleScope.isDark(ThemeMode.SYSTEM, 0x10))
        // 未指定（0x00）按浅色处理：与系统「未声明明暗」的表现一致
        assertFalse(OverlayStyleScope.isDark(ThemeMode.SYSTEM, 0x00))
    }
}
