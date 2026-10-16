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
        check("dynamicTheme", prefs.dynamicTheme, SettingsDefaults.DYNAMIC_THEME)
        check("carMode", prefs.carMode, SettingsDefaults.CAR_MODE)

        // 播放
        check("playbackSpeed", prefs.playbackSpeed, SettingsDefaults.SPEED)
        check("playbackPitchSemitones", prefs.playbackPitchSemitones, SettingsDefaults.PITCH_SEMITONES)
        check("skipSilence", prefs.skipSilence, SettingsDefaults.SKIP_SILENCE)

        // 播放页歌词
        check("lyricScale", prefs.lyricScale, SettingsDefaults.LYRIC_SCALE)
        check("lyricWordByWord", prefs.lyricWordByWord, SettingsDefaults.LYRIC_WORD_BY_WORD)
        check("lyricSungColor", prefs.lyricSungColor, SettingsDefaults.LYRIC_SUNG_COLOR)

        // 桌面悬浮歌词
        check("lyricOverlay", prefs.lyricOverlay, SettingsDefaults.LYRIC_OVERLAY)
        check("lyricOverlayLines", prefs.lyricOverlayLines, SettingsDefaults.OVERLAY_LINES)
        check("lyricOverlayTextScale", prefs.lyricOverlayTextScale, SettingsDefaults.OVERLAY_TEXT_SCALE)
        check("lyricOverlayAlign", prefs.lyricOverlayAlign, SettingsDefaults.OVERLAY_ALIGN)
        check("lyricOverlayBackdrop", prefs.lyricOverlayBackdrop, SettingsDefaults.OVERLAY_BACKDROP)
        check("lyricOverlayPauseMode", prefs.lyricOverlayPauseMode, SettingsDefaults.OVERLAY_PAUSE_MODE)
        check("lyricOverlayWidthPercent", prefs.lyricOverlayWidthPercent, SettingsDefaults.OVERLAY_WIDTH_PERCENT)
        check("lyricOverlayTextColor", prefs.lyricOverlayTextColor, SettingsDefaults.OVERLAY_TEXT_COLOR)
        check("lyricOverlaySungColor", prefs.lyricOverlaySungColor, SettingsDefaults.OVERLAY_SUNG_COLOR)

        // 音乐库 / 更新
        check("minSongDurationSec", prefs.minSongDurationSec, SettingsDefaults.MIN_DURATION_SEC)
        check("autoCheckUpdate", prefs.autoCheckUpdate, SettingsDefaults.AUTO_UPDATE)
        check("updateDownloadRoute", prefs.updateDownloadRoute, SettingsDefaults.UPDATE_ROUTE)

        assertTrue(
            "出厂默认值出现了两处定义（Prefs getter 与 SettingsDefaults 不一致）：\n" +
                drift.joinToString("\n"),
            drift.isEmpty(),
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
    }

    private fun rawPreferences() =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
}
