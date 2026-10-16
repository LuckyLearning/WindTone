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
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import kotlin.math.abs

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
private enum class SettingsPage(val title: String) {
    ROOT("设置"),

    // v1.1.3 二次重构：主页只放六张二级分类入口卡，原来平铺在主页的
    // 「外观 / 播放 / 歌词」三组各自下沉成子页（顺序与主页卡片顺序一致）。
    APPEARANCE("外观"),
    PLAYBACK("播放"),
    LYRICS("歌词"),

    LIBRARY("音乐库"),
    UPDATE("更新"),
    DIAGNOSTICS("关于与诊断"),
}

/**
 * 各页面的滚动位置，按 [SettingsPage.ordinal] 排列。
 *
 * 存成 `List<Int>` 而不是 `ScrollState`：一个 Int 不受任何对象生命周期影响，
 * 这是这套机制能成立的全部原因（细节见 `SettingsScreen` 里 `pagePositions` 的注释）。
 */
private val PagePositionsSaver = listSaver<MutableList<Int>, Int>(
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
    onLyricOverlayLines: (Int) -> Unit,
    onLyricOverlayTextScale: (Int) -> Unit,
    onLyricOverlayAlign: (Int) -> Unit,
    onLyricOverlayBackdrop: (Int) -> Unit,
    onLyricOverlayPauseMode: (Int) -> Unit,
    /** 悬浮窗宽度：屏幕宽度百分比（1..100）—— v1.1.4 */
    onLyricOverlayWidthPercent: (Int) -> Unit,
    /** 悬浮窗正文色（未唱部分）—— v1.1.4 */
    onLyricOverlayTextColor: (Int) -> Unit,
    /** 悬浮窗已唱部分的颜色 —— v1.1.4 */
    onLyricOverlaySungColor: (Int) -> Unit,
    onOverlayEditing: (Boolean) -> Unit,
    onOverlayPreview: () -> Unit,
    canDrawOverlay: Boolean,
    overlayHealth: LyricOverlayBus.Health,
    onLyricScale: (Float) -> Unit,
    onLyricWordByWord: (Boolean) -> Unit,
    /** 逐字歌词已唱部分的颜色（ARGB）；`SettingsDefaults.COLOR_FOLLOW_THEME` = 跟随主题 */
    onLyricSungColor: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
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
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    var showEq by remember { mutableStateOf(false) }
    // 短音频过滤阈值编辑弹窗
    var showMinDurationDialog by remember { mutableStateOf(false) }
    // 下载线路选择弹窗
    var showRouteDialog by remember { mutableStateOf(false) }
    // 悬浮歌词的外观选项（行数 / 字号 / 底板 / 暂停行为）共用一个选项弹层
    var overlayChoice by remember { mutableStateOf<OverlayChoice?>(null) }
    // v1.1.4：三处可配的歌词颜色（播放页已唱色 / 悬浮窗文字色 / 悬浮窗已唱色）
    // 共用一个选择弹层 —— 它们只是「写回哪个字段」不同，界面完全一样
    var colorPicker by remember { mutableStateOf<ColorSlot?>(null) }
    // 用户点过悬浮歌词开关但还没授权 → 展开下方授权指引，授权成功返回后自动收起
    var overlayHintVisible by remember { mutableStateOf(false) }
    // 三条跳转路都走不通（ROM 连系统授权页都不给开）→ 指引卡里改显示手动路径
    var overlayJumpFailed by remember { mutableStateOf(false) }
    LaunchedEffect(canDrawOverlay) {
        if (canDrawOverlay) {
            overlayHintVisible = false
            overlayJumpFailed = false
        }
    }

    // 当前处于哪一层：主页面 / 音乐库 / 更新 / 关于与诊断
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    // 车载模式的长说明从副标题搬进弹窗，副标题只留一行摘要
    var showCarModeInfo by remember { mutableStateOf(false) }
    // 三处不可撤销的操作统一走二次确认
    var showClearStatsConfirm by remember { mutableStateOf(false) }
    var showClearCrashConfirm by remember { mutableStateOf(false) }
    // 数据维护（诊断子页）：清理失效歌曲 / 清空全部用户数据
    var showCleanupConfirm by remember { mutableStateOf(false) }
    // 悬浮歌词自检结果
    var showOverlayCheck by remember { mutableStateOf(false) }
    var overlayCheck by remember { mutableStateOf<OverlaySnapshot?>(null) }
    var showResetDataConfirm by remember { mutableStateOf(false) }
    // 清空曲库（与「清空全部用户数据」方向相反，同样不可撤销）
    var showResetLibraryConfirm by remember { mutableStateOf(false) }

    // 子页里按返回键先回主页面（本处理器在子页才启用，主页面时交给外层关闭设置页）
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

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
        mutableStateListOf(0, 0, 0, 0, 0, 0, 0)
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
                IconButton(onClick = { if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    page.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
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
                if (page == SettingsPage.ROOT) {
                    CategoryRow(
                        title = "外观",
                        subtitle = "主题取色 · 车载模式",
                        icon = AppIcons.Palette,
                        onClick = { page = SettingsPage.APPEARANCE },
                    )
                    CategoryRow(
                        title = "播放",
                        subtitle = "速度 ${"%.2f".format(player.speed)}x · 跳过静音 · 均衡器与音效",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { page = SettingsPage.PLAYBACK },
                    )
                    CategoryRow(
                        title = "歌词",
                        subtitle = "字号 ${"%.1f".format(player.lyricScale)}x · 逐字 · 悬浮歌词",
                        icon = AppIcons.LyricsBubble,
                        onClick = { page = SettingsPage.LYRICS },
                    )
                    CategoryRow(
                        title = "音乐库",
                        // 效果图把「过滤 < 60 秒」放在摘要里（而不是右侧值），照做
                        subtitle = if (minSongDurationSec > 0) {
                            "过滤 < ${minSongDurationSec} 秒"
                        } else {
                            "未开启短音频过滤"
                        },
                        icon = AppIcons.LibraryMusic,
                        onClick = { page = SettingsPage.LIBRARY },
                    )
                    CategoryRow(
                        title = "更新",
                        subtitle = "检查更新 · 自动检查 · 下载线路",
                        icon = AppIcons.Cloud,
                        onClick = { page = SettingsPage.UPDATE },
                        trailing = if (update.info != null) "有新版本" else "v${update.currentVersion}",
                    )
                    CategoryRow(
                        title = "关于与诊断",
                        subtitle = "版本信息 · 播放统计 · 崩溃日志 · 隐私说明",
                        icon = AppIcons.Info,
                        onClick = { page = SettingsPage.DIAGNOSTICS },
                    )
                }

                // ================================================== 子页：外观
                // 这一组是「界面长什么样」的总开关，改动频率低；
                // 留在主页只会让六张分类卡之外又开始长第三层内容。
                if (page == SettingsPage.APPEARANCE) {

                    // ------------------------------------------------ 主题
                    SectionTitle("主题")
                    SwitchRow(
                        title = "主题跟随封面取色",
                        subtitle = "从当前歌曲封面提取主色，驱动整个界面的配色",
                        checked = dynamicTheme,
                        onCheckedChange = onDynamicTheme,
                        onReset = resetAction(dynamicTheme != SettingsDefaults.DYNAMIC_THEME) {
                            onDynamicTheme(SettingsDefaults.DYNAMIC_THEME)
                        },
                    )

                    // ------------------------------------------------ 车载
                    SectionTitle("车载")
                    // 副标题原文 180 字，一条设置项就占掉大半屏；详细说明移到标题旁的「?」弹窗
                    SwitchRow(
                        title = "车载模式",
                        subtitle = "横屏大界面 · 歌词页自动收起 · 开机自启",
                        checked = player.carMode,
                        onCheckedChange = onCarMode,
                        onInfo = { showCarModeInfo = true },
                        onReset = resetAction(player.carMode != SettingsDefaults.CAR_MODE) {
                            onCarMode(SettingsDefaults.CAR_MODE)
                        },
                    )

                }

                // ================================================== 子页：播放
                // 速度滑块与「跳过静音」就地生效（无箭头）；「均衡器与音效」
                // 打开的是底部弹层，属于「点开会进入另一个界面」，带箭头。
                if (page == SettingsPage.PLAYBACK) {

                    // ------------------------------------------------ 速度
                    SectionTitle("速度")
                    SliderRow(
                        title = "默认播放速度 ${"%.2f".format(player.speed)}x",
                        value = player.speed,
                        range = 0.75f..2.0f,
                        onValueChange = onSpeed,
                        onReset = resetAction(abs(player.speed - SettingsDefaults.SPEED) > 0.001f) {
                            onSpeed(SettingsDefaults.SPEED)
                        },
                    )

                    // ------------------------------------------------ 播放行为
                    SectionTitle("播放行为")
                    SwitchRow(
                        title = "跳过静音",
                        subtitle = "自动跳过录音开头的空白段（对整轨 / 现场录音有效）",
                        checked = player.skipSilence,
                        onCheckedChange = onSkipSilence,
                        onReset = resetAction(player.skipSilence != SettingsDefaults.SKIP_SILENCE) {
                            onSkipSilence(SettingsDefaults.SKIP_SILENCE)
                        },
                    )
                    ActionRow(
                        title = "均衡器与音效",
                        subtitle = "均衡器 / 低音增强 / 环绕声 / 响度增强（需先开始播放）",
                        onClick = { showEq = true },
                        chevron = true,
                    )

                }

                // ================================================== 子页：歌词
                // 内容与 v1.0.7 主页的「歌词」组完全一致，只是整体挪进子页 ——
                // 歌词字号 → 逐字歌词 → 桌面悬浮歌词 → 五个外观选项 + 调整入口。
                //
                // 降级不降可达性：播放页「播放设置」弹层的「歌词」分组里仍有
                // 「桌面悬浮歌词」开关（见 Sheets.kt），听歌时顺手就能开，不必钻两层设置。
                if (page == SettingsPage.LYRICS) {

                    // ------------------------------------------------ 播放页歌词
                    SectionTitle("播放页歌词")
                    SliderRow(
                        title = "歌词字号 ${"%.1f".format(player.lyricScale)}x",
                        value = player.lyricScale,
                        range = 0.7f..1.6f,
                        onValueChange = onLyricScale,
                        onReset = resetAction(abs(player.lyricScale - SettingsDefaults.LYRIC_SCALE) > 0.001f) {
                            onLyricScale(SettingsDefaults.LYRIC_SCALE)
                        },
                    )
                    SwitchRow(
                        title = "逐字歌词",
                        subtitle = "当前行随播放逐字点亮；无逐字时间轴的歌词按行时长自动派生",
                        checked = player.lyricWordByWord,
                        onCheckedChange = onLyricWordByWord,
                        onReset = resetAction(player.lyricWordByWord != SettingsDefaults.LYRIC_WORD_BY_WORD) {
                            onLyricWordByWord(SettingsDefaults.LYRIC_WORD_BY_WORD)
                        },
                    )
                    // v1.1.4：逐字歌词「已唱部分」的颜色。此前写死成 colorScheme.primary，
                    // 现在允许固定成某个颜色；「跟随主题」保持原样 —— 那是默认，不是特例。
                    ActionRow(
                        title = "已唱部分的颜色",
                        subtitle = "逐字歌词里已经唱过的那一段；默认跟随主题色（随封面取色漂移）",
                        onClick = { colorPicker = ColorSlot.LYRIC_SUNG },
                        trailing = LyricsColors.label(player.lyricSungColor),
                        chevron = true,
                        onReset = resetAction(
                            player.lyricSungColor != SettingsDefaults.LYRIC_SUNG_COLOR,
                        ) { onLyricSungColor(SettingsDefaults.LYRIC_SUNG_COLOR) },
                    )

                    // ------------------------------------------------ 桌面悬浮歌词
                    SectionTitle("桌面悬浮歌词")
                    SwitchRow(
                        title = "桌面悬浮歌词",
                        subtitle = if (canDrawOverlay) {
                            "退到桌面 / 导航页后，在屏幕最上层显示当前歌词行（App 内自动隐藏，开启瞬间有 6 秒预览）"
                        } else {
                            "需要先授予「出现在其他应用上」权限，点击开关会自动跳转授权页"
                        },
                        checked = player.lyricOverlay,
                        onCheckedChange = { want ->
                            if (!want) {
                                onLyricOverlay(false)
                            } else if (canDrawOverlay) {
                                onLyricOverlay(true)
                            } else {
                                // 未授权：先跳系统授权页。授权成功返回后由
                                // MainActivity 的 onResume 重新对账并自动打开开关，
                                // 这里不预先置 true，免得界面显示开启却什么都看不到。
                                //
                                // ⚠️ 各 ROM 对 ACTION_MANAGE_OVERLAY_PERMISSION 的支持并不一致：
                                // 带 package: 数据在原生系统上会直接定位到本应用，但部分
                                // HyperOS / MIUI 版本不认这个 data，会打开一个空的应用列表 ——
                                // 用户视角就是「点了开关什么都没发生」。所以逐级降级，
                                // 并且三条路都走不通时把手动路径写出来，绝不静默。
                                overlayHintVisible = true
                                overlayJumpFailed = !context.startOverlayPermissionPage()
                            }
                        },
                        // 关掉悬浮歌词是「回到默认」，但这里的 ↺ 只负责把开关拨回默认值，
                        // 不等同于下面那个「调整位置」——位置是另一件事，不在这里复位。
                        onReset = resetAction(player.lyricOverlay != SettingsDefaults.LYRIC_OVERLAY) {
                            onLyricOverlay(SettingsDefaults.LYRIC_OVERLAY)
                        },
                    )
                    // 悬浮歌词的五个外观选项：行数 / 字号 / 对齐 / 底板 / 暂停行为。
                    // 都紧跟在开关下方，与它绑成一个整体；开关关着也允许**预设**，
                    // 所以不做可见性 gating。五项共用同一个选项弹层，改完立刻给一次 6 秒预览。
                    ActionRow(
                        title = "悬浮歌词行数",
                        subtitle = if (player.lyricOverlayLines == 3) {
                            "3 行：上一行 / 当前行 / 下一行，当前行高亮"
                        } else {
                            "1 行：只显示当前歌词行（一行放不下时自动折行）"
                        },
                        onClick = { overlayChoice = OverlayChoice.LINES },
                        chevron = true,
                        onReset = resetAction(player.lyricOverlayLines != SettingsDefaults.OVERLAY_LINES) {
                            onLyricOverlayLines(SettingsDefaults.OVERLAY_LINES)
                        },
                    )
                    ActionRow(
                        title = "悬浮歌词字号",
                        subtitle = "大屏与车机上建议调大一档",
                        onClick = { overlayChoice = OverlayChoice.TEXT_SCALE },
                        trailing = OVERLAY_TEXT_SCALE_LABELS[player.lyricOverlayTextScale],
                        chevron = true,
                        onReset = resetAction(
                            player.lyricOverlayTextScale != SettingsDefaults.OVERLAY_TEXT_SCALE,
                        ) { onLyricOverlayTextScale(SettingsDefaults.OVERLAY_TEXT_SCALE) },
                    )
                    ActionRow(
                        title = "悬浮歌词对齐",
                        subtitle = if (player.lyricOverlayAlign == 1) {
                            "歌词在窗内靠左排，适合长句与固定视线"
                        } else {
                            "歌词在窗内水平居中（窗口位置由拖动决定）"
                        },
                        onClick = { overlayChoice = OverlayChoice.ALIGN },
                        trailing = OVERLAY_ALIGN_LABELS[player.lyricOverlayAlign],
                        chevron = true,
                        onReset = resetAction(player.lyricOverlayAlign != SettingsDefaults.OVERLAY_ALIGN) {
                            onLyricOverlayAlign(SettingsDefaults.OVERLAY_ALIGN)
                        },
                    )
                    ActionRow(
                        title = "悬浮歌词底板",
                        subtitle = if (player.lyricOverlayBackdrop == 0) {
                            "无底板最干净，但亮色壁纸上白字可能看不清"
                        } else {
                            "垫一层底色，任何壁纸上都读得清"
                        },
                        onClick = { overlayChoice = OverlayChoice.BACKDROP },
                        trailing = OVERLAY_BACKDROP_LABELS[player.lyricOverlayBackdrop],
                        chevron = true,
                        onReset = resetAction(
                            player.lyricOverlayBackdrop != SettingsDefaults.OVERLAY_BACKDROP,
                        ) { onLyricOverlayBackdrop(SettingsDefaults.OVERLAY_BACKDROP) },
                    )
                    ActionRow(
                        title = "暂停时的悬浮歌词",
                        subtitle = if (player.lyricOverlayPauseMode == 1) {
                            "暂停即隐藏，恢复播放时再出现"
                        } else {
                            "淡出到半透明，方便看清唱到哪一句"
                        },
                        onClick = { overlayChoice = OverlayChoice.PAUSE },
                        trailing = OVERLAY_PAUSE_LABELS[player.lyricOverlayPauseMode],
                        chevron = true,
                        onReset = resetAction(
                            player.lyricOverlayPauseMode != SettingsDefaults.OVERLAY_PAUSE_MODE,
                        ) { onLyricOverlayPauseMode(SettingsDefaults.OVERLAY_PAUSE_MODE) },
                    )

                    // v1.1.4 新增三项：宽度滑块 + 两处颜色。都紧跟在五个外观选项之后，仍属「外观」范畴。
                    // 宽度之所以是滑块而不是档位：屏幕尺寸千差万别，档位永远差一个「刚好」。
                    SliderRow(
                        title = "悬浮歌词宽度 ${player.lyricOverlayWidthPercent}%",
                        // 宽度与「横向能拖多远」是同一件事的两面，到了 100% 就直接说破，
                        // 否则用户会像 2026-10-16 那样把「拖不动」当 bug 报回来。
                        subtitle = if (player.lyricOverlayWidthPercent >= 100) {
                            "已占满屏宽，悬浮窗无法左右移动；调小一点即可拖到屏幕任意位置"
                        } else {
                            "屏幕宽度的百分比（1–100%）。窗口越窄，能左右移动的范围越大；" +
                                "窗口越宽，长句折行越晚"
                        },
                        value = player.lyricOverlayWidthPercent.toFloat(),
                        range = 1f..100f,
                        // 拖动时逐帧写回：宽度与屏幕是直接的比例关系，松手才变会让人
                        // 以为「拖了半天没反应」。引擎侧每次都会重摆窗口，正好当实时预览用。
                        onValueChange = { onLyricOverlayWidthPercent(it.toInt().coerceIn(1, 100)) },
                        onReset = resetAction(
                            player.lyricOverlayWidthPercent != SettingsDefaults.OVERLAY_WIDTH_PERCENT,
                        ) { onLyricOverlayWidthPercent(SettingsDefaults.OVERLAY_WIDTH_PERCENT) },
                    )
                    ActionRow(
                        title = "悬浮歌词文字颜色",
                        subtitle = "还没唱到的那部分文字的颜色，默认纯白",
                        onClick = { colorPicker = ColorSlot.OVERLAY_TEXT },
                        swatch = player.lyricOverlayTextColor,
                        chevron = true,
                        onReset = resetAction(
                            player.lyricOverlayTextColor != SettingsDefaults.OVERLAY_TEXT_COLOR,
                        ) { onLyricOverlayTextColor(SettingsDefaults.OVERLAY_TEXT_COLOR) },
                    )
                    ActionRow(
                        title = "悬浮歌词已唱颜色",
                        subtitle = "逐字高亮里已唱过的那一段，默认暖黄",
                        onClick = { colorPicker = ColorSlot.OVERLAY_SUNG },
                        swatch = player.lyricOverlaySungColor,
                        chevron = true,
                        onReset = resetAction(
                            player.lyricOverlaySungColor != SettingsDefaults.OVERLAY_SUNG_COLOR,
                        ) { onLyricOverlaySungColor(SettingsDefaults.OVERLAY_SUNG_COLOR) },
                    )
                    // 运行状态：开关打开且已授权时显示。这一行是排障的关键 ——
                    // 悬浮窗启动失败（挂窗被 ROM 拒绝 / 前台服务被系统拦）在
                    // 旧版本里是完全静默的，用户只看到「开关开了但什么都没发生」。
                    if (player.lyricOverlay && canDrawOverlay) {
                        val failed = overlayHealth.status == LyricOverlayBus.Status.FAILED
                        Text(
                            text = when (overlayHealth.status) {
                                LyricOverlayBus.Status.RUNNING ->
                                    "运行中：${overlayHealth.detail}，退到桌面即可看到歌词行"
                                LyricOverlayBus.Status.FAILED ->
                                    "启动失败：${overlayHealth.detail}。" +
                                        "可到 设置 → 应用 → 显示悬浮窗（部分系统叫「后台弹出界面」）确认，" +
                                        "详情见 关于与诊断 → 崩溃日志"
                                LyricOverlayBus.Status.IDLE -> "悬浮窗未启动"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                        )
                    }
                    if (overlayHintVisible && !canDrawOverlay) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = if (overlayJumpFailed) {
                                    "未能打开系统授权页（本机 ROM 未提供该入口）。\n" +
                                        "请手动前往：设置 → 应用 → 听风 → 权限 → " +
                                        "「显示悬浮窗」或「出现在其他应用上层」→ 允许。\n" +
                                        "部分系统还需要同时允许「后台弹出界面」，否则授权后仍不显示。"
                                } else {
                                    "授权路径：设置 → 应用 → 权限 → 出现在其他应用上 → 允许。\n" +
                                        "部分比亚迪车机（DiLink）需要把本应用加入白名单，或先强停一次应用进程，" +
                                        "否则授权后界面可能黑屏或歌词不显示。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    // 位置调整：开关打开且已授权时才出现。进入后悬浮窗临时可拖动，
                    // 拖完点「完成」锁回穿透态。
                    if (player.lyricOverlay && canDrawOverlay) {
                        ActionRow(
                            title = if (player.overlayEditing) "完成调整" else "调整悬浮歌词位置",
                            subtitle = if (player.overlayEditing) {
                                "拖动屏幕上的歌词（横竖都可以）到合适位置，然后点这里锁定（锁定后点击穿透）"
                            } else {
                                "临时解锁，把歌词拖到想要的位置；位置会记住，锁定后不挡触摸"
                            },
                            onClick = { onOverlayEditing(!player.overlayEditing) },
                        )
                    }

                    // v1.1.3：三个子页入口行已升级为主页的六张分类卡（见本文件开头的
                    // SettingsPage.ROOT 分支）—— 原来这里只有音乐库 / 更新 / 关于与诊断
                    // 三条 ActionRow，现在外观 / 播放 / 歌词也各自成了分类卡。
                }

                // ================================================== 子页：音乐库
                // 一次性配置（短音频过滤）。配完就不用再进来了。
                if (page == SettingsPage.LIBRARY) {

                    // ------------------------------------------------ 本地曲库
                    SectionTitle("本地曲库")
                    ActionRow(
                        title = if (minSongDurationSec > 0) "过滤短音频（< ${minSongDurationSec} 秒）" else "过滤短音频（未开启）",
                        subtitle = "时长已知且低于阈值的音频不会进入曲库（滤掉提示音、转场音效等）。" +
                            "修改后自动重新扫描；填 0 关闭过滤",
                        onClick = { showMinDurationDialog = true },
                        chevron = true,
                        onReset = resetAction(minSongDurationSec != SettingsDefaults.MIN_DURATION_SEC) {
                            onMinSongDurationSec(SettingsDefaults.MIN_DURATION_SEC)
                        },
                    )
                }

                // ================================================== 子页：关于与诊断
                // 只读信息 + 排障工具。看一次就够的内容不该和开关抢首页的位置。
                if (page == SettingsPage.DIAGNOSTICS) {

                    // ------------------------------------------------ 播放统计
                    SectionTitle("播放统计")
                    val stats = library.playStats
                    StatCard(
                        rows = listOf(
                            "歌曲总数" to "${library.songs.size} 首",
                            "播放过的歌曲" to "${stats.listenedSongs} 首",
                            "累计播放次数" to "${stats.totalPlays} 次",
                            "累计收听时长" to formatListened(stats.totalListenedMs),
                            "收藏" to "${stats.favorites} 首",
                        )
                    )
                    ActionRow(
                        title = "收听报告",
                        subtitle = "按天曲线 · 时段分布 · 最常播放 / 最长收听榜（全部来自本地记录）",
                        onClick = onOpenReport,
                        chevron = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 不可撤销 → 二次确认
                    TextButton(onClick = { showClearStatsConfirm = true }) {
                        Text("清空播放统计（保留收藏）")
                    }

                    // ------------------------------------------------ 数据与存储
                    // 进入本页时刷新一次自检：曲库 / 目录 / 歌单 / 缓存的实时占用。
                    LaunchedEffect(Unit) { onRefreshMaintenance() }
                    SectionTitle("数据与存储")
                    StatCard(
                        rows = listOf(
                            "曲库歌曲" to if (maintenance == null) "—" else "${maintenance.songs} 首",
                            "暂不可见" to if (maintenance == null) "—" else "${maintenance.missing} 首",
                            "已移出曲库" to if (maintenance == null) "—" else "${maintenance.excluded} 首",
                            "导入目录" to if (maintenance == null) "—" else "${maintenance.folders} 个",
                            "歌单" to if (maintenance == null) "—" else "${maintenance.playlists} 个",
                            "封面缓存" to (maintenance?.coverSizeText ?: "—"),
                            "数据库占用" to (maintenance?.dbSizeText ?: "—"),
                        )
                    )
                    ActionRow(
                        title = "清理失效歌曲",
                        subtitle = if ((maintenance?.missing ?: 0) > 0) {
                            "${maintenance!!.missing} 首歌曲的文件已不在本机，清理后其收藏与统计一并移除"
                        } else {
                            "文件不在本机的歌曲会保留记录（文件回来后自动恢复）"
                        },
                        onClick = { showCleanupConfirm = true },
                    )
                    // 「移出曲库」是这个应用里唯一一个"歌还在、但被用户主动藏起来"的状态：
                    // 不给恢复入口的话，用户误点一次就再也找不回来（重扫也不会收）。
                    ActionRow(
                        title = "恢复已移出曲库的歌曲",
                        subtitle = if ((maintenance?.excluded ?: 0) > 0) {
                            "${maintenance!!.excluded} 首被「移出曲库」，恢复后会重新扫描一次"
                        } else {
                            "没有歌被移出曲库（长按歌曲 → 移出曲库 会记在这里）"
                        },
                        onClick = onRestoreExcluded,
                    )
                    ActionRow(
                        title = "重复歌曲清理",
                        subtitle = "按「标题 + 歌手 + 时长」找重复，每组留一首，其余移出曲库（不删文件）",
                        onClick = onOpenDuplicates,
                        chevron = true,
                    )
                    ActionRow(
                        title = "清空封面缓存",
                        subtitle = "只删掉本地可重新生成的封面图，不影响收藏与歌单",
                        onClick = onClearCoverCache,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 不可撤销 → 二次确认
                    TextButton(onClick = { showResetDataConfirm = true }) {
                        Text("清空全部用户数据（保留曲库）")
                    }
                    TextButton(onClick = { showResetLibraryConfirm = true }) {
                        Text("清空曲库与导入目录（保留收藏与统计）")
                    }

                    // ------------------------------------------------ 悬浮歌词自检
                    // 悬浮歌词链路跨 6 个环节，任何一环断掉用户侧都是同一个「没反应」。
                    // 这个入口把整条链路一次测完，直接指出断点，省去猜和反复重装。
                    SectionTitle("悬浮歌词")
                    ActionRow(
                        title = "悬浮歌词自检",
                        subtitle = "逐项检查 授权 → 开关 → 服务 → 窗口 → 内容，指出断在哪一环",
                        onClick = {
                            overlayCheck = LyricOverlayBridge.snapshot(context)
                            showOverlayCheck = true
                        },
                        chevron = true,
                    )

                    // ------------------------------------------------ 崩溃日志
                    SectionTitle("崩溃日志")
                    ActionRow(
                        title = if (crashLogCount > 0) "查看日志（共 $crashLogCount 条）" else "暂无崩溃记录",
                        subtitle = "崩溃信息只保存在本机，不会自动上传，可导出后反馈",
                        onClick = onLoadCrashLog,
                    )
                    if (crashLogText != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = crashLogText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                                maxLines = 24,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onExportCrashLog, enabled = crashLogCount > 0) {
                            Text("导出日志")
                        }
                        // 不可撤销 → 二次确认
                        TextButton(
                            onClick = { showClearCrashConfirm = true },
                            enabled = crashLogCount > 0,
                        ) { Text("清空日志") }
                    }

                    // ------------------------------------------------ 关于
                    SectionTitle("关于")
                    StatCard(
                        rows = listOf(
                            "应用" to "听风 WindTone",
                            "版本" to version,
                            "包名" to context.packageName,
                            "最低系统" to "Android 7.0（API 24）",
                            // v1.0.7 起 Release 按 ABI 分包，这里显示本机架构，方便对照下载
                            "CPU 架构" to UpdateChecker.primaryAbiLabel().ifBlank { "—" },
                            "更新来源" to "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}",
                        )
                    )
                    Text(
                        "歌单、收藏、播放统计、歌词等全部数据都只保存在本机；" +
                            "歌词优先读取音频同目录的 .lrc / .krc / .yrc 文件，其次读取音频内嵌歌词。\n" +
                            "联网行为只有一处：「检查更新 / 下载新版本」访问 GitHub Releases" +
                            "（检查更新只走 GitHub 官方地址；下载安装包可选择第三方加速镜像，可在「下载线路」中调整）。" +
                            "不采集、不上传任何信息。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                // ================================================== 子页：更新
                // 「检查更新」是偶尔要点的动作、「自动检查」是开关、「下载线路」是排障 ——
                // 三件事性质不同，但都属于「更新」，独立成一页就够。
                if (page == SettingsPage.UPDATE) {
                    SectionTitle("更新")
                    val info = update.info
                    ActionRow(
                        title = when {
                            update.checking -> "正在检查更新…"
                            info != null -> "发现新版本 v${info.version}"
                            else -> "检查更新"
                        },
                        subtitle = when {
                            update.checking -> "正在访问 GitHub Releases…"
                            info != null ->
                                "当前 v${update.currentVersion} → 最新 v${info.version}" +
                                    UpdateChecker.formatSize(info.apkSizeBytes)
                                        .takeIf { it.isNotBlank() }
                                        ?.let { " · $it" }
                                        .orEmpty()

                            else -> "当前版本 v${update.currentVersion} · 安装包由 GitHub Releases 分发"
                        },
                        onClick = onCheckUpdate,
                    )
                    SwitchRow(
                        title = "启动时自动检查更新",
                        subtitle = "关闭后应用不再发起任何网络请求（在线更新是唯一的联网功能）",
                        checked = autoCheckUpdate,
                        onCheckedChange = onAutoCheckUpdate,
                        onReset = resetAction(autoCheckUpdate != SettingsDefaults.AUTO_UPDATE) {
                            onAutoCheckUpdate(SettingsDefaults.AUTO_UPDATE)
                        },
                    )
                    ActionRow(
                        title = "下载线路",
                        subtitle = "${downloadRoute.label} · ${downloadRoute.hint}",
                        onClick = { showRouteDialog = true },
                        chevron = true,
                        onReset = resetAction(downloadRoute != SettingsDefaults.UPDATE_ROUTE) {
                            onDownloadRoute(SettingsDefaults.UPDATE_ROUTE)
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onOpenReleasePage) { Text("打开发布页面") }
                        if (info != null) {
                            TextButton(onClick = onIgnoreUpdate) { Text("忽略此版本") }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showEq) EqSheet(onClose = { showEq = false })

    // 车载模式的长说明：副标题只留一行摘要（原来 180 字把一条设置项撑成大半屏）
    if (showCarModeInfo) {
        AlertDialog(
            onDismissRequest = { showCarModeInfo = false },
            title = { Text("车载模式") },
            text = {
                Text(
                    "启动后直接进入播放页并锁定横屏，适合车机或手机横放。\n\n" +
                        "歌词页 5 秒无操作会自动收起顶栏、控制键与底部状态行，只留歌词、歌名、" +
                        "进度与时间；触摸任意位置恢复。切到封面态不收起。\n\n" +
                        "开启后，设备开机时应用会自动拉起（车载场景）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showCarModeInfo = false }) { Text("知道了") }
            },
        )
    }

    // 以下三处都会丢数据且不可撤销，统一走二次确认
    if (showClearStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStatsConfirm = false },
            title = { Text("清空播放统计？") },
            text = {
                Text(
                    "会清空播放次数、累计收听时长与「播放过的歌曲」；收藏与歌单保留。该操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearStats()
                        showClearStatsConfirm = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearStatsConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showClearCrashConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCrashConfirm = false },
            title = { Text("清空崩溃日志？") },
            text = {
                Text(
                    "本机保存的崩溃记录会被全部删除，删除后无法找回。" +
                        "如果是要反馈问题，建议先「导出日志」再清空。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCrashLog()
                        showClearCrashConfirm = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCrashConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showOverlayCheck && overlayCheck != null) {
        val snap = overlayCheck!!
        AlertDialog(
            onDismissRequest = { showOverlayCheck = false },
            title = { Text("悬浮歌词自检") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = snap.report(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 动作按「能否直接解决问题」排；全部就地执行，不关弹窗，
                    // 方便用户一件事做完立刻回到这里看数字有没有变。
                    TextButton(onClick = onOverlayPreview) { Text("显示预览 6 秒") }
                    if (!snap.permissionGranted || !snap.windowAttached) {
                        TextButton(
                            onClick = { context.startOverlayPermissionPage() },
                        ) { Text("去系统设置授权") }
                    }
                    TextButton(
                        onClick = {
                            // 重新拉起 = 走一遍开关打开的全流程（落盘 + 起服务 + 预览）
                            onLyricOverlay(true)
                            overlayCheck = LyricOverlayBridge.snapshot(context)
                        },
                    ) { Text("重新拉起服务") }
                    TextButton(
                        onClick = {
                            // 窗口跑到屏幕外时的唯一出路：桌面上根本抓不到它，
                            // 只能从这儿复位，并顺手给一次预览确认位置对不对。
                            LyricOverlayBridge.resetPosition(context)
                            onOverlayPreview()
                            overlayCheck = LyricOverlayBridge.snapshot(context)
                        },
                    ) { Text("重置位置并预览") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOverlayCheck = false }) { Text("关闭") }
            },
        )
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            title = { Text("清理失效歌曲？") },
            text = {
                Text(
                    "会删除「文件已不在本机」的歌曲记录，这些歌的收藏、播放统计、歌词偏移与歌单条目会随之消失。" +
                        "如果文件只是暂时不可用（外置存储未挂载、权限被撤销），先不要清理。该操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCleanupMissingSongs()
                        showCleanupConfirm = false
                    },
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showResetDataConfirm) {
        AlertDialog(
            onDismissRequest = { showResetDataConfirm = false },
            title = { Text("清空全部用户数据？") },
            text = {
                Text(
                    "会清空收藏、播放统计、逐曲歌词偏移、本地标签编辑、全部歌单、播放队列与搜索历史。" +
                        "曲库与已导入的目录会保留，歌单一旦清空无法恢复。该操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetUserData()
                        showResetDataConfirm = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDataConfirm = false }) { Text("取消") }
            },
        )
    }

    // 清空曲库：与「清空全部用户数据」互为镜像 —— 一个清数据留曲库，一个清曲库留数据。
    // 因为「留数据」这一半很容易被误解成"什么都没删"，文案必须写清双方各自保住了什么。
    if (showResetLibraryConfirm) {
        AlertDialog(
            onDismissRequest = { showResetLibraryConfirm = false },
            title = { Text("清空曲库与导入目录？") },
            text = {
                Text(
                    "会移除曲库里的全部歌曲记录与导入的目录（含已排出的目录），" +
                        "下次启动停在「请选择扫描范围」，需要重新选择要扫描的位置。\n\n" +
                        "收藏、播放统计、歌单与逐曲歌词偏移会保留 —— " +
                        "重扫到同一批文件时它们会自动接上。\n\n" +
                        "存储中的音频文件不会被删除。该操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetLibrary()
                        showResetLibraryConfirm = false
                    },
                ) { Text("清空曲库") }
            },
            dismissButton = {
                TextButton(onClick = { showResetLibraryConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showMinDurationDialog) {
        var input by remember { mutableStateOf(if (minSongDurationSec > 0) minSongDurationSec.toString() else "") }
        AlertDialog(
            onDismissRequest = { showMinDurationDialog = false },
            title = { Text("过滤短音频") },
            text = {
                Column {
                    Text(
                        "时长小于该秒数的音频不进入曲库；填 0 关闭过滤。默认 60 秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(4) },
                        label = { Text("最短时长（秒）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMinSongDurationSec(input.toIntOrNull()?.coerceIn(0, 3600) ?: 0)
                        showMinDurationDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showMinDurationDialog = false }) { Text("取消") }
            },
        )
    }

    // 悬浮歌词的五个外观选项共用同一个弹层：标题 / 说明 / 选项列表都由 [OverlayChoice] 提供，
    // 选中项右侧打 ✓，点任意一项即生效并关闭（改完引擎会给一次 6 秒前台预览）。
    overlayChoice?.let { choice ->
        val selected = when (choice) {
            OverlayChoice.LINES -> player.lyricOverlayLines
            OverlayChoice.TEXT_SCALE -> player.lyricOverlayTextScale
            OverlayChoice.ALIGN -> player.lyricOverlayAlign
            OverlayChoice.BACKDROP -> player.lyricOverlayBackdrop
            OverlayChoice.PAUSE -> player.lyricOverlayPauseMode
        }
        AlertDialog(
            onDismissRequest = { overlayChoice = null },
            title = { Text(choice.title) },
            text = {
                Column {
                    Text(
                        choice.intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    choice.options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    when (choice) {
                                        OverlayChoice.LINES -> onLyricOverlayLines(option.value)
                                        OverlayChoice.TEXT_SCALE -> onLyricOverlayTextScale(option.value)
                                        OverlayChoice.ALIGN -> onLyricOverlayAlign(option.value)
                                        OverlayChoice.BACKDROP -> onLyricOverlayBackdrop(option.value)
                                        OverlayChoice.PAUSE -> onLyricOverlayPauseMode(option.value)
                                    }
                                    overlayChoice = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (option.value == selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    option.hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (option.value == selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { overlayChoice = null }) { Text("关闭") }
            },
        )
    }

    // 三处歌词颜色共用同一个选择弹层（效果图 ㉙E）：跟随主题（仅播放页已唱色）/
    // 预设色板 / 自定义。选中即落盘，悬浮窗那两处还会顺带给一次 6 秒预览。
    colorPicker?.let { slot ->
        ColorPickerSheet(
            title = slot.title,
            intro = slot.intro,
            value = when (slot) {
                ColorSlot.LYRIC_SUNG -> player.lyricSungColor
                ColorSlot.OVERLAY_TEXT -> player.lyricOverlayTextColor
                ColorSlot.OVERLAY_SUNG -> player.lyricOverlaySungColor
            },
            canFollowTheme = slot.canFollowTheme,
            onPick = { argb ->
                when (slot) {
                    ColorSlot.LYRIC_SUNG -> onLyricSungColor(argb)
                    ColorSlot.OVERLAY_TEXT -> onLyricOverlayTextColor(argb)
                    ColorSlot.OVERLAY_SUNG -> onLyricOverlaySungColor(argb)
                }
            },
            onDismiss = { colorPicker = null },
        )
    }

    // 下载线路选择：检查更新永远走 GitHub 官方地址，这里只管安装包走哪条线
    if (showRouteDialog) {
        AlertDialog(
            onDismissRequest = { showRouteDialog = false },
            title = { Text("下载线路") },
            text = {
                Column {
                    Text(
                        "「检查更新」始终访问 GitHub 官方地址；只有下载安装包时可以选择走加速镜像。" +
                            "镜像有缓存，偶尔会慢或滞后，下载失败时换一条线路再试即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DownloadRoute.values().forEach { route ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onDownloadRoute(route)
                                    showRouteDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    route.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (route == downloadRoute) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    route.hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (route == downloadRoute) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRouteDialog = false }) { Text("完成") } },
        )
    }

    if (update.dialogVisible && (update.info != null || update.readyApkPath != null)) {
        UpdateDialog(
            update = update,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            onGrantPermission = onGrantInstallPermission,
            onOpenReleasePage = onOpenReleasePage,
            onIgnore = onIgnoreUpdate,
            onDismiss = onDismissUpdateDialog,
        )
    }
}

/**
 * 应用内更新对话框，三种形态：
 *  1. 发现新版本 → 展示更新说明 + 「下载并安装」
 *  2. 下载中     → 进度条（不可取消，避免留下半截文件）
 *  3. 下载完成   → 「立即安装」；未授权「安装未知应用」时先引导授权
 */
@Composable
private fun UpdateDialog(
    update: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = update.info

    if (update.downloading) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在下载新版本") },
            text = {
                Column {
                    Text(
                        "下载线路：${update.route.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (update.progress >= 0) "已下载 ${update.progress}%"
                        else "已下载 ${UpdateChecker.formatSize(update.downloadedBytes).ifBlank { "0 MB" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (update.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { update.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (update.totalBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${UpdateChecker.formatSize(update.downloadedBytes)} / " +
                                UpdateChecker.formatSize(update.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { },
        )
        return
    }

    if (update.readyApkPath != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("安装包已就绪") },
            text = {
                Text(
                    if (update.needInstallPermission) {
                        "系统需要你允许「听风」安装应用，才能继续升级。点击「去授权」后打开对应开关即可。"
                    } else {
                        "点击「立即安装」会调起系统安装器，确认后即可完成升级。" +
                            "歌单、收藏、播放统计都会保留。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = if (update.needInstallPermission) onGrantPermission else onInstall) {
                    Text(if (update.needInstallPermission) "去授权" else "立即安装")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )
        return
    }

    if (info == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${info.version}") },
        text = {
            Column {
                Text(
                    "当前版本 v${update.currentVersion}" +
                        UpdateChecker.formatSize(info.apkSizeBytes)
                            .takeIf { it.isNotBlank() }
                            ?.let { " · 新版本 $it" }
                            .orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "下载线路：${update.route.label}（可在「设置 → 更新 → 下载线路」中更换）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("该版本没有填写更新说明。", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenReleasePage) { Text("在浏览器中查看发布页") }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("下载并安装") } },
        dismissButton = { TextButton(onClick = onIgnore) { Text("忽略") } },
    )
}

// ---------------------------------------------------------------- 小组件

/** 选项弹层里的一项 */
private data class OverlayOption(val value: Int, val label: String, val hint: String)

/**
 * 悬浮歌词五个外观选项（行数 / 字号 / 对齐 / 底板 / 暂停表现）。
 *
 * 把「标题 + 说明 + 选项列表」一起收进枚举，是为了让五个设置项共用同一个弹层实现——
 * 每多一个选项就多复制一份 `AlertDialog` 是不可维护的，而且样式迟早会走样。
 */
private enum class OverlayChoice(
    val title: String,
    val intro: String,
    val options: List<OverlayOption>,
) {
    LINES(
        "悬浮歌词行数",
        "桌面悬浮歌词一次显示几行。选中后立刻给一次 6 秒预览。",
        listOf(
            OverlayOption(1, "1 行", "只显示当前歌词行，放不下时自动折行"),
            OverlayOption(3, "3 行", "上一行 / 当前行 / 下一行，当前行高亮"),
        ),
    ),
    TEXT_SCALE(
        "悬浮歌词字号",
        "以正文为基准。大屏与车机建议调大一档；一行放不下会自动折行，不会横向溢出屏幕。",
        listOf(
            OverlayOption(0, "小", "约正文的 0.8 倍"),
            OverlayOption(1, "中", "默认，与播放页歌词接近"),
            OverlayOption(2, "大", "约正文的 1.25 倍"),
        ),
    ),
    ALIGN(
        "悬浮歌词对齐",
        "歌词文字在悬浮窗**内部**怎么排（窗口在屏幕上的位置由「调整悬浮歌词位置」自由拖动，与此无关）。" +
            "居中适合每句都不长的歌词；居左更适合长句——每行都从同一条竖线起排，" +
            "读起来比居中的参差边缘省力。",
        listOf(
            OverlayOption(0, "居中", "窗内每行居中，默认"),
            OverlayOption(1, "居左", "窗内每行从左沿起排"),
        ),
    ),
    BACKDROP(
        "悬浮歌词底板",
        "字幕下面垫什么。亮色壁纸上「无底板的白字」会看不清——投影在高分屏上几乎等于没有。",
        listOf(
            OverlayOption(0, "无底板", "最干净，需要壁纸足够深"),
            OverlayOption(1, "半透明", "默认，任何壁纸上都读得清"),
            OverlayOption(2, "深色实底", "对比最强，适合花哨壁纸"),
        ),
    ),
    PAUSE(
        "暂停时的悬浮歌词",
        "暂停之后字幕怎么处理。",
        listOf(
            OverlayOption(0, "淡出", "默认，保留最后一句便于回看唱到哪"),
            OverlayOption(1, "隐藏", "暂停即消失，桌面更干净"),
        ),
    ),
}

/** 设置页行尾摘要用的短标签（与弹层选项一一对应，按档位下标取） */
private val OVERLAY_TEXT_SCALE_LABELS = listOf("小", "中", "大")
private val OVERLAY_ALIGN_LABELS = listOf("居中", "居左")
private val OVERLAY_BACKDROP_LABELS = listOf("无底板", "半透明", "深色实底")
private val OVERLAY_PAUSE_LABELS = listOf("淡出", "隐藏")

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/**
 * 「恢复默认」按钮的判据包装。
 *
 * `needsReset` 为真时给出动作，否则返回 `null` —— 行组件见到 `null` 就不画 ↺。
 * 把「当前值是否偏离出厂默认」和「点下去写回什么」放在同一个表达式里，
 * 是为了让两处不可能对不上：判据和动作相隔二十行、改一处忘一处，是这类
 * 「重置按钮把值写歪了」的典型来路。
 */
private fun resetAction(needsReset: Boolean, action: () -> Unit): (() -> Unit)? =
    if (needsReset) action else null

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfo: (() -> Unit)? = null,
    /** 非空时在开关左侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入） */
    onReset: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                // 副标题放不下的细节（如车载模式的收起规则 / 开机自启）挂在这里，点开是弹窗
                if (onInfo != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        AppIcons.Info,
                        contentDescription = "查看详细说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onInfo),
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onReset != null) {
            ResetButton(onReset)
            Spacer(Modifier.width(4.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    subtitle: String? = null,
    /** 非空时在标题行右侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入） */
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (onReset != null) ResetButton(onReset)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

/**
 * 设置主页的**二级分类入口卡**（v1.1.3 新增，效果图 ㉙ 的 `.cat-row`）。
 *
 * 与 [ActionRow] 的分工（效果图原话：「子页入口行与分类卡的区别」）：
 *  - [CategoryRow] = **一整类设置的门面**，带左侧语义图标 + 摘要，只出现在设置主页；
 *  - [ActionRow] = **某一类设置里的一条**，无图标，出现在子页里。
 *
 * 规格：40dp 图标容器（圆角 12、secondaryContainer 底）+ 标题 15sp/600 +
 * 摘要 11sp + 可选右侧值（11sp、primary）+ 18dp chevron；卡片圆角 14dp、
 * 内边距 11×14、卡间距 8dp。chevron 规范自 v1.1.3 起放宽一档 ——
 * **只有 CategoryRow 与 ActionRow** 可带箭头，`OptionRow`（弹层行）不变。
 */
@Composable
private fun CategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            // .cat-row{margin-top:8px} —— 卡与卡之间靠它分隔，不加 SectionTitle
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(14.dp))
                Text(
                    text = trailing,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 设置页的可点行。
 *
 * @param trailing 右侧的当前值摘要（如「过滤 < 60 秒」「已是最新」）——把状态前置到首页，
 *   用户不用进子页就知道里面是什么状况。
 * @param chevron 是否显示右箭头。**只给「点开会进入另一个界面」的行开**（子页入口、
 *   打开弹层/弹窗的行）；就地执行动作（如「检查更新」）或就地展开（如「查看日志」）的行不加。
 * @param swatch 右侧的当前颜色预览圆点（颜色类设置行专用；`null` 表示不画）。
 *   「跟随主题」没有固定色，调用方传 `null`，改由 [trailing] 的文字表达。
 * @param onReset 非空时在右侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入）。
 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: String? = null,
    chevron: Boolean = false,
    swatch: Int? = null,
    onReset: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ↺ 也是 clickable，且嵌在本行的 clickable 里。Compose 的点击手势由**最内层**
            // 拿到并消费，所以点 ↺ 只复位、不会顺带打开本行该进的那个界面。
            if (onReset != null) {
                Spacer(Modifier.width(6.dp))
                ResetButton(onReset)
            }
            if (swatch != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
            if (chevron) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCard(rows: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            rows.forEach { (k, v) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(112.dp),
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 歌词颜色（v1.1.4）

/**
 * 三处可配的歌词颜色。
 *
 * 三处共用同一个选择弹层 —— 它们的界面完全一样，只有「写回哪个字段」不同，
 * 复制三份 `ModalBottomSheet` 只会让样式迟早走样。
 */
private enum class ColorSlot(
    val title: String,
    val intro: String,
    /** 是否提供「跟随主题」。只有播放页的已唱色能跟随主题（它默认就跟着 primary 走）。 */
    val canFollowTheme: Boolean,
) {
    /** 播放页逐字歌词里「已唱过的那一段」 */
    LYRIC_SUNG(
        "已唱部分的颜色",
        "逐字歌词里已经唱过的那一段。选「跟随主题」就交给封面取色，随歌曲一起变化。",
        true,
    ),

    /** 悬浮窗里还没唱到的正文 */
    OVERLAY_TEXT(
        "悬浮歌词文字颜色",
        "桌面悬浮歌词里还没唱到的那部分。悬浮窗是固定色，不跟随主题。",
        false,
    ),

    /** 悬浮窗里已唱过的那一段 */
    OVERLAY_SUNG(
        "悬浮歌词已唱颜色",
        "桌面悬浮歌词里当前行已经唱过的那一段。还没唱到的部分由「文字颜色」单独控制。",
        false,
    ),
}

/**
 * 颜色弹层的预设色板（效果图 ㉙E 里的 8 个 `.swatch`）。
 *
 * 刻意不做成枚举：枚举承载不了**用户自定义**的颜色，而这三处的取值
 * 既可能是预设、也可能是任意 ARGB，还可能是「跟随主题」哨兵。
 */
internal object LyricsColors {
    /** 弹层里自定义滑块的起点色（暖黄，与悬浮窗已唱色的默认值同色系） */
    const val DEFAULT_SEED = 0xFFFFCD56.toInt()

    data class Preset(val name: String, val argb: Int)

    /** 白 / 暖黄 / 天蓝 / 青绿 / 草绿 / 桃粉 / 橘橙 / 雾紫 */
    val presets = listOf(
        Preset("纯白", 0xFFFFFFFF.toInt()),
        Preset("暖黄", 0xFFFFCD56.toInt()),
        Preset("天蓝", 0xFF4FC3F7.toInt()),
        Preset("青绿", 0xFF4DD0E1.toInt()),
        Preset("草绿", 0xFF81C784.toInt()),
        Preset("桃粉", 0xFFF06292.toInt()),
        Preset("橘橙", 0xFFFF7043.toInt()),
        Preset("雾紫", 0xFFBA68C8.toInt()),
    )

    /**
     * 取一个颜色的人话名字：哨兵 →「跟随主题」，预设 → 色名，其余 → `#RRGGBB`。
     *
     * 设置行右侧要回答「现在是什么颜色」，直接印 `-12593` 没人看得懂。
     */
    fun label(argb: Int): String = when (argb) {
        SettingsDefaults.COLOR_FOLLOW_THEME -> "跟随主题"
        else -> presets.firstOrNull { it.argb == argb }?.name ?: hex(argb)
    }

    /** ARGB → `#RRGGBB`（丢掉 alpha：这三处颜色都是不透明的） */
    fun hex(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return "#" + rgb.toString(16).uppercase().padStart(6, '0')
    }
}

/**
 * 颜色选择弹层（效果图 ㉙E，三处歌词颜色共用）。
 *
 * 三段式 —— **跟随主题 / 预设色板 / 自定义**：
 *  - 「跟随主题」只对播放页已唱色出现；悬浮窗那两处是固定色，没有这一项。
 *  - 预设 8 色覆盖「白 / 暖 / 冷 / 亮」几类常见偏好，车机上一点就中。
 *  - 自定义给色相 / 饱和度 / 亮度三个滑块 + 一条实时预览条。
 *
 * **为什么不用色轮**：车机上手指粗、精度差，色轮边缘的色相极难选准；
 * 三个滑块 + 预览条的组合在大屏上反而更好操作。
 *
 * 选中即落盘（[onPick]）——悬浮窗那两处会顺带触发一次 6 秒前台预览，
 * 不用退到桌面去验证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerSheet(
    title: String,
    intro: String,
    value: Int,
    canFollowTheme: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 「跟随主题」没有具体颜色可回填，自定义区从 [LyricsColors.DEFAULT_SEED] 起步 ——
    // 不从随机色、也不从上一个弹层留下的色相起步，滑块初始位置每次一样。
    val seed = if (value == SettingsDefaults.COLOR_FOLLOW_THEME) {
        LyricsColors.DEFAULT_SEED
    } else {
        value
    }
    // 用 FloatArray 但每次都赋**新数组**：原地改元素不会触发重组，这个坑很安静。
    var hsv by remember(value) { mutableStateOf(hsvOf(seed)) }
    val custom = android.graphics.Color.HSVToColor(hsv)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                intro,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (canFollowTheme) {
                SectionTitle("跟随主题")
                PickRow(
                    title = "跟随主题",
                    subtitle = "从当前歌曲封面取色，随主题一起变化",
                    selected = value == SettingsDefaults.COLOR_FOLLOW_THEME,
                    onClick = {
                        onPick(SettingsDefaults.COLOR_FOLLOW_THEME)
                        onDismiss()
                    },
                )
            }

            SectionTitle("预设")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LyricsColors.presets.forEach { preset ->
                    ColorSwatch(
                        argb = preset.argb,
                        selected = value == preset.argb,
                        onClick = {
                            onPick(preset.argb)
                            onDismiss()
                        },
                    )
                }
            }

            SectionTitle("自定义")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(custom))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            )
            // 三个滑块只在**松手**时才写回：拖动时逐帧 setLyricOverlayXxxColor 会连着
            // 触发预览，把浮动窗口的排版刷成流水；预览条本身逐帧在变，反馈并不缺。
            HsvSlider(
                label = "色相",
                value = hsv[0],
                range = 0f..360f,
                onChange = { hsv = floatArrayOf(it, hsv[1], hsv[2]) },
                onCommit = { onPick(android.graphics.Color.HSVToColor(hsv)) },
            )
            HsvSlider(
                label = "饱和度",
                value = hsv[1],
                range = 0f..1f,
                onChange = { hsv = floatArrayOf(hsv[0], it, hsv[2]) },
                onCommit = { onPick(android.graphics.Color.HSVToColor(hsv)) },
            )
            HsvSlider(
                label = "亮度",
                value = hsv[2],
                range = 0f..1f,
                onChange = { hsv = floatArrayOf(hsv[0], hsv[1], it) },
                onCommit = { onPick(android.graphics.Color.HSVToColor(hsv)) },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = {
                        onPick(custom)
                        onDismiss()
                    },
                ) { Text("使用这个颜色") }
            }
        }
    }
}

/** 弹层里「跟随主题」那一行：左标题 + 右 ✓（无箭头，与效果图的 `.row-action` 一致） */
@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 预设色板里的一颗色点：34dp 圆，选中时描边加粗并在中心叠一个 ✓ */
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = contrastTint(argb),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 自定义区的一个分量滑块（色相 / 饱和度 / 亮度） */
@Composable
private fun HsvSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, fontSize = 13.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * 在给定底色上叠 ✓ 时该用黑还是白。
 *
 * 用 BT.601 的感知亮度而不是 RGB 算术均值：纯黄与纯蓝的算术均值一模一样，
 * 但人眼看黄的亮度接近白、蓝的亮度接近黑，按算术均值选会正好选反。
 */
private fun contrastTint(argb: Int): Color {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val luma = 0.299f * r + 0.587f * g + 0.114f * b
    return if (luma > 150f) Color(0xFF1B1B1B) else Color.White
}

/** ARGB → `[色相 0..360, 饱和度 0..1, 亮度 0..1]`（自定义滑块的初始位置） */
private fun hsvOf(argb: Int): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv
}

/** 累计收听时长的友好显示 */
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
