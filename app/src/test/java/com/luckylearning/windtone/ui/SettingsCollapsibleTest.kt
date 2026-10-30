package com.luckylearning.windtone.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.Pitch
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「速度与变调」默认收起（2026-10-30 用户要求）。
 *
 * 为什么值得测：这两块滑块原来常驻在设置页最上方，滚动时手指一蹭就改值，而改完
 * 当首听不出区别 —— 属于「改了却不知道为什么声音变了」那一类。收起后**唯一**能证明
 * 它真的收起了的证据，就是「默认状态下拖动条不在树里」；只断言标题在，收起与否都绿。
 *
 * ⚠️ 与 [SettingsScreenTest] 同一套 Robolectric 约束：给窗口尺寸、开 NATIVE 图形模式、
 * 一个测试只调一次 `setContent`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCollapsibleTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    /** 摘要行文案，跟着当前值走（收起态唯一能看到的东西） */
    private fun summary(speed: Float, pitch: Int): String =
        ctx.getString(
            R.string.speed_and_pitch_summary,
            "%.2f".format(speed),
            Pitch.labelOf(pitch),
        )

    private fun renderPlayback(speed: Float = 1f, pitch: Int = 0) {
        compose.setContent {
            MusicPlayerTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsPlaybackPage(
                        player = PlayerUiState(speed = speed, pitchSemitones = pitch),
                        onSpeed = {},
                        onPitch = {},
                        onSkipSilence = {},
            onSkipIntroSeconds = {},
            onSkipOutroSeconds = {},
                        onAutoResume = {},
                        onAutoPlayOnBoot = {},
                    )
                }
            }
        }
    }

    @Test
    fun `默认收起，两个滑块都不在界面上`() {
        renderPlayback()

        // 收起态：摘要行在，两条滑块行不在
        compose.onNodeWithText(summary(1f, 0)).assertIsDisplayed()
        compose
            .onNodeWithText(ctx.getString(R.string.default_speed_x, "1.00"))
            .assertDoesNotExist()
        compose
            .onNodeWithText(ctx.getString(R.string.pitch, Pitch.labelOf(0)))
            .assertDoesNotExist()
    }

    @Test
    fun `点标题才展开两个滑块`() {
        renderPlayback()

        compose.onNodeWithText(summary(1f, 0)).performClick()

        compose
            .onNodeWithText(ctx.getString(R.string.default_speed_x, "1.00"))
            .assertIsDisplayed()
        compose
            .onNodeWithText(ctx.getString(R.string.pitch, Pitch.labelOf(0)))
            .assertIsDisplayed()
    }

    @Test
    fun `摘要跟着当前值走，收起也能看出被改过`() {
        // 1.25x / 升 2 个半音：收起态下用户要能看出「不是我设的」
        renderPlayback(speed = 1.25f, pitch = 2)

        val expected = summary(1.25f, 2)
        compose.onNodeWithText(expected).assertIsDisplayed()
        // 摘要里必须同时有两个值，缺一个就等于「看不出变调被动过」
        assertEquals("1.25x · +2 半音", expected)
    }

    /** A7：假开关已移除，设置页只留「始终开启」说明行 */
    @Test
    fun `无缝播放诚实化_只读说明无开关`() {
        renderPlayback()
        compose.onNodeWithText(ctx.getString(R.string.gapless_playback)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.gapless_playback_always_on)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.gapless_playback_subtitle)).assertIsDisplayed()
        // 点开说明弹窗；用弹窗独有短句（副标题没有「假开关」）
        compose.onNodeWithText(ctx.getString(R.string.gapless_playback)).performClick()
        compose.onNodeWithText("假开关", substring = true).assertIsDisplayed()
    }
}
