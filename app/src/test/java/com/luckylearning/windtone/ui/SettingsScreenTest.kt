package com.luckylearning.windtone.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.playback.OnlineMediaCache
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.ThemeMode
import com.luckylearning.windtone.overlay.ForegroundAppLog
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
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
        themeMode: ThemeMode = SettingsDefaults.THEME_MODE,
        dynamicTheme: Boolean = SettingsDefaults.DYNAMIC_THEME,
        onThemeMode: (ThemeMode) -> Unit = {},
        onCarMode: (Boolean) -> Unit = {},
        onLyricScale: (Float) -> Unit = {},
        onLyricOverlayTextSp: (Int) -> Unit = {},
        onLyricOverlayTextColor: (Int) -> Unit = {},
        onLyricOverlayBoxHeightPercent: (Int) -> Unit = {},
        onLyricOverlayFullHeightPercent: (Int) -> Unit = {},
        onLyricOverlayXPercent: (Int) -> Unit = {},
        onLyricOverlayYPercent: (Int) -> Unit = {},
        onLyricOverlayFullYPercent: (Int) -> Unit = {},
        onLyricOverlayBgColor: (Int) -> Unit = {},
        onLyricOverlayBgAlphaPercent: (Int) -> Unit = {},
        onLyricOverlayHiddenApps: (Set<String>) -> Unit = {},
        onForegroundLogEnabled: (Boolean) -> Unit = {},
        onDebugUnlocked: (Boolean) -> Unit = {},
        onAutoResume: (Boolean) -> Unit = {},
        onOpenStats: () -> Unit = {},
        onOpenReport: () -> Unit = {},
        onOpenPlayer: () -> Unit = {},
    ) {
        compose.setContent {
            MusicPlayerTheme {
                SettingsScreen(
                    library = LibraryUiState(),
                    player = player,
                    update = UpdateUiState(),
                    themeMode = themeMode,
                    dynamicTheme = dynamicTheme,
                    autoCheckUpdate = true,
                    downloadRoute = DownloadRoute.AUTO,
                    crashLogCount = 0,
                    crashLogText = null,
                    onClose = {},
                    onDynamicTheme = {},
                    onThemeMode = onThemeMode,
                    onCarMode = onCarMode,
                    onLyricOverlay = {},
                    onLyricOverlayLines = {},
                    onLyricOverlayFullHeight = {},
                    onLyricOverlayFullWidthPercent = {},
                    onLyricOverlayFullOffsetPercent = {},
                    onLyricOverlayShowTitle = {},
                    onLyricOverlayTitleSp = {},
                    onLyricOverlayTextSp = onLyricOverlayTextSp,
                    onLyricOverlayAlign = {},
                    onLyricOverlayPauseMode = {},
                    onLyricOverlayWidthPercent = {},
                    onLyricOverlayTextColor = onLyricOverlayTextColor,
                    onLyricOverlayBoxHeightPercent = onLyricOverlayBoxHeightPercent,
                    onLyricOverlayFullHeightPercent = onLyricOverlayFullHeightPercent,
                    onLyricOverlayXPercent = onLyricOverlayXPercent,
                    onLyricOverlayYPercent = onLyricOverlayYPercent,
                    onLyricOverlayFullYPercent = onLyricOverlayFullYPercent,
                    onLyricOverlayBgColor = onLyricOverlayBgColor,
                    onLyricOverlayBgAlphaPercent = onLyricOverlayBgAlphaPercent,
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
                    onLyricOverlayHiddenApps = onLyricOverlayHiddenApps,
                    onForegroundLogEnabled = onForegroundLogEnabled,
                    onDebugUnlocked = onDebugUnlocked,
                    onOverlayEditing = {},
                    onOverlayPreview = {},
                    onTestBootPlayback = {},
                    onPlayQueue = { _, _ -> },
                    canDrawOverlay = false,
                    overlayHealth = LyricOverlayBus.Health(),
                    onLyricScale = onLyricScale,
                    onLyricTapSeeks = {},
                    onSpeed = {},
                    onPitch = {},
                    onSkipSilence = {},
            onSkipIntroSeconds = {},
            onSkipOutroSeconds = {},
                    onAutoResume = onAutoResume,
                    onAutoPlayOnBoot = {},
                    onClearStats = {},
                    maintenance = null,
                    onRefreshMaintenance = {},
                    onCleanupMissingSongs = {},
                    onClearCoverCache = {},
                    onResetUserData = {},
                    onResetLibrary = {},
                    onRestoreExcluded = {},
                    onOpenReport = onOpenReport,
                    onOpenDuplicates = {},
                    onOpenStats = onOpenStats,
                    onOpenPlayer = onOpenPlayer,
                    minSongDurationSec = 60,
                    onMinSongDurationSec = {},
                    scanBlocklist = emptySet(),
                    onScanBlocklist = {},
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

    /**
     * v1.1.78：进入悬浮歌词子页后展开细参。v1.2.54 起细参按语义拆成六张折叠卡。
     *
     * 默认只展开「歌词文字」一组，这里把其余五组点开（多次点击会收起，
     * 所以「歌词文字」不能碰）。等价于旧版那个「高级参数」大开关。
     */
    private fun expandOverlayAdvanced() {
        listOf("外观与预设", "位置与尺寸", "标题块", "背景", "行为").forEach { group ->
            compose.onNodeWithText(group).performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    /**
     * v1.1.80：悬浮歌词不再是主页分类卡，收进了「歌词」子页。
     *
     * 路径与真实用户一致：主页 → 歌词 → 桌面悬浮歌词。
     */
    private fun openOverlayPage() {
        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("桌面悬浮歌词").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** v1.1.80：在线播放收进了实验室功能。 */
    private fun openOnlinePage() {
        compose.onNodeWithText("实验室功能").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("在线播放").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * v1.1.80 三级结构：歌词 → 悬浮歌词，返回键逐级退（不是一步跳回主页）。
     *
     * 这一条锁的是 `SettingsPage.parent()`：新增嵌套页面时最容易犯的错就是
     * 所有子页的上一级都写 ROOT —— 用户从歌词点进去，返回就直接被弹到主页。
     */
    @Test
    fun overlayBackReturnsToLyricsPageNotRoot() {
        render(player = PlayerUiState(lyricOverlay = true))
        openOverlayPage()
        // v1.2.54 第二版：页面上只剩「主开关 + 折叠卡」，用最上面那张卡的标题打点
        compose.onNodeWithText("外观与预设").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        // 回到「歌词」页：播放页歌词那段还在
        compose.onNodeWithText("播放页歌词").performScrollTo().assertIsDisplayed()
        // 再退一次才回主页（分类卡出现）
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("主题模式 · 主题取色 · 车载模式").assertIsDisplayed()
    }

    /** v1.1.80：更新从主页卡降级为「关于」里的一行；2026-10-30 卡片改名「关于与更新」。 */
    @Test
    fun aboutPageOpensUpdatePage() {
        render()
        compose.onNodeWithText("关于与更新").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("启动时自动检查更新").performScrollTo().assertIsDisplayed()
    }

    /**
     * 2026-11-01：电池优化白名单从「诊断与排障」（要连点版本号 5 次才解锁）提到关于页。
     *
     * 车机上这一项没加白名单的后果是「后台被冻结 → 放着放着没声」，且不报错；
     * 两次诊断包都是「否」。所以它必须在**普通用户能找到**的地方，而不是排障页。
     */
    @Test
    fun aboutPageShowsBatteryOptimizationEntry() {
        render()
        compose.onNodeWithText("关于与更新").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("后台运行").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("电池优化").performScrollTo().assertIsDisplayed()
        // Robolectric 下 isIgnoringBatteryOptimizations 恒为 false → 应显示「未加入」引导
        compose.onNodeWithText("未加入白名单", substring = true).assertIsDisplayed()
    }

    /** 主页分类卡（v1.1.80：6 张，一屏放下）。 */
    @Test
    fun rootShowsSixCategoryCards() {
        render()
        listOf("外观", "播放", "歌词", "曲库与存储", "关于与更新", "实验室功能").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        // 分类卡与子页里的 ActionRow 的区别就在这一行摘要（连摘要一起断言，
        // 免得以后退回成没有摘要的普通 ActionRow 也照样"通过"）
        compose.onNodeWithText("主题模式 · 主题取色 · 车载模式").assertIsDisplayed()
        compose.onNodeWithText("统计 · 清理 · 过滤 < 60 秒").assertIsDisplayed()
        // 降级成入口行的三个分类不许再出现在主页
        listOf("悬浮歌词", "在线播放", "更新", "调整菜单顺序").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    /** 点「外观」→ 进外观子页（主题 / 车载两段），主页的卡整批让位 */
    @Test
    fun appearanceCardOpensAppearancePage() {
        render()
        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("主题跟随封面取色").assertIsDisplayed()
        compose.onNodeWithText("车载").assertIsDisplayed()
        compose.onNodeWithText("关于与更新").assertDoesNotExist()
    }

    /** 点「歌词」→ 进歌词子页；按返回键回到主页（六张卡回来） */
    @Test
    fun lyricsCardOpensSubPageAndBackReturnsToRoot() {
        render()
        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("播放页歌词").assertIsDisplayed()
        compose.onNodeWithText("关于与更新").assertDoesNotExist()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("关于与更新").assertIsDisplayed()
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
        // 车载模式开着（偏离默认），主题取色仍在默认上 → 全页应当只有**一枚** ↺。
        // v1.2.8 起车载与手机共用同一套设置页，不再有「完整设置」中转。
        render(player = PlayerUiState(carMode = true), onCarMode = { carMode = it })

        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(1)

        compose.onNodeWithContentDescription("恢复默认").performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.CAR_MODE, carMode)
    }

    /**
     * v1.2.8 起车载与手机共用同一套设置页，**唯一**区别是：车载模式下每一页顶栏都有
     * 「播放页」，一步回正在播放；手机模式不显示（底部播放条本来就常在）。
     */
    @Test
    fun carModeShowsPlayerButtonOnEveryPageAndItOpensPlayer() {
        var opened = 0
        render(player = PlayerUiState(carMode = true), onOpenPlayer = { opened++ })

        compose.onNodeWithText("播放页").assertIsDisplayed()
        compose.onNodeWithText("播放页").performClick()
        compose.waitForIdle()
        assertEquals("点「播放页」必须回调 onOpenPlayer", 1, opened)
    }

    /** 手机模式没有「播放页」按钮：底部播放条本来就常在，多一个入口是噪音 */
    @Test
    fun phoneModeDoesNotShowPlayerButtonInSettings() {
        render(player = PlayerUiState())
        compose.onNodeWithText("播放页").assertDoesNotExist()
    }

    /**
     * 歌词子页里字号与悬浮歌词文字颜色都偏离了默认 → 两枚 ↺，各自只复位自己那一项。
     *
     * 刻意盖住两件事：
     *  ① ↺ 嵌在整行 `clickable` 里，点它必须**只**复位，不能顺带打开这一行该进的那个界面
     *     ——否则点一次 ↺ 会同时弹出色板，看起来像「复位失败变成了打开设置」；
     *  ② ↺ 必须**先滚进视口再点**：这一页比 800dp 的测试窗口长，页尾那枚 ↺ 的底边实测在
     *     1662px，而窗口只有 1600px —— 点击坐标落到窗口外时会**静默落空**（回调一次都
     *     不被叫到，看着像功能坏了），既不报错也不抛异常，只能靠断言抓到。
     *
     * ⚠️ v1.1.21 起 `lyricOverlay` 必须显式打开：悬浮歌词关着时，颜色这一项与其
     * 余外观项一起隐藏（见 [overlayAppearanceOptionsHideWhenOverlayOff]），
     * 沿用默认的 `false` 会直接找不到这一行。
     */
    @Test
    fun lyricsPageResetsScaleAndOverlayTextColorIndependently() {
        var scale: Float? = null
        var textColor: Int? = null
        render(
            player = PlayerUiState(
                lyricScale = 1.3f,
                lyricOverlay = true,
                lyricOverlayTextColor = 0xFF4FC3F7.toInt(),
            ),
            onLyricScale = { scale = it },
            onLyricOverlayTextColor = { textColor = it },
        )

        // v1.1.52 拆页后：字号 ↺ 在「歌词」子页，颜色 ↺ 在「悬浮歌词」子页，
        // 两处互不干扰这件事正好借拆页钉得更死。
        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        // 歌词子页只有「歌词字号」偏离默认（双语 / 跳转都在默认档，不挂 ↺）
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("恢复默认")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.LYRIC_SCALE, scale)
        // 悬浮歌词那边的回调不该被顺带叫到
        assertEquals(null, textColor)

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        openOverlayPage()
        expandOverlayAdvanced()
        // 悬浮歌词子页挂 ↺ 的两处：①开关本身（开着 ≠ 出厂默认）②「悬浮歌词文字颜色」
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(2)
        compose.onNodeWithText("悬浮歌词文字颜色").performScrollTo()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("恢复默认")[1].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            "点「悬浮歌词文字颜色」行的 ↺ 应当写回 OVERLAY_TEXT_COLOR",
            SettingsDefaults.OVERLAY_TEXT_COLOR,
            textColor,
        )
        // 它只该动颜色：字号那处的回调不该被叫到；也不该顺带把色板弹层打开
        assertEquals(SettingsDefaults.LYRIC_SCALE, scale)
        compose.onNodeWithText("预设").assertDoesNotExist()
    }

    // ================================================================ v1.1.21 联动项按开关显隐

    /**
     * 悬浮歌词关闭时，它下面那一整块**一个都不许出现**。
     *
     * 这条测的是用户 2026-10-18 提的那句「功能关闭时相应联动选项都隐藏」：
     * 此前的做法是「关着也允许预设」，于是页面上摆着一排改了不会生效的控件 ——
     * 用户调完宽度回桌面什么也看不到，只会当成 bug。断言用 assertDoesNotExist
     * 而不是「不可见」：不存在与不可见是两回事，前者连读屏都扫不到。
     */
    @Test
    fun overlayAppearanceOptionsHideWhenOverlayOff() {
        render(player = PlayerUiState(lyricOverlay = false))

        // v1.1.52 拆页后这些项都在「悬浮歌词」子页
        openOverlayPage()

        listOf(
            "全屏高度",
            "正文字号",
            "悬浮歌词对齐",
            "背景不透明度",
            "暂停时的悬浮歌词",
            "悬浮歌词文字颜色",
            "显示歌名与歌手",
            "悬浮歌词行数",
            "调整悬浮歌词位置",
        ).forEach { title ->
            compose.onNodeWithText(title, substring = true).assertDoesNotExist()
        }
        // 开关本身必须还在（这一页只剩「分组标题 + 开关」两处出现这个词），
        // 否则整页就剩一个孤零零的分组标题。
        compose.onAllNodesWithText("桌面悬浮歌词").assertCountEquals(2)
    }

    /**
     * 反过来：开关打开后这些项必须**真的出现**。
     *
     * 与上一条成对 —— 只测「关掉会消失」的话，把整块内容误删掉也能通过测试。
     * 顺带锁住「全屏关闭态下展示单条字幕那套」：行数在、全屏宽度不在。
     */
    @Test
    fun overlayAppearanceOptionsAppearWhenOverlayOn() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayFullHeight = false))

        // v1.1.52：这些项从「歌词」子页拆到了「悬浮歌词」子页
        openOverlayPage()
        expandOverlayAdvanced()

        listOf(
            "全屏高度",
            "悬浮歌词行数",
            "悬浮歌词宽度",
            "显示歌名与歌手",
            "正文字号",
            "悬浮歌词对齐",
            "背景不透明度",
            "悬浮歌词文字颜色",
        ).forEach { title ->
            compose.onNodeWithText(title, substring = true).performScrollTo().assertIsDisplayed()
        }
        // 单条字幕这一档不该出现全屏专属的滑块（v1.1.48 起单条自己有水平/垂直位置，
        // 所以这里判的是「全屏宽度」而不是「水平位置」）
        compose.onNodeWithText("全屏宽度", substring = true).assertDoesNotExist()
        compose.onNodeWithText("水平位置", substring = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * v1.1.49：行数多出一档「自动」。
     *
     * 为什么值得单独测：这一档存的是 0，而 0 在老代码里会被夹成 1（越界值）。
     * 界面看着有「自动」，点下去却回到 1 行，是最容易被当成「点了没反应」的形态。
     */
    @Test
    fun overlayLinesDialogOffersAutoFit() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayFullHeight = false))

        openOverlayPage()
        expandOverlayAdvanced()

        compose.onNodeWithText("悬浮歌词行数", substring = true).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("自动（按高度适配）", substring = true).assertIsDisplayed()
        // 手选档不能被顶掉：老用户设的 3 行 / 5 行还得能选回来
        compose.onAllNodesWithText("3 行").assertCountEquals(1)
    }

    /**
     * v1.1.48：尺寸 / 位置 / 背景这些新旋钮真的出现在页面上。
     *
     * 为什么要测：这些控件全在「悬浮歌词开着」的条件块里，写错一个分支就会静默消失，
     * 而那在编译期完全看不出来 —— 用户只能报「设置里找不到」。
     */
    @Test
    fun overlaySizeAndBackgroundControlsAppear() {
        render(
            player = PlayerUiState(
                lyricOverlay = true,
                lyricOverlayFullHeight = false,
            ),
        )
        openOverlayPage()
        expandOverlayAdvanced()

        listOf("高度 自动", "水平位置", "垂直位置", "选择背景色", "背景颜色", "背景不透明度")
            .forEach { title ->
                compose.onNodeWithText(title, substring = true).performScrollTo().assertIsDisplayed()
            }
        // 全屏专属的控件不该混进来
        compose.onNodeWithText("全屏高度 1", substring = true).assertDoesNotExist()
    }

    /**
     * v1.1.51：背景只剩「颜色 + 不透明度」两项，不再有「底板三档 + 自定义背景」
     * 两套开关同时管同一个结果（用户要求「能自由调节的就不要用固定项」）。
     */
    @Test
    fun overlayBackgroundIsAlwaysCustomizable() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayFullHeight = false))

        openOverlayPage()
        expandOverlayAdvanced()

        compose.onNodeWithText("背景不透明度", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择背景色", substring = true).performScrollTo().assertIsDisplayed()
        // 旧两套开关必须彻底消失，否则用户又会看到「改了不生效」的那一套
        compose.onNodeWithText("自定义背景", substring = true).assertDoesNotExist()
        compose.onNodeWithText("悬浮歌词底板", substring = true).assertDoesNotExist()
    }

    /**
     * v1.2.54：字号改成**磅值 + −/＋ 步进**，不再有滑杆与上下限。
     *
     * 用户原话「字号大小不按区间，用磅，没有区间限制，参考 anylyric」——
     * 滑杆天生需要 range，所以「还在用滑杆」本身就等于「还有区间」，
     * 这条测试锁住的是「点 ＋ 真的按 2 磅往上走」，而不只是界面上有个数字。
     */
    @Test
    fun overlayFontSizeUsesPtStepper() {
        var lastSp = -1
        render(
            player = PlayerUiState(lyricOverlay = true, lyricOverlayTextSp = 28),
            onLyricOverlayTextSp = { lastSp = it },
        )

        openOverlayPage()
        expandOverlayAdvanced()

        compose.onNodeWithText("正文字号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("28 磅").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("正文字号 增大").performScrollTo().performClick()
        assertEquals("步进 2 磅，且不设上限", 30, lastSp)
        compose.onNodeWithContentDescription("正文字号 减小").performScrollTo().performClick()
        assertEquals("减号同样一档 2 磅", 26, lastSp)
        // 旧的「小 / 中 / 大」三档不该再出现
        compose.onNodeWithText("小", substring = false).assertDoesNotExist()
    }

    /**
     * v1.2.54：标题块的四类可调项（位置 / 对齐 / 颜色 / 描边）都要能摸到。
     *
     * 这些行藏在「标题块」分组里，而分组默认收起 —— 少挂一条回调、少一行 UI，
     * 编译期完全看不出来，只有用户点开才发现「没有这个设置」。所以按用户路径走一遍。
     */
    @Test
    fun overlayTitleBlockExposesPositionAlignColorAndOutline() {
        render(
            player = PlayerUiState(
                lyricOverlay = true,
                lyricOverlayShowTitle = true,
            ),
        )
        openOverlayPage()
        expandOverlayAdvanced()

        listOf("标题位置", "标题对齐", "标题颜色", "标题描边").forEach { title ->
            compose.onNodeWithText(title, substring = true).performScrollTo().assertIsDisplayed()
        }
        // 三档对齐的档位文案要真的出现（不是只有一个标题行）
        compose.onNodeWithText("居右", substring = false).performScrollTo().assertIsDisplayed()
    }

    /** v1.2.54：标题关掉时，标题块里的位置 / 对齐 / 颜色 / 描边必须全部收起 */
    @Test
    fun overlayTitleBlockCollapsesWhenTitleOff() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayShowTitle = false))
        openOverlayPage()
        expandOverlayAdvanced()

        compose.onNodeWithText("标题位置", substring = true).assertDoesNotExist()
        compose.onNodeWithText("标题描边", substring = true).assertDoesNotExist()
    }

    /**
     * v1.2.54：当前行 / 其他行分开调；只有一行歌词时「其他行」整块收起。
     *
     * 这正对应「不起效的就收起」：一行字幕没有「上下一句」，留着一组滑杆
     * 只会让人反复调却什么也看不到。
     */
    @Test
    fun overlayPerRoleStylingHidesContextRowsForSingleLine() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayLines = 1))
        openOverlayPage()
        expandOverlayAdvanced()

        compose.onNodeWithText("当前行字号", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("其他行字号", substring = true).assertDoesNotExist()
    }

    /**
     * v1.2.54 第二版：整页不再是「几十行平铺」，而是「主开关 + 六张折叠卡」。
     *
     * 用户原话「所有设置规划组合一下，显示隐藏要联动起来，不要罗列全部平铺」——
     * 这条测的正是**收起态**：六张卡的标题都在，而卡内的细项一个都不渲染。
     * 断言用 assertDoesNotExist（不存在，而不是不可见）：收起必须是没进组合，
     * 否则读屏软件还能扫到一堆「看得见摸不着」的控件。
     */
    @Test
    fun overlayPageIsCollapsedIntoCardsByDefault() {
        render(player = PlayerUiState(lyricOverlay = true))

        openOverlayPage()

        listOf("外观与预设", "位置与尺寸", "歌词文字", "标题块", "背景", "行为").forEach { card ->
            compose.onNodeWithText(card).performScrollTo().assertIsDisplayed()
        }
        // 默认只展开「歌词文字」，其余卡内细项一律不存在
        listOf(
            "全屏高度",
            "悬浮歌词行数",
            "背景不透明度",
            "暂停时的悬浮歌词",
            "显示歌名与歌手",
        ).forEach { title ->
            compose.onNodeWithText(title, substring = true).assertDoesNotExist()
        }
        // 默认展开的那一张，细项必须在
        compose.onNodeWithText("正文字号").performScrollTo().assertIsDisplayed()
    }

    /**
     * v1.2.54 第二版：只有一行歌词时，行距也跟着「其他行」一起收起。
     *
     * 一行字之间没有「行与行之间」，留着滑杆就是「调了没反应」。
     */
    @Test
    fun overlayLineSpacingHidesForSingleLine() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayLines = 1))
        openOverlayPage()
        expandOverlayAdvanced()

        compose.onNodeWithText("歌词行距", substring = true).assertDoesNotExist()
    }

    /** v1.2.54：行数 >1 时「其他行」的三项（颜色 / 字号 / 压暗）出现 */
    @Test
    fun overlayPerRoleStylingShowsContextRowsForMultiLine() {
        render(player = PlayerUiState(lyricOverlay = true, lyricOverlayLines = 3))
        openOverlayPage()
        expandOverlayAdvanced()

        listOf("其他行颜色", "其他行字号", "上下句亮度").forEach { title ->
            compose.onNodeWithText(title, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    // ================================================================ v1.1.16 继续上次播放

    /**
     * 播放子页里有「打开应用继续上次播放」，点它的 ↺ 写回出厂默认。
     *
     * 为什么非要断言它在界面上：这条开关真正干活的地方在引擎里（冷启动恢复队列
     * 之后那一下 `play()`），界面少一行既不会编译失败也不会运行报错 ——
     * 功能从此只能靠「开关找不到」被用户发现，而那通常是一周以后的事。
     */
    @Test
    fun playbackPageExposesAutoResumeSwitch() {
        var autoResume: Boolean? = null
        render(player = PlayerUiState(autoResume = false), onAutoResume = { autoResume = it })

        compose.onNodeWithText("播放").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("打开应用继续上次播放").performScrollTo().assertIsDisplayed()

        // 出厂默认是「开」，当前是「关」→ 这一页只该挂着这一枚 ↺
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(1)
        compose.onNodeWithContentDescription("恢复默认").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.AUTO_RESUME, autoResume)
    }

    // ================================================================ v1.1.17 曲库格式与容量

    /**
     * 「阿里云盘」页已不在设置里（v1.1.75 升为抽屉一级入口，见 AliyunPanPageTest）。
     *
     * 这里保留 v1.1.56 那次真机崩溃的教训说明：`pagePositions` 是按下标存各页滚动
     * 位置的，当年 ALIYUN 是枚举里 ordinal 最大的那一项，只要表比枚举短一格就必崩。
     * 枚举项增删时的长度契约另由 [SettingsPagePositionsTest] 直接断言。
     */
    @Test
    fun onlinePagePicksCacheInDialogAndOpensReport() {
        render()
        openOnlinePage()

        compose.onNodeWithText("缓存上限").performScrollTo().performClick()
        compose.waitForIdle()
        // A3 把默认档从 512MB 改成 128MB 之后，「当前值摘要」与「弹窗里的选项文案」
        // 会在同一个界面上撞车（两个节点都带 OnClick，`onNodeWithText` 直接报
        // 「found 2 nodes」）。这里用「先切到非默认档、再切回默认」的路径绕开：
        // 选 256MB 时行摘要还是 128MB、弹窗里 256MB 唯一；选回 128MB 时反过来。
        compose.onNodeWithText("256MB").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("2GB").assertCountEquals(0)
        compose.onNodeWithText("256MB").assertIsDisplayed()

        compose.onNodeWithText("缓存上限").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("128MB").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("128MB").assertIsDisplayed()

        compose.onNodeWithText("收听报告").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("每日收听时长").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("每日收听时长").assertIsDisplayed()
        compose.onNodeWithText("最常播放").performScrollTo().assertIsDisplayed()

        OnlineMediaCache.setLimitBytes(
            ApplicationProvider.getApplicationContext(),
            OnlineMediaCache.MAX_BYTES,
        )
    }

    /**
     * 「曲库与存储」子页里有「曲库统计」入口，点它能把请求交出去。
     *
     * 与上一条同理：统计页本身是个全屏子页，入口行丢了**不会编译失败也不会报错**，
     * 只会让这个功能从界面上消失。顺带钉住这一行确实存在（不是被折叠进别的行）。
     *
     * 2026-10-30：卡片由「音乐库」改名「曲库与存储」，原来的「格式与容量」行
     * 改名「曲库统计」，并与「播放统计」并排放在同一组（用户口径：按钮放一起，
     * 查看各进各的页面 —— 见下一条用例）。
     */
    @Test
    fun libraryPageExposesFormatStatsEntry() {
        var opened = false
        render(onOpenStats = { opened = true })

        compose.onNodeWithText("曲库与存储").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("曲库统计").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("曲库统计").performClick()
        compose.waitForIdle()
        assert(opened) { "点「曲库统计」应当打开统计页" }
    }

    /**
     * 「播放统计」与「曲库统计」两个入口并排在同一页（2026-10-30 用户口径）。
     *
     * 分两个页面看是用户要的，但**入口必须挨着** —— 分开放的话，
     * 「我这库有多大 / 我听了多久」这两个总要一起看的问题得来回翻两页。
     */
    @Test
    fun playbackStatsAndLibraryStatsSitTogether() {
        var report = false
        var stats = false
        render(onOpenReport = { report = true }, onOpenStats = { stats = true })

        compose.onNodeWithText("曲库与存储").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("播放统计").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("曲库统计").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("播放统计").performClick()
        compose.waitForIdle()
        assert(report) { "点「播放统计」应当打开收听报告页" }
        assert(!stats) { "点「播放统计」不该顺带打开曲库统计" }
    }

    // ================================================================ v1.1.18 双语歌词

    /**
     * 「歌词」子页**不再有**「双语歌词」入口（v1.2.2 用户要求移除切换功能）。
     *
     * 这条是反向守护：功能删掉后最怕有人"顺手加回来"，或者删了 UI 却没删状态，
     * 让设置页留一条点不动的死行。断言的是"界面上找不到这个词"。
     */
    @Test
    fun lyricsPageNoLongerExposesBilingualSwitch() {
        render()

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("双语歌词", substring = true).assertCountEquals(0)
    }

    @Test
    fun lyricsPageKeepsOnlyHiddenAppsRow() {
        render(player = PlayerUiState(lyricOverlay = true))

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        // 歌词子页不再有名单入口（跟着悬浮歌词拆走了）
        compose.onNodeWithText("不显示悬浮窗的应用").assertDoesNotExist()
        // 记录开关也已经搬走，不该再出现在歌词子页
        compose.onNodeWithText("记录前台应用切换").assertDoesNotExist()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        openOverlayPage()
        expandOverlayAdvanced()
        compose.onNodeWithText("不显示悬浮窗的应用").performScrollTo().assertExists()
    }

    /**
     * 调试分区：开关 + 历史 + 「加入名单」闭环（v1.1.30）。
     *
     * 用户要的是「在记录里看到 360 影像，直接把它加进名单」，而不是复制包名、
     * 切到歌词子页、再粘进弹窗搜索框 —— 车机上这套动作没人愿意做。
     */
    @Test
    fun debugSectionTogglesLogAndAddsFromHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ForegroundAppLog.clear(context)
        ForegroundAppLog.append(context, "com.byd.aeri.caranywhere", "360 影像")

        var logEnabled: Boolean? = null
        var hidden: Set<String>? = null
        render(
            // 调试分区默认隐藏，本用例要验的是分区内的接线，所以直接以「已解锁」状态渲染
            player = PlayerUiState(lyricOverlay = true, debugUnlocked = true),
            onForegroundLogEnabled = { logEnabled = it },
            onLyricOverlayHiddenApps = { hidden = it },
        )

        compose.onNodeWithText("关于与更新").performClick()
        compose.waitForIdle()
        // 2026-10-30：「调试」分区降为二级页，入口在「关于与更新」里
        compose.onNodeWithText("诊断与排障").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("调试").performScrollTo().assertExists()
        // SwitchRow 整行不可点，只有开关本体可点；它带 contentDescription = 标题
        compose.onNodeWithContentDescription("记录前台应用切换")
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("点开关应回调 true", true, logEnabled)

        // 历史里那条记录 → 「加入名单」
        //
        // ⚠️ 这里用语义动作而不是 performClick()：这一行在页面很靠下的位置，
        // Robolectric 的测试窗口只有 360×800dp，`performScrollTo()` 之后按钮
        // 仍可能落在窗口外 —— 而 Compose 的 performClick() 是**注入真实触摸**，
        // 落在窗口外会被静默丢弃（不抛异常、回调一次都不触发）。
        // 本用例要验的是「按钮接线到 onLyricOverlayHiddenApps」，不是命中区域；
        // 真实点击在模拟器上验过（设置 → 关于与更新 → 诊断与排障 → 加入名单）。
        compose.onNodeWithText("360 影像").assertExists()
        compose.onNodeWithText("加入名单").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(
            "应把历史里的包名加入名单",
            setOf("com.byd.aeri.caranywhere"),
            hidden,
        )
    }

    /**
     * 调试分区默认**不在**页面上（v1.1.31）。
     *
     * 排障工具对普通用户是纯噪音：他们既不需要「前台应用记录」，也不该看到
     * 「崩溃日志 / 开机自启」这些字样。藏起来不是安全措施，是不打扰。
     *
     * 2026-10-30 起这批工具整体降为二级页「诊断与排障」，未解锁时整页只有一行提示。
     */
    @Test
    fun debugSectionIsHiddenByDefault() {
        render()

        compose.onNodeWithText("关于与更新").performClick()
        compose.waitForIdle()
        // 2026-10-30：「调试」分区降为二级页，入口在「关于与更新」里
        compose.onNodeWithText("诊断与排障").performScrollTo().performClick()
        compose.waitForIdle()

        // 2026-10-30：播放统计与维护动作已搬去「曲库与存储」，关于页只剩版本与更新
        compose.onNodeWithText("播放统计").assertDoesNotExist()
        // 未解锁时只摆一行说明，一件排障工具都不出现
        compose.onNodeWithText("显示调试功能").assertDoesNotExist()
        compose.onNodeWithText("记录前台应用切换").assertDoesNotExist()
        compose.onNodeWithText("诊断包（完整）").assertDoesNotExist()
    }

    /**
     * 在「关于」卡上连点 5 次才解锁，点 4 次不算。
     *
     * 计数只看「本页连续点击」：`remember` 不落盘，退出本页重新数 ——
     * 否则零散误触会慢慢攒够 5 次，把入口翻出来。
     */
    @Test
    fun fiveTapsOnAboutUnlockDebugSection() {
        var unlocked: Boolean? = null
        render(onDebugUnlocked = { unlocked = it })

        compose.onNodeWithText("关于与更新").performClick()
        compose.waitForIdle()

        repeat(4) {
            compose.onNodeWithText("版本").performScrollTo().performClick()
            compose.waitForIdle()
        }
        assertNull("点 4 次不该解锁", unlocked)

        compose.onNodeWithText("版本").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("第 5 次应解锁调试分区", true, unlocked)
    }

    /**
     * 解锁后可关回去：调试分区顶部那个开关写回 false。
     *
     * 开关放在分区**最上面**是有意的 —— 关掉之后整段消失，放在底部就再也点不到了。
     */
    @Test
    fun debugSectionCanBeHiddenAgainFromItsOwnSwitch() {
        var unlocked: Boolean? = null
        render(
            player = PlayerUiState(debugUnlocked = true),
            onDebugUnlocked = { unlocked = it },
        )

        compose.onNodeWithText("关于与更新").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("诊断与排障").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("调试").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("显示调试功能")
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("关掉开关应把解锁状态写回 false", false, unlocked)
    }

    /**
     * 主题模式（v1.2.4）：外观页三档 chip 全部可见，点「深色」回报 DARK。
     *
     * 为什么要测「点下去回报的是不是那一档」：三档是**按 ordinal 映射**的，
     * 文案列表与枚举顺序一旦错位，界面上看着能点、选中的却是隔壁那一档 ——
     * 这种错位不会崩、不会报错，只能靠断言守住。
     */
    @Test
    fun appearancePageThemeModeChipsReportTheirOwnMode() {
        var picked: ThemeMode? = null
        render(onThemeMode = { picked = it })

        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        // 三档都在（出厂默认选中「跟随系统」）
        compose.onNodeWithText("主题模式").assertIsDisplayed()
        compose.onNodeWithText("跟随系统").assertIsDisplayed()
        compose.onNodeWithText("浅色").assertIsDisplayed()
        compose.onNodeWithText("深色").assertIsDisplayed()

        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, picked)

        compose.onNodeWithText("浅色").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.LIGHT, picked)
    }

    /**
     * 主题模式偏离默认（深色）后 ↺ 出现，点它写回 `SettingsDefaults`（跟随系统）。
     *
     * 出厂默认必须是「跟随系统」：升级用户此前一直跟随系统，
     * 默认值一变，系统深色用户会当场被改成浅色。
     */
    @Test
    fun themeModeResetGoesBackToSystem() {
        var picked: ThemeMode? = null
        render(themeMode = ThemeMode.DARK, onThemeMode = { picked = it })

        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("恢复默认")
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.THEME_MODE, picked)
        assertEquals(ThemeMode.SYSTEM, picked)
    }
}
