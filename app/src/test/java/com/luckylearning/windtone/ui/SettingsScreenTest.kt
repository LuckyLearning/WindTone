package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
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

    /**
     * settingsScreen 是「数据进、回调出」的纯展示组件，默认素状态 + 空回调即可渲染。
     *
     * 参数都带默认值，常见的三个场景（出厂默认 / 某项偏离 / 观察某个回调收到了什么）
     * 各写一行就够，不必每个测试重复五十个空 lambda。
     */
    private fun render(
        player: PlayerUiState = PlayerUiState(),
        dynamicTheme: Boolean = SettingsDefaults.DYNAMIC_THEME,
        onCarMode: (Boolean) -> Unit = {},
        onLyricScale: (Float) -> Unit = {},
        onLyricSungColor: (Int) -> Unit = {},
    ) {
        compose.setContent {
            MusicPlayerTheme {
                SettingsScreen(
                    library = LibraryUiState(),
                    player = player,
                    update = UpdateUiState(),
                    dynamicTheme = dynamicTheme,
                    autoCheckUpdate = true,
                    downloadRoute = DownloadRoute.AUTO,
                    crashLogCount = 0,
                    crashLogText = null,
                    onClose = {},
                    onDynamicTheme = {},
                    onCarMode = onCarMode,
                    onLyricOverlay = {},
                    onLyricOverlayLines = {},
                    onLyricOverlayTextScale = {},
                    onLyricOverlayAlign = {},
                    onLyricOverlayBackdrop = {},
                    onLyricOverlayPauseMode = {},
                    onLyricOverlayWidthPercent = {},
                    onLyricOverlayTextColor = {},
                    onLyricOverlaySungColor = {},
                    onOverlayEditing = {},
                    onOverlayPreview = {},
                    canDrawOverlay = false,
                    overlayHealth = LyricOverlayBus.Health(),
                    onLyricScale = onLyricScale,
                    onLyricWordByWord = {},
                    onLyricSungColor = onLyricSungColor,
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

    // ================================================================ v1.1.4 需求 1：恢复默认

    /**
     * 出厂默认下**一枚 ↺ 都不出现**。
     *
     * 「恢复默认」只在偏离默认时才是个可执行的动作。一行都没动过却挂着按钮，
     * 用户会以为「还得点一下才算生效」，等于把默认状态本身渲染成了一个疑问。
     */
    @Test
    fun resetButtonsStayHiddenAtFactoryDefaults() {
        render()
        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(0)
    }

    /** 偏离默认后 ↺ 出现，点它写回 `SettingsDefaults` —— 也就是「重新装一次会拿到的那份值」 */
    @Test
    fun resetButtonAppearsOnDeviationAndWritesBackFactoryDefault() {
        var carMode: Boolean? = null
        // 车载模式开着（偏离默认），主题取色仍在默认上 → 全页应当只有**一枚** ↺
        render(player = PlayerUiState(carMode = true), onCarMode = { carMode = it })

        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(1)

        compose.onNodeWithContentDescription("恢复默认").performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.CAR_MODE, carMode)
    }

    /**
     * 歌词子页里字号与「已唱部分的颜色」都偏离了默认 → 两枚 ↺，分别写回各自的默认值。
     *
     * 下标就是它们在页面里的先后顺序（字号在前、颜色在后）。这里顺带盖住一件事：
     * ↺ 嵌在整行 `clickable` 里，点它必须**只**复位，不能顺带打开这一行该进的那个界面
     * ——否则点一次 ↺ 会同时弹出色板，看起来像「复位失败变成了打开设置」。
     */
    @Test
    fun lyricsPageResetsScaleAndSungColorIndependently() {
        var scale: Float? = null
        var sungColor: Int? = null
        render(
            player = PlayerUiState(lyricScale = 1.3f, lyricSungColor = 0xFF4FC3F7.toInt()),
            onLyricScale = { scale = it },
            onLyricSungColor = { sungColor = it },
        )

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已唱部分的颜色").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(2)

        compose.onAllNodesWithContentDescription("恢复默认")[0].performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.LYRIC_SCALE, scale)
        // 点字号的 ↺ 只该动字号：颜色那处的回调一次都不该被叫到
        assertEquals(null, sungColor)
        // 也不该顺带把色板弹层（ModalBottomSheet）打开
        compose.onNodeWithText("预设").assertDoesNotExist()

        compose.onAllNodesWithContentDescription("恢复默认")[1].performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.COLOR_FOLLOW_THEME, sungColor)
    }
}
