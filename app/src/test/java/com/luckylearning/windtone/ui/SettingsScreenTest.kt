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
import com.luckylearning.windtone.data.SettingsDefaults
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
        onLyricBilingual: (Int) -> Unit = {},
        onLyricOverlayTextColor: (Int) -> Unit = {},
        onLyricOverlayBoxHeightPercent: (Int) -> Unit = {},
        onLyricOverlayFullHeightPercent: (Int) -> Unit = {},
        onLyricOverlayXPercent: (Int) -> Unit = {},
        onLyricOverlayYPercent: (Int) -> Unit = {},
        onLyricOverlayFullYPercent: (Int) -> Unit = {},
        onLyricOverlayBgColor: (Int) -> Unit = {},
        onLyricOverlayBgAlphaPercent: (Int) -> Unit = {},
        onLyricOverlayCustomBg: (Boolean) -> Unit = {},
        onLyricOverlayHiddenApps: (Set<String>) -> Unit = {},
        onForegroundLogEnabled: (Boolean) -> Unit = {},
        onDebugUnlocked: (Boolean) -> Unit = {},
        onAutoResume: (Boolean) -> Unit = {},
        onOpenStats: () -> Unit = {},
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
                    onLyricOverlayFullHeight = {},
                    onLyricOverlayFullWidthPercent = {},
                    onLyricOverlayFullOffsetPercent = {},
                    onLyricOverlayShowTitle = {},
                    onLyricOverlayTitleScale = {},
                    onLyricOverlayTextScale = {},
                    onLyricOverlayAlign = {},
                    onLyricOverlayBackdrop = {},
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
                    onLyricOverlayCustomBg = onLyricOverlayCustomBg,
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
                    onLyricBilingual = onLyricBilingual,
                    onSpeed = {},
                    onPitch = {},
                    onSkipSilence = {},
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
                    onOpenReport = {},
                    onOpenDuplicates = {},
                    onOpenStats = onOpenStats,
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
        compose.onNodeWithText("格式与容量 · 过滤 < 60 秒").assertIsDisplayed()
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

        // ⚠️ v1.1.32 起车载模式默认走「扁平单页」设置，六张分类卡收在「完整设置」里。
        // 这条用例验的是手机版六卡结构里的 ↺，所以先经「完整设置」进到那套页面
        // （这条路径本身也顺带被覆盖：车载下唯一的多级入口必须通）。
        compose.onNodeWithText("完整设置")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithText("外观").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(1)

        compose.onNodeWithContentDescription("恢复默认").performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.CAR_MODE, carMode)
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

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        // 三枚：①「歌词字号」（页首）②「桌面悬浮歌词」开关本身（开着 ≠ 出厂默认）
        // ③「悬浮歌词文字颜色」（页尾）。开关那枚是 v1.1.21 打开悬浮歌词后才冒出来的，
        // 早先这条测试用默认的「关闭」状态，所以只有两枚。
        compose.onAllNodesWithContentDescription("恢复默认").assertCountEquals(3)

        // 下标 = 页面里的先后顺序：0 歌词字号 / 1 悬浮歌词开关 / 2 悬浮歌词文字颜色
        compose.onNodeWithText("悬浮歌词文字颜色").performScrollTo()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("恢复默认")[2].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            "点「悬浮歌词文字颜色」行的 ↺ 应当写回 OVERLAY_TEXT_COLOR",
            SettingsDefaults.OVERLAY_TEXT_COLOR,
            textColor,
        )
        // 它只该动颜色：字号那处的回调不该被叫到；也不该顺带把色板弹层打开
        assertEquals(null, scale)
        compose.onNodeWithText("预设").assertDoesNotExist()

        compose.onAllNodesWithContentDescription("恢复默认")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(SettingsDefaults.LYRIC_SCALE, scale)
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

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()

        listOf(
            "全屏高度",
            "悬浮歌词字号",
            "悬浮歌词对齐",
            "悬浮歌词底板",
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

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()

        listOf(
            "全屏高度",
            "悬浮歌词行数",
            "悬浮歌词宽度",
            "显示歌名与歌手",
            "悬浮歌词字号",
            "悬浮歌词对齐",
            "悬浮歌词底板",
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

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()

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
                lyricOverlayCustomBg = true,
            ),
        )
        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()

        listOf("高度 自动", "水平位置", "垂直位置", "自定义背景", "选择背景色", "背景颜色", "背景不透明度")
            .forEach { title ->
                compose.onNodeWithText(title, substring = true).performScrollTo().assertIsDisplayed()
            }
        // 全屏专属的控件不该混进来
        compose.onNodeWithText("全屏高度 1", substring = true).assertDoesNotExist()
    }

    /** 关掉「自定义背景」时，色板与不透明度滑块必须一起隐藏（用户 2026-10-21 的要求） */
    @Test
    fun overlayCustomBackgroundControlsHiddenWhenOff() {
        render(
            player = PlayerUiState(
                lyricOverlay = true,
                lyricOverlayFullHeight = false,
                lyricOverlayCustomBg = false,
            ),
        )
        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("自定义背景", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择背景色", substring = true).assertDoesNotExist()
        compose.onNodeWithText("背景不透明度", substring = true).assertDoesNotExist()
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
     * 「音乐库」子页里有「格式与容量」入口，点它能把请求交出去。
     *
     * 与上一条同理：统计页本身是个全屏子页，入口行丢了**不会编译失败也不会报错**，
     * 只会让这个功能从界面上消失。顺带钉住这一行确实存在（不是被折叠进别的行）。
     */
    @Test
    fun libraryPageExposesFormatStatsEntry() {
        var opened = false
        render(onOpenStats = { opened = true })

        compose.onNodeWithText("音乐库").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("格式与容量").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("格式与容量").performClick()
        compose.waitForIdle()
        assert(opened) { "点「格式与容量」应当打开统计页" }
    }

    // ================================================================ v1.1.18 双语歌词

    /**
     * 「歌词」子页有「双语歌词」入口，弹层三选一，点选即回调。
     *
     * 入口行丢了不会编译失败也不会报错，只会让这个功能从界面上消失 ——
     * 与上面「格式与容量」入口同理，必须有一条编译期之外的回归。
     */
    @Test
    fun lyricsPageExposesBilingualModeChoice() {
        var mode: Int? = null
        render(onLyricBilingual = { mode = it })

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("双语歌词 · 都显示", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("双语歌词 · 都显示", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("只显示中文").performClick()
        compose.waitForIdle()
        assertEquals("点「只显示中文」应当回调 1", 1, mode)
    }

    /**
     * v1.1.30：两个入口分家了。
     *
     *  · 歌词子页只剩「不显示悬浮窗的应用」（日常配置）；
     *  · 「记录前台应用切换」搬进 关于与诊断 → **调试**，和「加入名单」放在同一屏。
     *
     * 防的是「功能从界面上悄悄消失」—— 这两处都是用户在自己车上配名单的唯一入口，
     * 丢了不会编译失败、不会报错。
     */
    @Test
    fun lyricsPageKeepsOnlyHiddenAppsRow() {
        render(player = PlayerUiState(lyricOverlay = true))

        compose.onNodeWithText("歌词").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("不显示悬浮窗的应用").performScrollTo().assertExists()
        // 记录开关已经搬走，不该再出现在歌词子页
        compose.onNodeWithText("记录前台应用切换").assertDoesNotExist()
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

        compose.onNodeWithText("关于与诊断").performClick()
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
        // 真实点击在模拟器上验过（设置 → 关于与诊断 → 调试 → 加入名单）。
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
     */
    @Test
    fun debugSectionIsHiddenByDefault() {
        render()

        compose.onNodeWithText("关于与诊断").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("播放统计").assertExists()
        compose.onNodeWithText("调试").assertDoesNotExist()
        compose.onNodeWithText("显示调试功能").assertDoesNotExist()
        compose.onNodeWithText("记录前台应用切换").assertDoesNotExist()
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

        compose.onNodeWithText("关于与诊断").performClick()
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

        compose.onNodeWithText("关于与诊断").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("调试").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("显示调试功能")
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("关掉开关应把解锁状态写回 false", false, unlocked)
    }
}
