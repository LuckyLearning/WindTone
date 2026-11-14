package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 悬浮窗外观的白天 / 夜间两套（v1.2.52）—— 经 [Prefs] 验证 [OverlayStylePrefs] 的分流。
 *
 * ## 为什么不直接测包装器
 * 包装器的全部价值在于「上层不用改」，所以真正要守的是 **`Prefs` 的公开属性**：
 * 切到夜间改字号，白天那套必须原封不动；夜间没写过的项必须继承白天；
 * 清掉夜间自有值后必须重新跟随白天。这些断言如果只测包装器，
 * 一旦有哪个 getter 绕过了 `sp`（比如新写的代码直接拿 `raw`），测试就看不见。
 *
 * ## 为什么用主题模式切作用域而不是改系统 uiMode
 * `ThemeMode.LIGHT / DARK` 能让「该用哪套」确定下来，与系统当前明暗无关 ——
 * Robolectric 的夜间限定符要在建 Application 之前设置，改起来很别扭，
 * 而分支本身已在 [OverlayStyleScopeTest] 里覆盖过（`SYSTEM + uiMode`）。
 */
@RunWith(RobolectricTestRunner::class)
class OverlayStylePrefsTest {

    private lateinit var context: Context
    private lateinit var raw: SharedPreferences
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        raw = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
        // 前提是「一个键都没写过」：不依赖 Robolectric 给的沙箱是否干净
        raw.edit().clear().commit()
        Prefs.clearCacheForTest()
        prefs = Prefs(context)
        prefs.themeMode = ThemeMode.LIGHT
    }

    @Test
    fun `夜间写的值只落在 d_ 键上`() {
        prefs.themeMode = ThemeMode.DARK
        prefs.lyricOverlayTextSp = 44

        assertEquals(44, raw.getInt("d_lyric_overlay_text_sp", -1))
        assertEquals(-1, raw.getInt("lyric_overlay_text_sp", -1))
    }

    @Test
    fun `白天写的值不碰夜间那套`() {
        prefs.themeMode = ThemeMode.DARK
        prefs.lyricOverlayTextSp = 44
        prefs.themeMode = ThemeMode.LIGHT
        prefs.lyricOverlayTextSp = 30

        assertEquals(30, raw.getInt("lyric_overlay_text_sp", -1))
        assertEquals(44, raw.getInt("d_lyric_overlay_text_sp", -1))
    }

    @Test
    fun `夜间没写过的项继承白天`() {
        prefs.lyricOverlayTextSp = 30
        prefs.lyricOverlayBgAlphaPercent = 80

        prefs.themeMode = ThemeMode.DARK
        assertEquals(30, prefs.lyricOverlayTextSp)
        assertEquals(80, prefs.lyricOverlayBgAlphaPercent)
        // 继承是**读时**回落的，不该把白天的值抄进夜间键
        assertFalse(raw.contains("d_lyric_overlay_text_sp"))
    }

    @Test
    fun `夜间写过的那项不再跟随白天`() {
        prefs.lyricOverlayTextSp = 30
        prefs.lyricOverlayBgAlphaPercent = 80

        prefs.themeMode = ThemeMode.DARK
        prefs.lyricOverlayTextSp = 44

        // 字号已自成一套，底板仍在跟随
        assertEquals(44, prefs.lyricOverlayTextSp)
        assertEquals(80, prefs.lyricOverlayBgAlphaPercent)

        // 改白天只影响「还跟着」的那项
        prefs.themeMode = ThemeMode.LIGHT
        prefs.lyricOverlayTextSp = 20
        prefs.lyricOverlayBgAlphaPercent = 30
        prefs.themeMode = ThemeMode.DARK
        assertEquals(44, prefs.lyricOverlayTextSp)
        assertEquals(30, prefs.lyricOverlayBgAlphaPercent)
    }

    @Test
    fun `行为类键只有一套`() {
        prefs.themeMode = ThemeMode.DARK
        prefs.lyricOverlay = true
        prefs.lyricOverlayFullHeight = true
        prefs.lyricOverlayDisplayId = 2
        prefs.lyricOverlayHiddenApps = setOf("com.example.nav")

        // 这些键夜间也写原键：夜里悬浮窗不能自己消失、也不能跑到别的屏上
        assertTrue(raw.getBoolean("lyric_overlay", false))
        assertTrue(raw.getBoolean("lyric_overlay_full_height", false))
        assertEquals(2, raw.getInt("lyric_overlay_display_id", -1))
        assertFalse(raw.contains("d_lyric_overlay"))
        assertFalse(raw.contains("d_lyric_overlay_display_id"))
    }

    @Test
    fun `复制白天到夜间后夜间自成一套`() {
        prefs.lyricOverlayTextSp = 30
        prefs.lyricOverlayBgAlphaPercent = 80

        prefs.copyOverlayStyleToDark()
        assertTrue(prefs.overlayStyleHasOwnDark())

        prefs.themeMode = ThemeMode.DARK
        prefs.lyricOverlayTextSp = 44
        prefs.themeMode = ThemeMode.LIGHT
        prefs.lyricOverlayTextSp = 20
        prefs.lyricOverlayBgAlphaPercent = 10
        prefs.themeMode = ThemeMode.DARK

        // 复制过的两项都不再跟随白天
        assertEquals(44, prefs.lyricOverlayTextSp)
        assertEquals(80, prefs.lyricOverlayBgAlphaPercent)
    }

    @Test
    fun `清掉夜间自有值后重新跟随白天`() {
        prefs.lyricOverlayTextSp = 30
        prefs.themeMode = ThemeMode.DARK
        prefs.lyricOverlayTextSp = 44
        assertTrue(prefs.overlayStyleHasOwnDark())

        prefs.clearOverlayDarkStyle()

        assertFalse(prefs.overlayStyleHasOwnDark())
        assertEquals(30, prefs.lyricOverlayTextSp)
    }

    @Test
    fun `旧档位迁移在夜间也跟随白天的换算结果`() {
        // 老用户：只写过 0/1/2 档（这是 v1.1.50 之前的数据）
        raw.edit().putInt("lyric_overlay_text_scale", 2).commit()

        prefs.themeMode = ThemeMode.DARK
        assertEquals(SettingsDefaults.OVERLAY_TEXT_SP_BY_SCALE[2], prefs.lyricOverlayTextSp)
    }

    @Test
    fun `系统明暗靠配置回调喂入而非读资源`() {
        prefs.themeMode = ThemeMode.SYSTEM
        // 配置回调里拿到的 uiMode（夜间）：只有它算数，Resources 里还是旧值也不影响
        prefs.setOverlayUiMode(0x20)
        prefs.lyricOverlayTextSp = 44
        assertEquals(44, raw.getInt("d_lyric_overlay_text_sp", -1))

        // 切回浅色：夜间那套的值不该串到白天
        prefs.setOverlayUiMode(0x10)
        assertEquals(SettingsDefaults.OVERLAY_TEXT_SP, prefs.lyricOverlayTextSp)
        // 再切回夜间，值还在
        prefs.setOverlayUiMode(0x20)
        assertEquals(44, prefs.lyricOverlayTextSp)
    }

    @Test
    fun `输出屏幕默认主屏且可持久化`() {
        assertEquals(SettingsDefaults.OVERLAY_DISPLAY_ID, prefs.lyricOverlayDisplayId)
        prefs.lyricOverlayDisplayId = 3
        // 新建实例（模拟重启）后仍在
        Prefs.clearCacheForTest()
        assertEquals(3, Prefs(context).lyricOverlayDisplayId)
    }

    @Test
    fun `双套只覆盖外观类键`() {
        // 行为键绝不能进 STYLE_KEYS：进了就是「夜里悬浮窗自己消失」这类事故
        val behavior = listOf(
            Prefs.KEY_LYRIC_OVERLAY,
            Prefs.KEY_LYRIC_OVERLAY_DISPLAY_ID,
            Prefs.KEY_LYRIC_OVERLAY_HIDDEN_APPS,
            Prefs.KEY_LYRIC_OVERLAY_FULL_HEIGHT,
            Prefs.KEY_OVERLAY_HANDLE_COLLAPSE,
            Prefs.KEY_OVERLAY_HANDLE_PLAY_PAUSE,
            Prefs.KEY_OVERLAY_HANDLE_PLAYER,
            Prefs.KEY_OVERLAY_HANDLES,
            Prefs.KEY_OVERLAY_HANDLE_ORDER,
        )
        behavior.forEach { key ->
            assertFalse("行为键不该分两套：$key", key in Prefs.STYLE_KEYS)
        }
        // 外观键的代表项必须在内（漏一个 = 那项夜里改了不生效）
        listOf(
            Prefs.KEY_LYRIC_OVERLAY_TEXT_SP,
            Prefs.KEY_LYRIC_OVERLAY_BG_COLOR,
            Prefs.KEY_LYRIC_OVERLAY_BG_ALPHA,
            Prefs.KEY_LYRIC_OVERLAY_TEXT_COLOR,
            Prefs.KEY_LYRIC_OVERLAY_WIDTH,
            Prefs.KEY_LYRIC_OVERLAY_X_PCT,
            Prefs.KEY_LYRIC_OVERLAY_FULL_Y_PCT,
        ).forEach { key ->
            assertTrue("外观键应分两套：$key", key in Prefs.STYLE_KEYS)
        }
    }

    /**
     * v1.2.53：明暗要按**输出屏**判，不能永远读应用所在屏。
     *
     * 真机表现就是这条的反面：歌词投到副驾屏后，车机切夜间，中控（应用所在屏）
     * 也收到了一次配置回调，但副屏没被问到 —— 悬浮窗停在白天那套。
     */
    @Test
    fun `输出到副屏时明暗取副屏配置并随换屏切换`() {
        prefs.themeMode = ThemeMode.SYSTEM
        prefs.setOverlayUiMode(0x10) // 应用所在屏：白天
        prefs.setOverlayDisplayUiMode(0x20) // 输出屏：夜间

        prefs.lyricOverlayDisplayId = 0
        assertFalse("主屏输出时应跟随应用配置", prefs.overlayStyleIsDark())

        prefs.lyricOverlayDisplayId = 7
        assertTrue("副屏输出时应跟随副屏配置", prefs.overlayStyleIsDark())

        prefs.lyricOverlayDisplayId = 0
        assertFalse("回到主屏后不能再按副屏配置取外观", prefs.overlayStyleIsDark())
    }

    @Test
    fun `描边开关属于外观键并分两套`() {
        assertTrue(
            "描边开关是外观项，夜里关掉不该影响白天",
            Prefs.KEY_LYRIC_OVERLAY_STROKE_ON in Prefs.STYLE_KEYS,
        )
        prefs.themeMode = ThemeMode.LIGHT
        prefs.lyricOverlayStrokeEnabled = false
        prefs.themeMode = ThemeMode.DARK
        assertFalse("夜间没写过就继承白天（白天已关）", prefs.lyricOverlayStrokeEnabled)
        prefs.lyricOverlayStrokeEnabled = true
        prefs.themeMode = ThemeMode.LIGHT
        assertFalse("夜间单独开过，不影响白天", prefs.lyricOverlayStrokeEnabled)
    }

    /**
     * v1.2.54：标题块与分角色样式同样分两套。
     *
     * 这里挑「位置」与「其他行字号」做代表：位置是离散档位（写错会静默落到 0），
     * 字号是数值（写错会被夹取成边界值），两者都能覆盖「分流没接上」的典型症状。
     */
    @Test
    fun `标题位置与分角色字号分两套`() {
        listOf(
            Prefs.KEY_LYRIC_OVERLAY_TITLE_POS,
            Prefs.KEY_LYRIC_OVERLAY_TITLE_ALIGN,
            Prefs.KEY_LYRIC_OVERLAY_TITLE_C,
            Prefs.KEY_LYRIC_OVERLAY_TITLE_STROKE_ON,
            Prefs.KEY_LYRIC_OVERLAY_CURRENT_SCALE,
            Prefs.KEY_LYRIC_OVERLAY_CONTEXT_SCALE,
            Prefs.KEY_LYRIC_OVERLAY_CONTEXT_C,
        ).forEach { key ->
            assertTrue("外观键应分两套：$key", key in Prefs.STYLE_KEYS)
        }

        prefs.themeMode = ThemeMode.LIGHT
        prefs.lyricOverlayTitlePos = 1
        prefs.lyricOverlayContextScale = 140
        prefs.themeMode = ThemeMode.DARK
        assertEquals("夜间没写过就继承白天", 1, prefs.lyricOverlayTitlePos)
        assertEquals("夜间没写过就继承白天", 140, prefs.lyricOverlayContextScale)

        prefs.lyricOverlayTitlePos = 0
        prefs.lyricOverlayContextScale = 80
        prefs.themeMode = ThemeMode.LIGHT
        assertEquals("夜间单独改过，不影响白天", 1, prefs.lyricOverlayTitlePos)
        assertEquals("夜间单独改过，不影响白天", 140, prefs.lyricOverlayContextScale)
    }
}
