package com.luckylearning.windtone.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.data.UpdateChecker

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
    onLoadCrashLog: () -> Unit,
    onExportCrashLog: () -> Unit,
    onClearCrashLog: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onAutoCheckUpdate: (Boolean) -> Unit,
    onOpenReleasePage: () -> Unit,
    onIgnoreUpdate: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    // WebDAV 云盘
    webdavConfigured: Boolean,
    webdavEnabled: Boolean,
    webdavScanning: Boolean,
    webdavSongCount: Int,
    webdavMessage: String?,
    onWebDavConnect: (url: String, user: String, password: String) -> Unit,
    onWebDavScan: () -> Unit,
    onWebDavDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    var showEq by remember { mutableStateOf(false) }
    // 用户点过悬浮歌词开关但还没授权 → 展开下方授权指引，授权成功返回后自动收起
    var overlayHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(canDrawOverlay) {
        if (canDrawOverlay) overlayHintVisible = false
    }
    // WebDAV 表单（只在未连接时展示；连接成功后表单消失，换成状态卡）
    var davUrl by remember(webdavConfigured) { mutableStateOf("") }
    var davUser by remember(webdavConfigured) { mutableStateOf("") }
    var davPass by remember(webdavConfigured) { mutableStateOf("") }

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
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 车机横屏可用宽度约 1700dp，若内容铺满整行，开关会被推到离文字
                    // 一两千像素远的地方，视线要在两端来回跳。这里限宽并水平居中；
                    // 手机宽度本就小于该上限，约束不生效，行为完全不变。
                    .widthIn(max = if (player.carMode) 1100.dp else 720.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = if (player.carMode) 32.dp else 18.dp),
            ) {
                // -------------------------------------------------- 外观
                SectionTitle("外观")
                SwitchRow(
                    title = "主题跟随封面取色",
                    subtitle = "从当前歌曲封面提取主色，驱动整个界面的配色",
                    checked = dynamicTheme,
                    onCheckedChange = onDynamicTheme,
                )
                SwitchRow(
                    title = "车载模式",
                    subtitle = "启动直接进入播放页并锁横屏（车机 / 手机横置）；歌词页 5 秒无操作自动收起顶栏、歌名行、控制键与状态行，只留歌词、进度与时间；触摸任意位置恢复。切到封面态不收起。开启后设备开机时会自动拉起本应用（车载场景）",
                    checked = player.carMode,
                    onCheckedChange = onCarMode,
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

                // -------------------------------------------------- 歌词
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

                // -------------------------------------------------- 播放
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

                // -------------------------------------------------- 音效
                SectionTitle("音效")
                ActionRow(
                    title = "均衡器与音效",
                    subtitle = "均衡器 / 低音增强 / 环绕声 / 响度增强（需先开始播放）",
                    onClick = { showEq = true },
                )

                // -------------------------------------------------- 云盘音乐
                SectionTitle("云盘音乐（WebDAV）")
                if (!webdavConfigured) {
                    Text(
                        "填写 WebDAV 服务器地址即可把云端音乐合并进曲库在线播放。" +
                            "家里的 NAS（群晖 / 威联通等）或 Alist、OpenList 都支持；" +
                            "把百度网盘挂载成 WebDAV 也可以（见项目文档的调研结论）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    OutlinedTextField(
                        value = davUrl,
                        onValueChange = { davUrl = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://nas.local:5244/dav") },
                        singleLine = true,
                        enabled = !webdavScanning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = davUser,
                        onValueChange = { davUser = it },
                        label = { Text("账号（可留空）") },
                        singleLine = true,
                        enabled = !webdavScanning,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = davPass,
                        onValueChange = { davPass = it },
                        label = { Text("密码（可留空）") },
                        singleLine = true,
                        enabled = !webdavScanning,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = { onWebDavConnect(davUrl, davUser, davPass) },
                        enabled = !webdavScanning && davUrl.isNotBlank(),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(if (webdavScanning) "正在连接…" else "连接并扫描")
                    }
                } else {
                    StatCard(
                        rows = listOf(
                            "状态" to if (webdavEnabled) "已启用" else "已停用",
                            "服务器" to "已配置",
                            "云端歌曲" to "$webdavSongCount 首",
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = onWebDavScan,
                        enabled = !webdavScanning,
                    ) { Text(if (webdavScanning) "正在扫描…" else "重新扫描云端目录") }
                    TextButton(
                        onClick = onWebDavDisconnect,
                        enabled = !webdavScanning,
                    ) { Text("断开并清除配置") }
                    Text(
                        "云端歌曲与本地曲库合并展示，支持收藏、歌单与歌词（同目录同名 .lrc）。" +
                            "账号密码只保存在本机应用私有目录，不上传任何服务器。" +
                            "云端文件不会被本应用修改或删除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (webdavMessage != null) {
                    Text(
                        webdavMessage!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }

                // -------------------------------------------------- 数据
                SectionTitle("数据")
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
                TextButton(onClick = onClearStats) { Text("清空播放统计（保留收藏）") }

                // -------------------------------------------------- 崩溃日志
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
                    TextButton(onClick = onClearCrashLog, enabled = crashLogCount > 0) {
                        Text("清空日志")
                    }
                }

                // -------------------------------------------------- 更新
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenReleasePage) { Text("打开发布页面") }
                    if (info != null) {
                        TextButton(onClick = onIgnoreUpdate) { Text("忽略此版本") }
                    }
                }

                // -------------------------------------------------- 关于
                SectionTitle("关于")
                StatCard(
                    rows = listOf(
                        "应用" to "听风 WindTone",
                        "版本" to version,
                        "包名" to context.packageName,
                        "最低系统" to "Android 7.0（API 24）",
                        "更新来源" to "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}",
                    )
                )
                Text(
                    "歌单、收藏、播放统计、歌词等全部数据都只保存在本机；" +
                        "歌词优先读取音频同目录的 .lrc / .krc / .yrc 文件，其次读取音频内嵌歌词。\n" +
                        "联网行为只有两处：「检查更新 / 下载新版本」访问 GitHub Releases；" +
                        "配置了 WebDAV 后访问你自己的服务器取音乐和歌词。" +
                        "不采集、不上传任何信息。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showEq) EqSheet(onClose = { showEq = false })

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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
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

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
