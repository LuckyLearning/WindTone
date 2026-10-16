package com.luckylearning.windtone.ui

import android.content.Context
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
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
import com.luckylearning.windtone.playback.Pitch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设置页六个子页的正文（v1.1.6 从 `SettingsScreen.kt` 拆出）。
 *
 * 拆分理由：`SettingsScreen.kt` 一度 **2165 行**，远超本项目 §8.6 定的「600 行/文件」。
 * 拆法只有一条内聚规则 —— **按页面切**，不按「组件 / 工具」这类横切面切：
 * 每个子页连同它自己那几处弹层一起搬过来，页面内用到的显示状态（哪个弹窗开着）
 * 也留在页面里，父级不再替它持有。
 *
 * 这样做的直接后果是 [SettingsScreen] 里那十几个 `var showXxx by remember`
 * 全部消失 —— 它们本来就是「属于某个页面的私事」，放在顶层只是历史原因。
 *
 * ⚠️ 搬迁是**纯位移**：没有改任何逻辑，参数的对应关系由编译器保证
 * （`SettingsScreenTest` 的六张卡导航用例仍会跑一遍入口）。
 */


// ================================================================ 子页：关于与诊断 / 更新

// ================================================================ 子页：关于与诊断

@Composable
internal fun SettingsDiagnosticsPage(
    player: PlayerUiState,
    library: LibraryUiState,
    maintenance: DataMaintenance.Report?,
    crashLogCount: Int,
    crashLogText: String?,
    onRefreshMaintenance: () -> Unit,
    onCleanupMissingSongs: () -> Unit,
    onClearCoverCache: () -> Unit,
    onResetUserData: () -> Unit,
    onResetLibrary: () -> Unit,
    onClearStats: () -> Unit,
    onRestoreExcluded: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onLoadCrashLog: () -> Unit,
    onExportCrashLog: () -> Unit,
    onClearCrashLog: () -> Unit,
    onOverlayPreview: () -> Unit,
    onLyricOverlay: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
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
}

// ================================================================ 子页：更新

@Composable
internal fun SettingsUpdatePage(
    update: UpdateUiState,
    downloadRoute: DownloadRoute,
    autoCheckUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onAutoCheckUpdate: (Boolean) -> Unit,
    onDownloadRoute: (DownloadRoute) -> Unit,
    onOpenReleasePage: () -> Unit,
    onIgnoreUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
) {
    var showRouteDialog by remember { mutableStateOf(false) }

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