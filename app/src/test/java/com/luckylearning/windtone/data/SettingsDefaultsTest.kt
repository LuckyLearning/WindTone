package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「出厂默认值」只能有一份定义（[SettingsDefaults]）—— 这条测试负责守住它。
 *
 * ## 为什么必须单独守
 *
 * v1.1.4 给每个可调项加了「恢复默认」按钮，按钮写回的是 [SettingsDefaults] 里的常量。
 * 于是「默认值」有了两个来源：
 *  ① `Prefs` 各 getter 里的 `sp.getXxx(KEY, 默认值)`（**全新安装**拿到的东西）；
 *  ② `SettingsDefaults` 的常量（**点了 ↺** 拿到的东西）。
 *
 * 两处一旦漂移，症状是「新装的用户」和「恢复过默认的用户」行为对不上 ——
 * 不报错、不崩溃、日志里什么都没有，只靠人眼几乎发现不了。这正是本项目栽过的那一类问题
 * （对照指南 §11.10：正确性建立在「两处永远一致」上，而没有任何东西在守着它）。
 *
 * ## 为什么不逐个断言字面量
 *
 * 断言写死 `assertEquals(60, prefs.minSongDurationSec)` 只能证明「Prefs 没变」，
 * 证明不了「两处一致」。这里两边都读，比的是**它们彼此**：谁改了，立刻红。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDefaultsTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 前提是「一个键都没写过」——所以先清一次，不依赖 Robolectric 给的沙箱是不是干净的
        rawPreferences().edit().clear().commit()
        prefs = Prefs(context)
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
    }

    @Test
    fun prefsGettersAgreeWithSettingsDefaults() {
        val drift = mutableListOf<String>()
        fun check(name: String, actual: Any?, expected: Any?) {
            if (actual != expected) {
                drift += "$name：Prefs 读出 $actual，SettingsDefaults 写的是 $expected"
            }
        }

        // 外观
        check("themeMode", prefs.themeMode, SettingsDefaults.THEME_MODE)
        check("dynamicTheme", prefs.dynamicTheme, SettingsDefaults.DYNAMIC_THEME)
        check("materialYou", prefs.materialYou, SettingsDefaults.MATERIAL_YOU)
        check("carMode", prefs.carMode, SettingsDefaults.CAR_MODE)
        check("btAutoCarEnabled", prefs.btAutoCarEnabled, SettingsDefaults.BT_AUTO_CAR_ENABLED)
        check("btAutoCarAnyDevice", prefs.btAutoCarAnyDevice, SettingsDefaults.BT_AUTO_CAR_ANY_DEVICE)
        check("btAutoCarDevices", prefs.btAutoCarDevices, SettingsDefaults.BT_AUTO_CAR_DEVICES)
        check("btAutoCarRestore", prefs.btAutoCarRestore, SettingsDefaults.BT_AUTO_CAR_RESTORE)

        // 播放
        check("playbackSpeed", prefs.playbackSpeed, SettingsDefaults.SPEED)
        check("playbackPitchSemitones", prefs.playbackPitchSemitones, SettingsDefaults.PITCH_SEMITONES)
        check("skipSilence", prefs.skipSilence, SettingsDefaults.SKIP_SILENCE)
        check("skipIntroSeconds", prefs.skipIntroSeconds, SettingsDefaults.SKIP_INTRO_SECONDS)
        check("skipOutroSeconds", prefs.skipOutroSeconds, SettingsDefaults.SKIP_OUTRO_SECONDS)
        check("autoResume", prefs.autoResume, SettingsDefaults.AUTO_RESUME)
        check("autoPlayOnBoot", prefs.autoPlayOnBoot, SettingsDefaults.AUTO_PLAY_ON_BOOT)
        check("onlineCachePrefetch", prefs.onlineCachePrefetch, SettingsDefaults.ONLINE_CACHE_PREFETCH)
        check("onlineParallelDownload", prefs.onlineParallelDownload, SettingsDefaults.ONLINE_PARALLEL_DOWNLOAD)

        // 播放页歌词
        check("lyricScale", prefs.lyricScale, SettingsDefaults.LYRIC_SCALE)
        // v1.1.23：点歌词跳转默认关闭（车机上滑动歌词会被误判成点击而跳进度）
        check("lyricTapSeeks", prefs.lyricTapSeeks, SettingsDefaults.LYRIC_TAP_SEEKS)

        // 桌面悬浮歌词
        check("lyricOverlay", prefs.lyricOverlay, SettingsDefaults.LYRIC_OVERLAY)
        check("lyricOverlayDisplayId", prefs.lyricOverlayDisplayId, SettingsDefaults.OVERLAY_DISPLAY_ID)
        check("lyricOverlayLines", prefs.lyricOverlayLines, SettingsDefaults.OVERLAY_LINES)
        check("lyricOverlayTextSp", prefs.lyricOverlayTextSp, SettingsDefaults.OVERLAY_TEXT_SP)
        check("lyricOverlayTitlePercent", prefs.lyricOverlayTitlePercent, SettingsDefaults.OVERLAY_TITLE_PERCENT)
        check("lyricOverlayAlign", prefs.lyricOverlayAlign, SettingsDefaults.OVERLAY_ALIGN)
        check("lyricOverlayBgAlphaPercent", prefs.lyricOverlayBgAlphaPercent, SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT)
        check(
            "lyricOverlayStrokeEnabled",
            prefs.lyricOverlayStrokeEnabled,
            SettingsDefaults.OVERLAY_STROKE_ENABLED,
        )
        check("lyricOverlayPauseMode", prefs.lyricOverlayPauseMode, SettingsDefaults.OVERLAY_PAUSE_MODE)
        check("lyricOverlayWidthPercent", prefs.lyricOverlayWidthPercent, SettingsDefaults.OVERLAY_WIDTH_PERCENT)
        check("lyricOverlayFullHeight", prefs.lyricOverlayFullHeight, SettingsDefaults.OVERLAY_FULL_HEIGHT)
        check(
            "lyricOverlayFullWidthPercent",
            prefs.lyricOverlayFullWidthPercent,
            SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT,
        )
        check(
            "lyricOverlayFullOffsetPercent",
            prefs.lyricOverlayFullOffsetPercent,
            SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT,
        )
        check("lyricOverlayTextColor", prefs.lyricOverlayTextColor, SettingsDefaults.OVERLAY_TEXT_COLOR)
        // v1.2.54：标题块与分角色样式
        check("lyricOverlayTitlePos", prefs.lyricOverlayTitlePos, SettingsDefaults.OVERLAY_TITLE_POS)
        check("lyricOverlayTitleAlign", prefs.lyricOverlayTitleAlign, SettingsDefaults.OVERLAY_TITLE_ALIGN)
        check("lyricOverlayTitleColor", prefs.lyricOverlayTitleColor, SettingsDefaults.OVERLAY_TITLE_COLOR)
        check(
            "lyricOverlayTitleStrokeEnabled",
            prefs.lyricOverlayTitleStrokeEnabled,
            SettingsDefaults.OVERLAY_TITLE_STROKE_ON,
        )
        check(
            "lyricOverlayTitleStrokeWidthDp",
            prefs.lyricOverlayTitleStrokeWidthDp,
            SettingsDefaults.OVERLAY_TITLE_STROKE_WIDTH_DP,
        )
        check(
            "lyricOverlayTitleStrokeColor",
            prefs.lyricOverlayTitleStrokeColor,
            SettingsDefaults.OVERLAY_TITLE_STROKE_COLOR,
        )
        check("lyricOverlayTitleSp", prefs.lyricOverlayTitleSp, SettingsDefaults.OVERLAY_TITLE_SP)
        check("lyricOverlayCurrentSp", prefs.lyricOverlayCurrentSp, SettingsDefaults.OVERLAY_CURRENT_SP)
        check("lyricOverlayContextSp", prefs.lyricOverlayContextSp, SettingsDefaults.OVERLAY_CONTEXT_SP)
        check("lyricOverlayContextColor", prefs.lyricOverlayContextColor, SettingsDefaults.OVERLAY_CONTEXT_COLOR)
        check("lyricOverlayHiddenApps", prefs.lyricOverlayHiddenApps, SettingsDefaults.OVERLAY_HIDDEN_APPS)
        check("foregroundLogEnabled", prefs.foregroundLogEnabled, SettingsDefaults.FOREGROUND_LOG_ENABLED)
        // v1.1.31：调试分区出厂隐藏（关于连点 5 次才显示）
        check("debugUnlocked", prefs.debugUnlocked, SettingsDefaults.DEBUG_UNLOCKED)

        // 音乐库 / 更新
        check("minSongDurationSec", prefs.minSongDurationSec, SettingsDefaults.MIN_DURATION_SEC)
        check("scanBlocklist", prefs.scanBlocklist, SettingsDefaults.SCAN_BLOCKLIST)
        check("focusLossMode", prefs.focusLossMode, SettingsDefaults.FOCUS_LOSS_MODE)
        check("deviceSchemeEnabled", prefs.deviceSchemeEnabled, SettingsDefaults.DEVICE_SCHEME_ENABLED)
        check("deviceSchemeBindings", prefs.deviceSchemeBindings, SettingsDefaults.DEVICE_SCHEME_BINDINGS)
        check("deviceSchemeCurrent", prefs.deviceSchemeCurrent, SettingsDefaults.DEVICE_SCHEME_CURRENT)
        check("deviceStash", prefs.deviceStash, SettingsDefaults.DEVICE_STASH)
        check("queueSnapshots", prefs.queueSnapshots, SettingsDefaults.QUEUE_SNAPSHOTS)
        check("btCarLyric", prefs.btCarLyric, SettingsDefaults.BT_CAR_LYRIC)
        check("autoCheckUpdate", prefs.autoCheckUpdate, SettingsDefaults.AUTO_UPDATE)
        check("updateDownloadRoute", prefs.updateDownloadRoute, SettingsDefaults.UPDATE_ROUTE)

        // 音效方案与播放过渡（v1.1.52）
        check("soundSchemeId", prefs.soundSchemeId, SettingsDefaults.SOUND_SCHEME_ID)
        check("carSchemeId", prefs.carSchemeId, SettingsDefaults.CAR_SCHEME_ID)
        check("soundStash", prefs.soundStash, SettingsDefaults.SOUND_STASH)
        check("fadeEnabled", prefs.fadeEnabled, SettingsDefaults.FADE_ENABLED)
        check("fadeDurationMs", prefs.fadeDurationMs, SettingsDefaults.FADE_DURATION_MS)
        check("loudnessBalance", prefs.loudnessBalance, SettingsDefaults.LOUDNESS_BALANCE)
        check("replayGainMode", prefs.replayGainMode.name, SettingsDefaults.REPLAY_GAIN_MODE)
        check("eqUserPresets", prefs.eqUserPresets, SettingsDefaults.EQ_USER_PRESETS)
        check("softEqUserConfirm", prefs.softEqUserConfirm, SettingsDefaults.SOFT_EQ_USER_CONFIRM)
        check("stereoBalance", prefs.stereoBalance, SettingsDefaults.STEREO_BALANCE)

        assertTrue(
            "出厂默认值出现了两处定义（Prefs getter 与 SettingsDefaults 不一致）：\n" +
                drift.joinToString("\n"),
            drift.isEmpty(),
        )
    }

    /** 淡入淡出时长默认值必须落在自己的合法区间里，否则一读出来就被夹走 */
    @Test
    fun fadeDurationDefaultSitsInsideItsRange() {
        assertTrue(
            "FADE_DURATION_MS=${SettingsDefaults.FADE_DURATION_MS} 不在 " +
                "FADE_DURATION_RANGE=${SettingsDefaults.FADE_DURATION_RANGE} 里",
            SettingsDefaults.FADE_DURATION_MS in SettingsDefaults.FADE_DURATION_RANGE,
        )
    }

    /** 悬浮窗宽度的合法区间必须真的能装下默认值，否则默认值一读出来就被夹走 */
    @Test
    fun overlayWidthDefaultSitsInsideItsRange() {
        assertTrue(
            "OVERLAY_WIDTH_PERCENT=${SettingsDefaults.OVERLAY_WIDTH_PERCENT} 不在 " +
                "OVERLAY_WIDTH_RANGE=${SettingsDefaults.OVERLAY_WIDTH_RANGE} 里",
            SettingsDefaults.OVERLAY_WIDTH_PERCENT in SettingsDefaults.OVERLAY_WIDTH_RANGE,
        )
        // 全屏那套也一样：默认宽度 100% 与默认居中 50% 都必须落在各自区间内，
        // 否则新装用户一进设置页就看到「满宽却偏左 30%」这种自相矛盾的状态。
        assertTrue(
            "OVERLAY_FULL_WIDTH_PERCENT=${SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT} 不在 " +
                "OVERLAY_FULL_WIDTH_RANGE=${SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE} 里",
            SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT in SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE,
        )
        assertTrue(
            "OVERLAY_FULL_OFFSET_PERCENT=${SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT} 不在 " +
                "OVERLAY_FULL_OFFSET_RANGE=${SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE} 里",
            SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT in SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE,
        )
    }

    private fun rawPreferences() =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
}
