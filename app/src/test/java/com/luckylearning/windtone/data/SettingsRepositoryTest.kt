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
 * [SettingsRepository] —— 设置写入的唯一接缝。
 *
 * 这些用例钉两件事：
 *  1. **落盘**：写完必须能通过 `Prefs` 原样读回来（否则就是「界面变了、重启没了」）；
 *  2. **返回值 = 真正生效的值**：调用方拿返回值更新界面状态，所以越界写入时
 *     返回的必须是夹取后的值，而不是调用方传进来的原值。
 *
 * 用 Robolectric：`Prefs` 内部要 `getSharedPreferences`，纯 JVM 桩拿不到。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 前提是「一个键都没写过」，先清一次，不依赖 Robolectric 沙箱是否干净
        raw().edit().clear().commit()
        prefs = Prefs(context)
        settings = SettingsRepository(prefs)
    }

    @After
    fun tearDown() {
        raw().edit().clear().commit()
    }

    private fun raw() = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 速度 / 变调

    @Test
    fun `setSpeed 夹取到合法区间并落盘`() {
        assertEquals(0.5f, settings.setSpeed(0.1f), 1e-6f)
        assertEquals(0.5f, prefs.playbackSpeed, 1e-6f)

        assertEquals(2.0f, settings.setSpeed(9.9f), 1e-6f)
        assertEquals(2.0f, prefs.playbackSpeed, 1e-6f)

        assertEquals(1.5f, settings.setSpeed(1.5f), 1e-6f)
        assertEquals(1.5f, prefs.playbackSpeed, 1e-6f)
    }

    @Test
    fun `setPitchSemitones 原样落盘（区间由播放域 Pitch 权威定义）`() {
        // 仓库不二次夹取：调用方已按 Pitch 的区间夹过，这里只负责落盘
        settings.setPitchSemitones(-7)
        assertEquals(-7, prefs.playbackPitchSemitones)

        settings.setPitchSemitones(12)
        assertEquals(12, prefs.playbackPitchSemitones)
    }

    @Test
    fun `布尔类设置落盘后可读回`() {
        settings.setSkipSilence(true)
        settings.setAutoResume(false)
        settings.setAutoPlayOnBoot(true)
        settings.setDynamicTheme(false)
        settings.setForegroundLogEnabled(true)
        settings.setDebugUnlocked(true)
        settings.setAutoCheckUpdate(false)
        settings.setOverlayEnabled(true)
        settings.setOverlayFullHeight(true)
        settings.setOverlayShowTitle(true)
        settings.setLyricsPaneVisible(false)
        settings.setCarMode(true)

        assert(prefs.skipSilence)
        assert(!prefs.autoResume)
        assert(prefs.autoPlayOnBoot)
        assert(!prefs.dynamicTheme)
        assert(prefs.foregroundLogEnabled)
        assert(prefs.debugUnlocked)
        assert(!prefs.autoCheckUpdate)
        assert(prefs.lyricOverlay)
        assert(prefs.lyricOverlayFullHeight)
        assert(prefs.lyricOverlayShowTitle)
        assert(!prefs.lyricsPaneVisible)
        assert(prefs.carMode)
    }

    // ---------------------------------------------------------------- 夹取返回值

    @Test
    fun `setOverlayLines 越界返回夹取后的值`() {
        val range = SettingsDefaults.OVERLAY_LINES_RANGE
        assertEquals(range.first, settings.setOverlayLines(-5))
        assertEquals(range.last, settings.setOverlayLines(99))
        // 落盘的也必须是夹取后的值，否则重启后读回一个越界数
        assertEquals(range.last, prefs.lyricOverlayLines)
    }

    @Test
    fun `字号类设置越界夹到各自区间`() {
        // 正文 12..60sp、标题 40..100%
        assertEquals(SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last, settings.setOverlayTextSp(999))
        assertEquals(SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first, settings.setOverlayTextSp(-1))
        assertEquals(SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.last, settings.setOverlayTitlePercent(999))
        assertEquals(SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.first, settings.setOverlayTitlePercent(0))
        // 落盘的也必须是夹取后的值，否则重启后读回一个越界数
        // （最后一次写的是下界：正文 -1 → 12，标题 0 → 40）
        assertEquals(SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first, prefs.lyricOverlayTextSp)
        assertEquals(SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.first, prefs.lyricOverlayTitlePercent)
    }

    @Test
    fun `两档开关类设置越界夹到 0到1`() {
        assertEquals(1, settings.setOverlayPauseMode(4))
        assertEquals(1, settings.setOverlayAlign(2))
        assertEquals(0, settings.setOverlayAlign(-3))
        assertEquals(1, prefs.lyricOverlayPauseMode)
        assertEquals(0, prefs.lyricOverlayAlign)
    }

    @Test
    fun `宽度与偏移百分比各自夹到自己的区间`() {
        assertEquals(1, settings.setOverlayWidthPercent(0))
        assertEquals(100, settings.setOverlayWidthPercent(1000))
        assertEquals(
            SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first,
            settings.setOverlayFullWidthPercent(-100),
        )
        assertEquals(
            SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last,
            settings.setOverlayFullWidthPercent(1000),
        )
        assertEquals(0, settings.setOverlayFullOffsetPercent(-1))
        assertEquals(100, settings.setOverlayFullOffsetPercent(101))
    }

    @Test
    fun `setLyricBilingual 夹到 0到2`() {
        assertEquals(2, settings.setLyricBilingual(9))
        assertEquals(0, settings.setLyricBilingual(-1))
        assertEquals(1, settings.setLyricBilingual(1))
        assertEquals(1, prefs.lyricBilingual)
    }

    // ---------------------------------------------------------------- 集合与颜色

    @Test
    fun `隐藏应用名单落盘后可读回`() {
        val apps = setOf("com.a", "com.b")
        settings.setOverlayHiddenApps(apps)
        assertEquals(apps, prefs.lyricOverlayHiddenApps)
    }

    @Test
    fun `文字颜色与歌词缩放等原样落盘`() {
        val color = 0xFF123456.toInt()
        settings.setOverlayTextColor(color)
        assertEquals(color, prefs.lyricOverlayTextColor)

        settings.setLyricScale(1.4f)
        assertEquals(1.4f, prefs.lyricScale, 1e-6f)

        settings.setLyricTapSeeks(true)
        assert(prefs.lyricTapSeeks)
    }

    @Test
    fun `样式补全各项越界夹到自己的区间并落盘`() {
        // v1.1.52 十项：圆角 / 边框宽高与颜色 / 行距 / 当前行色 / 压暗 / 描边宽色 / 动画 / 标题内容
        assertEquals(SettingsDefaults.OVERLAY_CORNER_RADIUS_RANGE.last, settings.setLyricOverlayCornerRadiusDp(99))
        assertEquals(SettingsDefaults.OVERLAY_BORDER_WIDTH_RANGE.first, settings.setLyricOverlayBorderWidthDp(-1))
        assertEquals(SettingsDefaults.OVERLAY_LINE_SPACING_RANGE.last, settings.setLyricOverlayLineSpacingDp(99))
        assertEquals(SettingsDefaults.OVERLAY_DIM_RANGE.first, settings.setLyricOverlayDimPercent(1))
        assertEquals(SettingsDefaults.OVERLAY_STROKE_WIDTH_RANGE.last, settings.setLyricOverlayStrokeWidthDp(50))
        assertEquals(SettingsDefaults.OVERLAY_FADE_RANGE.first, settings.setLyricOverlayFadeMs(-1))
        assertEquals(2, settings.setLyricOverlayTitleMode(9))
        assertEquals(0, settings.setLyricOverlayTitleMode(-1))
        // 落盘的也必须是夹取后的值（最后一次写的是下界 / 0 档）
        assertEquals(SettingsDefaults.OVERLAY_CORNER_RADIUS_RANGE.last, prefs.lyricOverlayCornerRadiusDp)
        assertEquals(SettingsDefaults.OVERLAY_BORDER_WIDTH_RANGE.first, prefs.lyricOverlayBorderWidthDp)
        assertEquals(SettingsDefaults.OVERLAY_LINE_SPACING_RANGE.last, prefs.lyricOverlayLineSpacingDp)
        assertEquals(SettingsDefaults.OVERLAY_DIM_RANGE.first, prefs.lyricOverlayDimPercent)
        assertEquals(SettingsDefaults.OVERLAY_STROKE_WIDTH_RANGE.last, prefs.lyricOverlayStrokeWidthDp)
        assertEquals(SettingsDefaults.OVERLAY_FADE_RANGE.first, prefs.lyricOverlayFadeMs)
        assertEquals(0, prefs.lyricOverlayTitleMode)
    }

    @Test
    fun `样式补全的颜色项原样落盘`() {
        val current = 0xFFFF8800.toInt()
        val stroke = 0xFF000000.toInt()
        val border = 0x66FFFFFF.toInt()
        settings.setLyricOverlayCurrentColor(current)
        settings.setLyricOverlayStrokeColor(stroke)
        settings.setLyricOverlayBorderColor(border)
        assertEquals(current, prefs.lyricOverlayCurrentColor)
        assertEquals(stroke, prefs.lyricOverlayStrokeColor)
        assertEquals(border, prefs.lyricOverlayBorderColor)
    }

    @Test
    fun `仓库与 prefs 直通读是同一份存储`() {
        // 门面不复制状态：settings.prefs 就是构造时传入的那个实例
        assert(settings.prefs === prefs)
        settings.setOverlayEnabled(true)
        assert(settings.prefs.lyricOverlay)
    }
}
