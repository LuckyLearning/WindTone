package com.luckylearning.windtone.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 车载设置页（v1.1.32）：**一页到底** + **常驻回播放页** 的回归。
 *
 * 这两条都是「用户看不见就会退回去」的性质：
 *  - 层级一旦退回「六卡 + 子页」，车机上就是三次点击改一个设置，而且没有任何编译错误；
 *  - 常驻播放条一旦被删，用户想回播放页就只能「返回 → 返回 → 点播放条」，
 *    而代码照样能跑、单测照样绿。
 *
 * 所以这里钉的是**结构**（分类卡不存在、就地表单存在、完整设置是唯一例外入口）
 * 与**接线**（点「播放页」真的回调、加减档位真的写值），不锁像素。
 *
 * ⚠️ Robolectric 的视口固定：车机横屏给 1280×800dp，页面靠下的行仍可能落在视口外，
 * 此时 `performClick()`（真实触摸）会被静默丢弃 —— 用 `performScrollTo()` +
 * `performSemanticsAction(OnClick)`（同 SettingsScreenTest 的教训）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(
        player: PlayerUiState = PlayerUiState(carMode = true),
        onOpenPlayer: () -> Unit = {},
        onSpeed: (Float) -> Unit = {},
        onPitch: (Int) -> Unit = {},
        onCarMode: (Boolean) -> Unit = {},
        onApplyOverlayPreset: (OverlayPreset) -> Unit = {},
        onLyricOverlayTextSp: (Int) -> Unit = {},
        onLyricOverlayXPercent: (Int) -> Unit = {},
        onLyricOverlayYPercent: (Int) -> Unit = {},
    ) {
        compose.setContent {
            MusicPlayerTheme {
                SettingsScreen(
                    library = LibraryUiState(),
                    player = player,
                    update = UpdateUiState(currentVersion = "1.1.32"),
                    dynamicTheme = false,
                    autoCheckUpdate = true,
                    downloadRoute = DownloadRoute.AUTO,
                    crashLogCount = 0,
                    crashLogText = null,
                    onClose = {},
                    onDynamicTheme = {},
                    onCarMode = onCarMode,
                    onLyricOverlay = {},
                    onLyricOverlayLines = {},
                    onLyricOverlayFullHeight = {},
                    onLyricOverlayFullWidthPercent = {},
                    onLyricOverlayFullOffsetPercent = {},
                    onLyricOverlayShowTitle = {},
                    onLyricOverlayTitlePercent = {},
                    onLyricOverlayTextSp = onLyricOverlayTextSp,
                    onLyricOverlayAlign = {},
                    onLyricOverlayPauseMode = {},
                    onLyricOverlayWidthPercent = {},
                    onLyricOverlayTextColor = {},
                    onLyricOverlayBoxHeightPercent = {},
                    onLyricOverlayFullHeightPercent = {},
                    onLyricOverlayXPercent = onLyricOverlayXPercent,
                    onLyricOverlayYPercent = onLyricOverlayYPercent,
                    onLyricOverlayFullYPercent = {},
                    onLyricOverlayBgColor = {},
                    onLyricOverlayBgAlphaPercent = {},
                    onLyricOverlayCornerRadiusDp = {},
                    onLyricOverlayBorderWidthDp = {},
                    onLyricOverlayBorderColor = {},
                    onLyricOverlayLineSpacingDp = {},
                    onLyricOverlayCurrentColor = {},
                    onLyricOverlayDimPercent = {},
                    onLyricOverlayStrokeWidthDp = {},
                    onLyricOverlayStrokeColor = {},
                    onLyricOverlayFadeMs = {},
                    onLyricOverlayTitleMode = {},
                    onLyricOverlayHiddenApps = {},
                    onApplyOverlayPreset = onApplyOverlayPreset,
                    onForegroundLogEnabled = {},
                    onDebugUnlocked = {},
                    onOverlayEditing = {},
                    onOverlayPreview = {},
                    onTestBootPlayback = {},
                    canDrawOverlay = false,
                    overlayHealth = LyricOverlayBus.Health(),
                    onLyricScale = {},
                    onLyricTapSeeks = {},
                    onSpeed = onSpeed,
                    onPitch = onPitch,
                    onSkipSilence = {},
                    onAutoResume = {},
                    onAutoPlayOnBoot = {},
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
                    onOpenStats = {},
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
                    onOpenPlayer = onOpenPlayer,
                    onPlayQueue = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
    }

    /** 车载下**不再有**六张分类卡：进设置就是能改的那几项，层级 1 */
    @Test
    fun carModeShowsFlatPageWithoutCategoryCards() {
        render()
        compose.onNodeWithText("播放速度").assertIsDisplayed()
        compose.onNodeWithText("跳过静音").assertIsDisplayed()
        // 分类卡的摘要文案 = 手机版主页的标志；它出现就说明又退回多级了
        compose.onAllNodesWithText("主题模式 · 主题取色 · 车载模式").assertCountEquals(0)
        compose.onAllNodesWithText("版本信息 · 播放统计 · 隐私说明").assertCountEquals(0)
        // 行车中不该出现的细调项也不在车载页上（只收在「完整设置」里）
        compose.onAllNodesWithText("均衡器与音效").assertCountEquals(0)
    }

    /** 常驻播放条上的「播放页」是真的回调（「任何页一步回播放」的落点） */
    @Test
    fun nowPlayingDockOpensPlayer() {
        var opened = 0
        render(onOpenPlayer = { opened++ })
        compose.onNodeWithText("播放页").performClick()
        compose.waitForIdle()
        assertEquals("点「播放页」必须回调 onOpenPlayer", 1, opened)
    }

    /** 档位按钮真的写值：速度 +0.05、变调 +1 半音 */
    @Test
    fun steppersWriteThrough() {
        var speed = 0f
        var semitones = 0
        render(
            player = PlayerUiState(carMode = true, speed = 1.0f, pitchSemitones = 0),
            onSpeed = { speed = it },
            onPitch = { semitones = it },
        )
        compose.onNodeWithContentDescription("提高播放速度").performClick()
        compose.onNodeWithContentDescription("升高一个半音").performClick()
        compose.waitForIdle()
        assertEquals("速度每次升 0.05x", 1.05f, speed, 0.001f)
        assertEquals("变调每次升 1 半音", 1, semitones)
    }

    /** 「完整设置」是唯一的多级入口：点开才出现六张分类卡 */
    @Test
    fun fullSettingsIsTheOnlyMultiLevelEntry() {
        render()
        compose.onNodeWithText("完整设置")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // 六卡出现 = 已进入手机版那套结构；此时标题也换成「完整设置」
        compose.onNodeWithText("主题模式 · 主题取色 · 车载模式").assertIsDisplayed()
        compose.onAllNodesWithText("完整设置").assertCountEquals(1)
    }

    /** 手机端（carMode = false）不被这条分支影响：仍是六卡主页 */
    @Test
    fun phoneModeKeepsSixCategoryCards() {
        render(player = PlayerUiState(carMode = false))
        listOf("外观", "播放", "歌词", "音乐库", "关于与诊断", "实验室功能").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onAllNodesWithText("播放速度").assertCountEquals(0)
    }

    /**
     * 结构护栏：车机设置页的触控目标不能缩到不可点。
     *
     * v1.1.98 起行高改为两档（[CarMetrics]）：常规 76dp、紧凑 62dp。
     * 62dp 仍然显著高于 Material 的 48dp 最小触控目标 —— 这台 DiLink 是 1280×656dp，
     * 76dp 的行高叠起来会把一页设置挤到必须滚动，用户明确反馈「按钮太大、很挤」。
     * 这里锁住两档的**下限**，防止以后有人为了"好看"把行高继续往下压。
     */
    @Test
    fun carSettingsKeepsUsableTouchTargets() {
        val metrics = com.luckylearning.windtone.ui.carMetricsFor(1280f, 656f)
        assertTrue("紧凑档行高不得低于 60dp", metrics.rowMinHeight.value >= 60f)
        assertTrue("紧凑档侧键不得低于 44dp", metrics.sideTile.value >= 44f)

        val regular = com.luckylearning.windtone.ui.carMetricsFor(1920f, 900f)
        assertTrue("常规档行高应回到 76dp 档", regular.rowMinHeight.value >= 76f)

        val source = java.io.File(
            "src/main/java/com/luckylearning/windtone/ui/CarSettingsScreen.kt",
        ).readText()
        assertTrue(
            "常驻播放条高度应在 96~112dp 之间",
            source.contains("CAR_DOCK_BAR_HEIGHT: Dp = 100.dp"),
        )
    }

    /** FR-07：车机设置页有「悬浮窗样式」三枚大卡（C1/C2/C3），无驾驶中简化开关 */
    @Test
    fun carModeShowsOverlayStyleBigCardsWithoutDriveSimplify() {
        render()
        compose.onNodeWithText("悬浮窗样式")
            .performScrollTo()
            .assertIsDisplayed()
        // displayName 带 🚗（OverlayPreset.displayName）
        compose.onNodeWithText("🚗 导航窄条")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("🚗 驻停歌词")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("🚗 纯净浮层")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("文字大小")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("显示位置")
            .performScrollTo()
            .assertIsDisplayed()
        listOf("左上", "右上", "左下", "右下").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // FR-08 已否决：页面上不能出现可操作的「驾驶中简化」
        compose.onAllNodesWithText("驾驶中简化").assertCountEquals(0)
    }

    /** FR-07 / R4：点「驻停歌词」走 applyOverlayPreset，写全局 */
    @Test
    fun selectingCarPresetAppliesGlobally() {
        val applied = mutableListOf<OverlayPreset>()
        render(onApplyOverlayPreset = { applied += it })
        compose.onNodeWithContentDescription("套用悬浮窗样式 🚗 驻停歌词")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, applied.size)
        assertEquals("C2", applied.single().id)
    }

    /** FR-07：字号步进与四角 chips 写穿 */
    @Test
    fun overlayTextSpAndCornerChipsWriteThrough() {
        var textSp = -1
        var x = -1
        var y = -1
        render(
            player = PlayerUiState(
                carMode = true,
                lyricOverlayTextSp = 24,
                lyricOverlayXPercent = 50,
                lyricOverlayYPercent = 40,
            ),
            onLyricOverlayTextSp = { textSp = it },
            onLyricOverlayXPercent = { x = it },
            onLyricOverlayYPercent = { y = it },
        )
        compose.onNodeWithContentDescription("增大悬浮窗字号")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("悬浮窗位置右上")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(26, textSp)
        assertEquals(100, x)
        assertEquals(0, y)
    }
}
