package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 悬浮窗「字号 / 标题字号 / 背景」从固定档位迁到连续值（v1.1.51）。
 *
 * ## 为什么值得单测
 *
 * 这一版删掉了三组老键（`lyric_overlay_text_scale` / `title_scale` / `backdrop`），
 * 但老用户的手机上它们还在。迁移写错的表现是「升级后歌词忽然变大 / 变小」或者
 * 「底板颜色变了」—— 不报错、不崩溃，只能靠用户一句「怎么和以前不一样了」发现。
 *
 * 迁移刻意做成**读时换算**（不写回、不需要迁移标记）：
 * 新键写过就以新键为准，否则按老值换算一次。这里钉的正是这两条路径的优先级。
 */
@RunWith(RobolectricTestRunner::class)
class OverlayLegacyMigrationTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPreferences().edit().clear().commit()
        Prefs.clearCacheForTest()
        prefs = Prefs(context)
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
        Prefs.clearCacheForTest()
    }

    private fun rawPreferences() =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 正文字号

    @Test
    fun `旧的三档字号换算成 sp，观感不变`() {
        // 16 / 20 / 25sp —— 就是原来 20sp × 0.82 / 1 / 1.25 的结果
        listOf(0 to 16, 1 to 20, 2 to 25).forEach { (scale, sp) ->
            rawPreferences().edit().putInt("lyric_overlay_text_scale", scale).commit()
            Prefs.clearCacheForTest()
            assertEquals("档位 $scale", sp, Prefs(context).lyricOverlayTextSp)
        }
    }

    @Test
    fun `新键写过时不再看旧档位`() {
        rawPreferences().edit()
            .putInt("lyric_overlay_text_scale", 0) // 旧值 16sp
            .putInt("lyric_overlay_text_sp", 34) // 用户自己拖到 34sp
            .commit()
        Prefs.clearCacheForTest()
        assertEquals(34, Prefs(context).lyricOverlayTextSp)
    }

    @Test
    fun `全新安装用 20sp，不受老默认档位影响`() {
        assertEquals(SettingsDefaults.OVERLAY_TEXT_SP, prefs.lyricOverlayTextSp)
    }

    // ------------------------------------------------------------ 标题字号

    @Test
    fun `旧的标题三档换算成正文百分比`() {
        listOf(0 to 55, 1 to 62, 2 to 78).forEach { (scale, percent) ->
            rawPreferences().edit().putInt("lyric_overlay_title_scale", scale).commit()
            Prefs.clearCacheForTest()
            assertEquals("档位 $scale", percent, Prefs(context).lyricOverlayTitlePercent)
        }
    }

    @Test
    fun `全新安装的标题是正文的六成二`() {
        assertEquals(SettingsDefaults.OVERLAY_TITLE_PERCENT, prefs.lyricOverlayTitlePercent)
    }

    // ------------------------------------------------------------ 背景

    @Test
    fun `旧的底板三档换算成不透明度`() {
        // 无底板 = 0%、半透明（argb 140）= 55%、实底（argb 228）= 89%
        listOf(0 to 0, 1 to 55, 2 to 89).forEach { (backdrop, alpha) ->
            rawPreferences().edit().putInt("lyric_overlay_backdrop", backdrop).commit()
            Prefs.clearCacheForTest()
            assertEquals("底板 $backdrop", alpha, Prefs(context).lyricOverlayBgAlphaPercent)
        }
    }

    @Test
    fun `旧版开着自定义背景时沿用当时的不透明度，不再按三档换算`() {
        // 这一档用户的 62% 是他自己拖出来的，不能被「三档映射」覆盖掉
        rawPreferences().edit()
            .putInt("lyric_overlay_backdrop", 0)
            .putBoolean("lyric_overlay_custom_bg", true)
            .commit()
        Prefs.clearCacheForTest()
        assertEquals(SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT, Prefs(context).lyricOverlayBgAlphaPercent)
    }

    @Test
    fun `新键写过时背景不透明度以新键为准`() {
        rawPreferences().edit()
            .putInt("lyric_overlay_backdrop", 2) // 旧值 89%
            .putInt("lyric_overlay_bg_alpha_percent", 25)
            .commit()
        Prefs.clearCacheForTest()
        assertEquals(25, Prefs(context).lyricOverlayBgAlphaPercent)
    }

    // ------------------------------------------------------------ 侧钮无障碍：布尔 → 三档

    /**
     * v1.2.38 及以前，侧钮上的「无障碍」只有布尔开/关（`overlay_handle_a11y`）。
     * 2026-10-31 升级成三档（0 不显示 / 1 仅未运行时显示 / 2 常显）。
     *
     * 迁移口径与本节其它几条一致：**读时换算，不写回**。老值 true 对应「常显」
     * （升级前就是这个观感），false 对应「不显示」。
     */
    @Test
    fun `旧布尔 true 迁移为常显，false 迁移为不显示`() {
        assertEquals(
            SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE,
            prefs.overlayHandleA11yMode,
        )

        rawPreferences().edit().putBoolean("overlay_handle_a11y", true).commit()
        assertEquals(2, prefs.overlayHandleA11yMode)

        rawPreferences().edit().putBoolean("overlay_handle_a11y", false).commit()
        assertEquals(0, prefs.overlayHandleA11yMode)
    }

    /**
     * 新键一旦写过就以它为准 —— 否则用户改过三档、下次又被老布尔翻回去，
     * 表现是「设置自己变回去了」。
     */
    @Test
    fun `新键优先于旧布尔`() {
        rawPreferences().edit()
            .putBoolean("overlay_handle_a11y", true)
            .putInt("overlay_handle_a11y_mode", 1)
            .commit()
        assertEquals("仅未运行时显示", 1, prefs.overlayHandleA11yMode)
    }

    /** 越界写入夹取到 0..2，别把非法值漏进服务里的 when 分支。 */
    @Test
    fun `三档写入越界会被夹取`() {
        prefs.overlayHandleA11yMode = 9
        assertEquals(2, prefs.overlayHandleA11yMode)
        prefs.overlayHandleA11yMode = -3
        assertEquals(0, prefs.overlayHandleA11yMode)
    }
}
