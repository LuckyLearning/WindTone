package com.luckylearning.windtone.ui
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.overlay.OverlaySnapshot
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.playback.Pitch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设置页的层级：**一个主页面 + 六个子页**。
 *
 * v1.1.3 二次重构（效果图 ㉙ 与其后的 ㉙B/C/D）：
 *  - 主页面**只放六张二级分类入口卡**（[CategoryRow]），一屏放下、任何设置最多两层直达；
 *  - 外观 / 播放 / 歌词 从「平铺在主页面上的三组」下沉为子页 —— 起因是桌面悬浮歌词的
 *    五个外观选项加入后，「歌词」一组独占两屏，主页又开始长回去；
 *  - 子页里按系统返回键先回主页面，回主页后再按才关闭整个设置页。
 *
 * 分层与 chevron 规范见 docs/ui/index.html「四、设置页」：只有 [CategoryRow] 与
 * [ActionRow] 能带右箭头，[OptionRow]（弹层行）依旧是「左侧图标 + 选中 ✓、无箭头」。
 */
internal enum class SettingsPage(val title: String) {
    ROOT("设置"),

    // v1.1.3 二次重构：主页只放六张二级分类入口卡，原来平铺在主页的
    // 「外观 / 播放 / 歌词」三组各自下沉成子页（顺序与主页卡片顺序一致）。
    APPEARANCE("外观"),
    PLAYBACK("播放"),
    LYRICS("歌词"),

    LIBRARY("音乐库"),
    UPDATE("更新"),
    DIAGNOSTICS("关于与诊断"),

    /** 在线歌单（M3U / M3U8 / PLS）—— v1.1.37 */
    ONLINE("在线歌单"),

    /** 百度网盘 —— v1.1.39 */
    BAIDU("百度网盘"),
}

/**
 * 各页面的滚动位置，按 [SettingsPage.ordinal] 排列。
 *
 * 存成 `List<Int>` 而不是 `ScrollState`：一个 Int 不受任何对象生命周期影响，
 * 这是这套机制能成立的全部原因（细节见 `SettingsScreen` 里 `pagePositions` 的注释）。
 */
internal val PagePositionsSaver = listSaver<MutableList<Int>, Int>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * 统一设置页。
 *
 * 之前所有开关（主题取色 / 歌词字号 / 速度 / 音效）散落在播放页菜单与库页菜单里，
 * 这里收拢成一个入口，并补上「数据」「崩溃日志」「关于」三块此前完全缺失的内容。
 */
@Composable
fun SettingsScreen(
    library: LibraryUiState,
    player: PlayerUiState,
    update: UpdateUiState,
    dynamicTheme: Boolean,
    autoCheckUpdate: Boolean,
    downloadRoute: DownloadRoute,
    crashLogCount: Int,
    crashLogText: String?,
    onClose: () -> Unit,
    onDynamicTheme: (Boolean) -> Unit,
    onCarMode: (Boolean) -> Unit,
    onLyricOverlay: (Boolean) -> Unit,
    /** 立刻走一次「开机后台起播」（v1.1.27，诊断页用） */
    onTestBootPlayback: () -> Unit,
    /** 播放一批在线条目（v1.1.37）：(队列, 起始下标) */
    onPlayQueue: (List<Song>, Int) -> Unit,
    onLyricOverlayLines: (Int) -> Unit,
    /** 悬浮歌词全屏高度（v1.1.19） */
    onLyricOverlayFullHeight: (Boolean) -> Unit,
    /** 全屏歌词的宽度（屏宽百分比）与水平位置（余量百分比）—— v1.1.20 */
    onLyricOverlayFullWidthPercent: (Int) -> Unit,
    onLyricOverlayFullOffsetPercent: (Int) -> Unit,
    onLyricOverlayShowTitle: (Boolean) -> Unit,
    onLyricOverlayTitleScale: (Int) -> Unit,
    onLyricOverlayTextScale: (Int) -> Unit,
    onLyricOverlayAlign: (Int) -> Unit,
    onLyricOverlayBackdrop: (Int) -> Unit,
    onLyricOverlayPauseMode: (Int) -> Unit,
    /** 悬浮窗宽度：屏幕宽度百分比（1..100）—— v1.1.4 */
    onLyricOverlayWidthPercent: (Int) -> Unit,
    onLyricOverlayBoxHeightPercent: (Int) -> Unit,
    onLyricOverlayFullHeightPercent: (Int) -> Unit,
    onLyricOverlayXPercent: (Int) -> Unit,
    onLyricOverlayYPercent: (Int) -> Unit,
    onLyricOverlayFullYPercent: (Int) -> Unit,
    onLyricOverlayBgColor: (Int) -> Unit,
    onLyricOverlayBgAlphaPercent: (Int) -> Unit,
    onLyricOverlayCustomBg: (Boolean) -> Unit,
    /** 悬浮窗歌词文字颜色 —— v1.1.4 */
    onLyricOverlayTextColor: (Int) -> Unit,
    /** 「不显示悬浮窗的应用」名单（v1.1.29） */
    onLyricOverlayHiddenApps: (Set<String>) -> Unit,
    /** 「前台应用记录」开关（v1.1.29） */
    onForegroundLogEnabled: (Boolean) -> Unit,
    /** 调试分区解锁开关（v1.1.31，默认隐藏） */
    onDebugUnlocked: (Boolean) -> Unit,
    onOverlayEditing: (Boolean) -> Unit,
    onOverlayPreview: () -> Unit,
    canDrawOverlay: Boolean,
    overlayHealth: LyricOverlayBus.Health,
    onLyricScale: (Float) -> Unit,
    /** 点歌词行是否跳转（默认关，v1.1.23） */
    onLyricTapSeeks: (Boolean) -> Unit,
    /** 双语歌词显示模式：0 都显示 / 1 只中文 / 2 只英文 —— v1.1.18 */
    onLyricBilingual: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    /** 变调：半音（-12..+12，0 = 原调） */
    onPitch: (Int) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    /** 「打开应用继续上次播放」：冷启动恢复队列后是否自动起播 —— v1.1.16 */
    onAutoResume: (Boolean) -> Unit,
    /** 开机后台起播（v1.1.24） */
    onAutoPlayOnBoot: (Boolean) -> Unit,
    onClearStats: () -> Unit,
    maintenance: DataMaintenance.Report?,
    onRefreshMaintenance: () -> Unit,
    onCleanupMissingSongs: () -> Unit,
    onClearCoverCache: () -> Unit,
    onResetUserData: () -> Unit,
    /** 清空曲库与导入目录（保留收藏 / 统计 / 歌单）——与 [onResetUserData] 方向相反 */
    onResetLibrary: () -> Unit,
    /** 取消全部「移出曲库」的排除标记并重扫 */
    onRestoreExcluded: () -> Unit,
    /** 打开收听报告（批次 10.1） */
    onOpenReport: () -> Unit,
    /** 打开重复歌曲清理（批次 10.2） */
    onOpenDuplicates: () -> Unit,
    /** 打开「曲库格式与容量」统计页 —— v1.1.17 */
    onOpenStats: () -> Unit,
    minSongDurationSec: Int,
    onMinSongDurationSec: (Int) -> Unit,
    onLoadCrashLog: () -> Unit,
    onExportCrashLog: () -> Unit,
    onClearCrashLog: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onAutoCheckUpdate: (Boolean) -> Unit,
    onDownloadRoute: (DownloadRoute) -> Unit,
    onOpenReleasePage: () -> Unit,
    onIgnoreUpdate: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    // ---------------------------------------------------------- 车载专用（v1.1.32）
    // 车载设置页是另一套「一页到底」的版式，它需要几个手机版设置里没有的动作：
    // 一步回播放页、就地切歌、重新扫描、打开扫描范围弹层。
    // 全部带默认值 —— 手机端与既有测试不用改一行。
    /** 一步回播放页（调用方负责先关设置页） */
    onOpenPlayer: () -> Unit = {},
    onTogglePlay: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onRescan: () -> Unit = {},
    onOpenScanScope: () -> Unit = {},
) {

    // 当前处于哪一层：主页面 / 音乐库 / 更新 / 关于与诊断
    var page by remember { mutableStateOf(SettingsPage.ROOT) }

    // 车载：「完整设置」是否已打开（v1.1.32）。
    // 车载默认走一页到底的 CarSettingsScreen；只有用户主动点最后一行「完整设置」
    // 才回到手机版这套六卡 + 子页的结构，且返回键先退回车载页而不是直接关设置。
    var fullSettings by remember { mutableStateOf(false) }

    // ---------------------------------------------------------------- 车载设置
    // 为什么在入口处分叉而不是改造六个子页：车机的诉求是「层级 1、控件大、now playing 常在」，
    // 与手机的「分类卡 + 子页」是两套信息结构。混在一套里必然两边都不达标
    // （子页里的 13sp 小字、Slider 拖动、弹层都是行车中不该出现的东西）。
    if (player.carMode && !fullSettings) {
        CarSettingsScreen(
            player = player,
            update = update,
            minSongDurationSec = minSongDurationSec,
            canDrawOverlay = canDrawOverlay,
            onClose = onClose,
            onOpenPlayer = onOpenPlayer,
            onOpenFullSettings = { fullSettings = true },
            onTogglePlay = onTogglePlay,
            onPrevious = onPrevious,
            onNext = onNext,
            onSpeed = onSpeed,
            onPitch = onPitch,
            onSkipSilence = onSkipSilence,
            onAutoResume = onAutoResume,
            onAutoPlayOnBoot = onAutoPlayOnBoot,
            onLyricScale = onLyricScale,
            onLyricOverlay = onLyricOverlay,
            onRescan = onRescan,
            onOpenScanScope = onOpenScanScope,
            onCheckUpdate = onCheckUpdate,
        )
        return
    }

    // 子页里按返回键先回主页面（本处理器在子页才启用，主页面时交给外层关闭设置页）
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    // 车载「完整设置」在主页时，返回键退回车载页（注册在子页处理器之后，优先命中）
    BackHandler(enabled = player.carMode && fullSettings && page == SettingsPage.ROOT) {
        fullSettings = false
    }

    // 每个页面各留一份滚动位置 —— 但**存数字，不存 ScrollState 对象**。
    //
    // 上一版是按页面各建一个 rememberScrollState()，用 when 选一个挂到 verticalScroll 上，
    // 思路是「没挂上去的 state 不会被测量，maxValue / value 都不会被人动，天然保留原值」。
    // 用户第二次报同一个问题（进子页返回主页，位置又被顶回最上面）后确认这条不成立。
    // 教训：只要正确性建立在「某个对象在整段导航期间一直活着、而且一直没人动它」上，
    // 就会在某个看不见的时刻被推翻，还不报任何错。
    //
    // 这里改成显式快照：离开某页时把它的滚动位置记进这张表，回到该页时用记下的值建 state。
    // 存进 rememberSaveable 的是 Int，与 ScrollState 的生死完全无关，旋转屏幕也不丢。
    // 子页也是各记各的，所以「音乐库」上次滚到哪里，再进去还是那里。
    // ⚠️ 长度必须等于 SettingsPage 的项数（现为 7），否则 index 越界；
    // 以后加子页记得一起加一个 0。
    val pagePositions = rememberSaveable(saver = PagePositionsSaver) {
        mutableStateListOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
    val pageIndex = page.ordinal
    val pageScroll = remember(pageIndex) { ScrollState(pagePositions[pageIndex]) }
    DisposableEffect(pageIndex, pageScroll) {
        // 切换页面时旧 state 已被 verticalScroll 卸下，不会再被测量 / 夹取，
        // 此刻读到的一定是用户真正停下的位置。
        onDispose { pagePositions[pageIndex] = pageScroll.value }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // 与库主页 / 播放页保持一致：底部导航栏（手势条）会盖住最后一段内容，
                // 之前只在顶部做了 statusBarsPadding，滚动到底时「关于」说明会被挡。
                .navigationBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(
                    onClick = {
                        when {
                            page != SettingsPage.ROOT -> page = SettingsPage.ROOT
                            // 车载：从「完整设置」退回车载设置页，而不是直接关掉整个设置
                            player.carMode && fullSettings -> fullSettings = false
                            else -> onClose()
                        }
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    // 车载进入「完整设置」时换个标题：让用户始终知道自己站在哪一层
                    if (player.carMode && fullSettings && page == SettingsPage.ROOT) {
                        "完整设置"
                    } else {
                        page.title
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                // 车载：任何子页都在顶栏留一个「播放页」——这是本版「任何页一步回播放」
                // 的后半句。车载设置页底部有常驻播放条，但用户一旦进了「完整设置」
                // （六卡 + 子页），播放条就不在画面上了，顶栏这一个按钮补上那个缺口。
                if (player.carMode) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenPlayer) {
                        Icon(
                            AppIcons.Lyrics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("播放页", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScroll)
                    // 车机横屏可用宽度约 1700dp，若内容铺满整行，开关会被推到离文字
                    // 一两千像素远的地方，视线要在两端来回跳。这里限宽并水平居中；
                    // 手机宽度本就小于该上限，约束不生效，行为完全不变。
                    .widthIn(max = if (player.carMode) 1100.dp else 720.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = if (player.carMode) 32.dp else 18.dp),
            ) {
                // ================================================== 主设置页（六张二级分类卡）
                // v1.1.3 二次重构：主页**只放分类入口卡**，一张卡一个分类 ——
                // 左侧语义图标（40dp 圆角 12 容器、secondaryContainer 底）+ 分类名
                // + 一行摘要 + 右箭头。此前「外观 / 播放 / 歌词」三组平铺在主页，
                // 悬浮歌词五个外观选项加入后「歌词」一组独占两屏，主页又长回去了；
                // 现在三组各自下沉成子页，主页回到**一屏六张卡**、任何设置最多两层直达。
                //
                // 与子页里 ActionRow 的区别（效果图《子页入口行与分类卡的区别》）：
                // ActionRow 是「某一类设置里的一条」，无图标；CategoryRow 是
                // 「一整类设置的门面」，带图标 + 摘要 —— 两层信息密度不同，不会混淆。

                // ================================================== 页面调度
                // v1.1.6：六个子页的正文全部搬到 `SettingsPages.kt`（本文件一度 2165 行，
                // 远超 §8.6 定的 600 行）。这里只剩编排 —— 一个页面一个调用。
                // 页面自己的弹层与「哪个弹窗开着」的状态也一并搬过去了：
                // 那些本来就是各页的私事，挂在顶层只是历史原因。
                when (page) {
                    SettingsPage.ROOT -> SettingsRootPage(
                        player = player,
                        update = update,
                        minSongDurationSec = minSongDurationSec,
                        onNavigate = { page = it },
                    )

                    SettingsPage.APPEARANCE -> SettingsAppearancePage(
                        player = player,
                        dynamicTheme = dynamicTheme,
                        onDynamicTheme = onDynamicTheme,
                        onCarMode = onCarMode,
                    )

                    SettingsPage.PLAYBACK -> SettingsPlaybackPage(
                        player = player,
                        onSpeed = onSpeed,
                        onPitch = onPitch,
                        onSkipSilence = onSkipSilence,
                        onAutoResume = onAutoResume,
                        onAutoPlayOnBoot = onAutoPlayOnBoot,
                    )

                    SettingsPage.LYRICS -> SettingsLyricsPage(
                        player = player,
                        canDrawOverlay = canDrawOverlay,
                        overlayHealth = overlayHealth,
                        onLyricScale = onLyricScale,
                        onLyricTapSeeks = onLyricTapSeeks,
                        onLyricBilingual = onLyricBilingual,
                        onLyricOverlay = onLyricOverlay,
                        onLyricOverlayLines = onLyricOverlayLines,
                        onLyricOverlayFullHeight = onLyricOverlayFullHeight,
                        onLyricOverlayFullWidthPercent = onLyricOverlayFullWidthPercent,
                        onLyricOverlayFullOffsetPercent = onLyricOverlayFullOffsetPercent,
                        onLyricOverlayShowTitle = onLyricOverlayShowTitle,
                        onLyricOverlayTitleScale = onLyricOverlayTitleScale,
                        onLyricOverlayTextScale = onLyricOverlayTextScale,
                        onLyricOverlayAlign = onLyricOverlayAlign,
                        onLyricOverlayBackdrop = onLyricOverlayBackdrop,
                        onLyricOverlayPauseMode = onLyricOverlayPauseMode,
                        onLyricOverlayWidthPercent = onLyricOverlayWidthPercent,
                        onLyricOverlayBoxHeightPercent = onLyricOverlayBoxHeightPercent,
                        onLyricOverlayFullHeightPercent = onLyricOverlayFullHeightPercent,
                        onLyricOverlayXPercent = onLyricOverlayXPercent,
                        onLyricOverlayYPercent = onLyricOverlayYPercent,
                        onLyricOverlayFullYPercent = onLyricOverlayFullYPercent,
                        onLyricOverlayBgColor = onLyricOverlayBgColor,
                        onLyricOverlayBgAlphaPercent = onLyricOverlayBgAlphaPercent,
                        onLyricOverlayCustomBg = onLyricOverlayCustomBg,
                        onLyricOverlayTextColor = onLyricOverlayTextColor,
                        onLyricOverlayHiddenApps = onLyricOverlayHiddenApps,
                        onOverlayEditing = onOverlayEditing,
                        onOverlayPreview = onOverlayPreview,
                    )

                    SettingsPage.LIBRARY -> SettingsLibraryPage(
                        minSongDurationSec = minSongDurationSec,
                        onMinSongDurationSec = onMinSongDurationSec,
                        onRestoreExcluded = onRestoreExcluded,
                        onOpenReport = onOpenReport,
                        onOpenDuplicates = onOpenDuplicates,
                        onOpenStats = onOpenStats,
                        onOpenOnline = { page = SettingsPage.ONLINE },
                        onOpenBaidu = { page = SettingsPage.BAIDU },
                        library = library,
                    )

                    SettingsPage.ONLINE -> OnlinePlaylistsPage(onPlay = onPlayQueue)

                    SettingsPage.BAIDU -> BaiduPanPage(onPlay = onPlayQueue)

                    SettingsPage.DIAGNOSTICS -> SettingsDiagnosticsPage(
                        player = player,
                        library = library,
                        maintenance = maintenance,
                        crashLogCount = crashLogCount,
                        crashLogText = crashLogText,
                        onRefreshMaintenance = onRefreshMaintenance,
                        onCleanupMissingSongs = onCleanupMissingSongs,
                        onClearCoverCache = onClearCoverCache,
                        onResetUserData = onResetUserData,
                        onResetLibrary = onResetLibrary,
                        onClearStats = onClearStats,
                        onRestoreExcluded = onRestoreExcluded,
                        onOpenReport = onOpenReport,
                        onOpenDuplicates = onOpenDuplicates,
                        onLoadCrashLog = onLoadCrashLog,
                        onExportCrashLog = onExportCrashLog,
                        onClearCrashLog = onClearCrashLog,
                        onOverlayPreview = onOverlayPreview,
                        onLyricOverlay = onLyricOverlay,
                        onForegroundLogEnabled = onForegroundLogEnabled,
                        onLyricOverlayHiddenApps = onLyricOverlayHiddenApps,
                        onTestBootPlayback = onTestBootPlayback,
                        onDebugUnlocked = onDebugUnlocked,
                    )

                    SettingsPage.UPDATE -> SettingsUpdatePage(
                        update = update,
                        downloadRoute = downloadRoute,
                        autoCheckUpdate = autoCheckUpdate,
                        onCheckUpdate = onCheckUpdate,
                        onAutoCheckUpdate = onAutoCheckUpdate,
                        onDownloadRoute = onDownloadRoute,
                        onOpenReleasePage = onOpenReleasePage,
                        onIgnoreUpdate = onIgnoreUpdate,
                        onDownloadUpdate = onDownloadUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onGrantInstallPermission = onGrantInstallPermission,
                        onDismissUpdateDialog = onDismissUpdateDialog,
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}



internal fun formatListened(ms: Long): String {
    if (ms < 60_000L) return "${ms / 1000} 秒"
    val totalMinutes = ms / 60_000L
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "$h 小时 $m 分钟" else "$m 分钟"
}

/**
 * 打开系统的「显示悬浮窗」授权页，返回是否成功打开。
 *
 * 逐级降级的原因：各 ROM 对 `ACTION_MANAGE_OVERLAY_PERMISSION` 的支持并不一致 ——
 * 带 `package:` 数据在原生系统上会直接定位到本应用的授权项，但部分
 * HyperOS / MIUI 版本不认这个 data，会打开一个空的应用列表；
 * 再不行就退到应用详情页（那里一定有权限入口）。三条路都断时返回 false，
 * 由调用方把手动路径写给用户，绝不静默失败。
 */
// 系统授权页的跳转（三级降级 + 手动路径兜底）已提到 Common.kt：
// 播放设置弹层（⑱ 屏）与设置页都要用它，不能只留在这一处。
