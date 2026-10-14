package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.UpdateChecker

/**
 * 设置页的层级：一个主页面 + 三个子页。
 *
 * 分层的理由见 docs/ui/index.html「四、设置页」：主页面只放**会反复改的开关 / 滑块**，
 * 一次性配置（短音频过滤）、只读信息（统计 / 版本 / 隐私）和排障工具
 * （崩溃日志 / 下载线路）各自收进子页 —— 它们平铺在同一条滚动里时，改一个开关要滚过
 * 六组无关内容。子页里按返回键先回主页面，不回退整个设置页。
 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    LIBRARY("音乐库"),
    UPDATE("更新"),
    DIAGNOSTICS("关于与诊断"),
}

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
    onOverlayEditing: (Boolean) -> Unit,
    canDrawOverlay: Boolean,
    onLyricScale: (Float) -> Unit,
    onLyricWordByWord: (Boolean) -> Unit,
    onNotifyLyrics: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onClearStats: () -> Unit,
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
    // 用户点过悬浮歌词开关但还没授权 → 展开下方授权指引，授权成功返回后自动收起
    var overlayHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(canDrawOverlay) {
        if (canDrawOverlay) overlayHintVisible = false
    }

    // 当前处于哪一层：主页面 / 音乐库 / 更新 / 关于与诊断
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    // 车载模式的长说明从副标题搬进弹窗，副标题只留一行摘要
    var showCarModeInfo by remember { mutableStateOf(false) }
    // 三处不可撤销的操作统一走二次确认
    var showClearStatsConfirm by remember { mutableStateOf(false) }
    var showClearCrashConfirm by remember { mutableStateOf(false) }

    // 子页里按返回键先回主页面（本处理器在子页才启用，主页面时交给外层关闭设置页）
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    // 切页时回到顶部，否则从长页面进子页会停在原来的滚动位置
    val pageScroll = rememberScrollState()
    LaunchedEffect(page) { pageScroll.scrollTo(0) }

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
                // ================================================== 主设置页
                // 只放会反复改的开关 / 滑块；一次性配置、只读信息与排障工具都在三个子页里。
                if (page == SettingsPage.ROOT) {

                    // ------------------------------------------------ 外观
                    SectionTitle("外观")
                    SwitchRow(
                        title = "主题跟随封面取色",
                        subtitle = "从当前歌曲封面提取主色，驱动整个界面的配色",
                        checked = dynamicTheme,
                        onCheckedChange = onDynamicTheme,
                    )
                    // 副标题原文 180 字，一条设置项就占掉大半屏；详细说明移到标题旁的「?」弹窗
                    SwitchRow(
                        title = "车载模式",
                        subtitle = "横屏大界面 · 歌词页自动收起 · 开机自启",
                        checked = player.carMode,
                        onCheckedChange = onCarMode,
                        onInfo = { showCarModeInfo = true },
                    )

                    // ------------------------------------------------ 播放
                    SectionTitle("播放")
                    SliderRow(
                        title = "默认播放速度 ${"%.2f".format(player.speed)}x",
                        value = player.speed,
                        range = 0.75f..2.0f,
                        onValueChange = onSpeed,
                    )
                    SwitchRow(
                        title = "跳过静音",
                        subtitle = "自动跳过录音开头的空白段（对整轨 / 现场录音有效）",
                        checked = player.skipSilence,
                        onCheckedChange = onSkipSilence,
                    )
                    ActionRow(
                        title = "均衡器与音效",
                        subtitle = "均衡器 / 低音增强 / 环绕声 / 响度增强（需先开始播放）",
                        onClick = { showEq = true },
                        chevron = true,
                    )

                    // ------------------------------------------------ 歌词
                    // 「歌词往哪儿显示」的三件事（逐字 / 通知栏 / 桌面悬浮）此前被拆在
                    // 「外观」与「歌词」两组里，现在统一收到这里。
                    SectionTitle("歌词")
                    SliderRow(
                        title = "歌词字号 ${"%.1f".format(player.lyricScale)}x",
                        value = player.lyricScale,
                        range = 0.7f..1.6f,
                        onValueChange = onLyricScale,
                    )
                    SwitchRow(
                        title = "逐字歌词",
                        subtitle = "当前行有逐字时间轴时，按字染色（.krc / .yrc / 增强型 LRC）",
                        checked = player.lyricWordByWord,
                        onCheckedChange = onLyricWordByWord,
                    )
                    SwitchRow(
                        title = "通知栏 / 锁屏显示歌词",
                        subtitle = "把当前歌词行下发到媒体通知（部分系统可能不显示副标题）",
                        checked = player.notifyLyrics,
                        onCheckedChange = onNotifyLyrics,
                    )
                    SwitchRow(
                        title = "桌面悬浮歌词",
                        subtitle = if (canDrawOverlay) {
                            "退到车机桌面 / 导航页后，在屏幕最上层显示当前歌词行（单行字幕，不挡触摸）"
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
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        // 带上包名，系统会直接定位到本应用的授权项，
                                        // 而不是打开应用总列表让用户自己找。
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                overlayHintVisible = true
                            }
                        },
                    )
                    if (overlayHintVisible && !canDrawOverlay) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = "授权路径：设置 → 应用 → 权限 → 出现在其他应用上 → 允许。\n" +
                                    "部分比亚迪车机（DiLink）需要把本应用加入白名单，或先强停一次应用进程，" +
                                    "否则授权后界面可能黑屏或歌词不显示。",
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
                                "拖动屏幕上的歌词到合适位置，然后点这里锁定（锁定后点击穿透）"
                            } else {
                                "临时解锁，把歌词拖到想要的位置；位置会记住，锁定后不挡触摸"
                            },
                            onClick = { onOverlayEditing(!player.overlayEditing) },
                        )
                    }

                    // ------------------------------------------------ 子页入口
                    // 右侧摘要把状态前置：不用进子页就知道有没有新版本。
                    // 右箭头是全 App 唯一使用 chevron 的地方，只标注「点开会进入另一个界面」。
                    Spacer(Modifier.height(6.dp))
                    ActionRow(
                        title = "音乐库",
                        subtitle = "短音频过滤",
                        onClick = { page = SettingsPage.LIBRARY },
                        trailing = if (minSongDurationSec > 0) "过滤 < ${minSongDurationSec} 秒" else "未开启",
                        chevron = true,
                    )
                    ActionRow(
                        title = "更新",
                        subtitle = "检查更新 · 自动检查 · 下载线路",
                        onClick = { page = SettingsPage.UPDATE },
                        trailing = if (update.info != null) "有新版本" else "v${update.currentVersion}",
                        chevron = true,
                    )
                    ActionRow(
                        title = "关于与诊断",
                        subtitle = "版本信息 · 播放统计 · 崩溃日志 · 隐私说明",
                        onClick = { page = SettingsPage.DIAGNOSTICS },
                        trailing = "v$version",
                        chevron = true,
                    )
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
                    Spacer(Modifier.height(8.dp))
                    // 不可撤销 → 二次确认
                    TextButton(onClick = { showClearStatsConfirm = true }) {
                        Text("清空播放统计（保留收藏）")
                    }

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
                    )
                    ActionRow(
                        title = "下载线路",
                        subtitle = "${downloadRoute.label} · ${downloadRoute.hint}",
                        onClick = { showRouteDialog = true },
                        chevron = true,
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

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfo: (() -> Unit)? = null,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

/**
 * 设置页的可点行。
 *
 * @param trailing 右侧的当前值摘要（如「过滤 < 60 秒」「已是最新」）——把状态前置到首页，
 *   用户不用进子页就知道里面是什么状况。
 * @param chevron 是否显示右箭头。**只给「点开会进入另一个界面」的行开**（子页入口、
 *   打开弹层/弹窗的行）；就地执行动作（如「检查更新」）或就地展开（如「查看日志」）的行不加。
 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: String? = null,
    chevron: Boolean = false,
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

/** 累计收听时长的友好显示 */
internal fun formatListened(ms: Long): String {
    if (ms < 60_000L) return "${ms / 1000} 秒"
    val totalMinutes = ms / 60_000L
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "$h 小时 $m 分钟" else "$m 分钟"
}
