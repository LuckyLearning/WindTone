package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页「一主六子」结构（v1.1.3 二次重构）的冒烟回归。
 *
 * 为什么值得单独测：主页从「平铺所有开关」改成「六张二级分类卡」之后，
 * **唯一的二级入口就是这六张卡** —— 卡片渲染不出来、或者点卡没进对应的子页，
 * 那些设置就等于从界面上消失了，而编译 / 打包 / lint 全都不会报。
 * 这正是本项目踩过的「功能静默隐身」那一类故障（对照指南 §11.12 的多选入口）。
 *
 * 断言的是**行为与结构**（六张卡在、点卡通到对应子页、返回能回主页），
 * 不锁具体像素与视觉细节（那些看效果图）。
 *
 * ⚠️ Robolectric 三个坑见 [LibraryDrawerTest] 的类注释：
 * 必须给窗口尺寸、必须开 NATIVE 图形模式、一个测试只能调一次 `setContent`。
 * 这里窗口给到 800dp 高，免得第六张卡落在折叠线下面导致 `assertIsDisplayed()` 失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** SettingsScreen 是「数据进、回调出」的纯展示组件，素状态 + 空回调即可渲染 */
    private fun render() {
        compose.setContent {
            MusicPlayerTheme {
                SettingsScreen(
                    library = LibraryUiState(),
                    player = PlayerUiState(),
                    update = UpdateUiState(),
                    dynamicTheme = true,
                    autoCheckUpdate = true,
                    downloadRoute = DownloadRoute.AUTO,
                    crashLogCount = 0,
                    crashLogText = null,
                    onClose = {},
                    onDynamicTheme = {},
                    onCarMode = {},
                    onLyricOverlay = {},
                    onLyricOverlayLines = {},
                    onLyricOverlayTextScale = {},
                    onLyricOverlayAlign = {},
                    onLyricOverlayBackdrop = {},
                    onLyricOverlayPauseMode = {},
                    onOverlayEditing = {},
                    onOverlayPreview = {},
                    canDrawOverlay = false,
                    overlayHealth = LyricOverlayBus.Health(),
                    onLyricScale = {},
                    onLyricWordByWord = {},
                    onSpeed = {},
                    onSkipSilence = {},
                    onClearStats = {},
                    maintenance = null,
                    onRefreshMaintenance = {},
                    onCleanupMissingSongs = {},
                    onClearCoverCache = {},
                    onResetUserData = {},
                    onResetLibrary = {},
                    onRestoreExcluded = {},
                    onOpenReport = {},
                    onOpenDuplicates = {},
                    minSongDurationSec = 60,
                    onMinSongDurationSec = {},
                    onLoadCrashLog = {},
                    onExportCrashLog = {},
                    onClearCrashLog = {},
                    onCheckUpdate = {},
                    onDownloadUpdate = {},
                    onInstallUpdate = {},
                    onGrantInstallPermission = {},
                    onAutoCheckUpdate = {},
                    onDownloadRoute = {},
                    onOpenReleasePage = {},
                    onIgnoreUpdate = {},
                    onDismissUpdateDialog = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** 主页 = 六张分类卡，一张不多一张不少 */
    @Test
    fun rootShowsSixCategoryCards() {
        render()
        listOf("外观", "播放", "歌词", "音乐库", "更新", "关于与诊断").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // 分类卡与子页里的 ActionRow 的区别就在这一行摘要（连摘要一起断言，
        // 免得以后退回成没有摘要的普通 ActionRow 也照样"通过"）
        compose.onNodeWithText("主题取色 · 车载模式").assertIsDisplayed()
        compose.onNodeWithText("过滤 < 60 秒").assertIsDisplayed()
    }

    /** 点「外观」→ 进外观子页（主题 / 车载两段），主页的卡整批让位 */
    @Test
    fun appearanceCardOpensAppearancePage() {
        render()
        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("主题跟随封面取色").assertIsDisplayed()
        compose.onNodeWithText("车载").assertIsDisplayed()
        compose.onNodeWithText("关于与诊断").assertDoesNotExist()
    }

    /** 点「歌词」→ 进歌词子页；按返回键回到主页（六张卡回来） */
    @Test
    fun lyricsCardOpensSubPageAndBackReturnsToRoot() {
        render()
        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("播放页歌词").assertIsDisplayed()
        compose.onNodeWithText("关于与诊断").assertDoesNotExist()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("关于与诊断").assertIsDisplayed()
        compose.onNodeWithText("播放页歌词").assertDoesNotExist()
    }
}
