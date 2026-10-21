package com.luckylearning.windtone.ui

import android.content.Context
import android.widget.Toast
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.playback.OnlineMediaCache
import com.luckylearning.windtone.data.BootStatus
import com.luckylearning.windtone.data.BootStatusText
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.ForegroundAppDetector
import com.luckylearning.windtone.overlay.ForegroundAppLog
import com.luckylearning.windtone.overlay.ForegroundAppState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.playback.Pitch
import com.luckylearning.windtone.ui.theme.windShadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 解锁调试分区要连点「关于」的次数（v1.1.31）。
 *
 * 5 次是经验值：足够挡住误触（没人会连点信息卡 5 下），又不至于让人点到怀疑人生。
 * 这是交互常量、不是设置项默认值，所以放在界面层而不是 `SettingsDefaults`。
 */
private const val DEBUG_UNLOCK_TAPS = 5

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
    /** 「前台应用记录」开关（v1.1.30 从歌词子页搬到这里） */
    onForegroundLogEnabled: (Boolean) -> Unit,
    /** 整份「不显示悬浮窗的应用」名单（v1.1.30：记录里可以一键加入） */
    onLyricOverlayHiddenApps: (Set<String>) -> Unit,
    /** 立刻走一次「开机后台起播」链路（v1.1.27，用于不重启也能验一遍） */
    onTestBootPlayback: () -> Unit,
    /** 调试分区解锁开关（v1.1.31；关于连点 5 次打开，分区内开关可关回去） */
    onDebugUnlocked: (Boolean) -> Unit,
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
    // 在线缓存占用（v1.1.38）：读一次即可，清除后就地刷新
    var onlineCacheBytes by remember { mutableStateOf(OnlineMediaCache.sizeBytes(context)) }
    // 开机自启的落盘证据（v1.1.27）：进页面读一次，随时可手动刷新
    var bootInfo by remember { mutableStateOf<BootStatus.Snapshot?>(null) }
    LaunchedEffect(Unit) { bootInfo = BootStatus.snapshot(context) }


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

    // ------------------------------------------------ 调试（默认隐藏，v1.1.31）
    //
    // 排障工具集中在这里 —— 它们都是**只在出问题时才用**的东西（查包名 / 查链路 / 查崩溃 / 查开机）。
    // ⚠️ 整段默认**不渲染**：普通用户用不上，摆在设置页里只会误点。
    //    打开：在下方「关于」卡上连点 5 次（`DEBUG_UNLOCK_TAPS`，计数只活在本页，退出即清零）。
    //    关闭：本区第一个开关，写回 `SettingsDefaults.DEBUG_UNLOCKED`。
    if (player.debugUnlocked) {
        SectionTitle("调试")
        // 分区自身的开关：关掉即整段消失。放在最上面而不是最下面 ——
        // 关掉之后这一区就没了，开关必须在「消失之前」够得着的位置。
        SwitchRow(
            title = "显示调试功能",
            subtitle = "关闭后本区隐藏；需要时在「关于」连点 5 次可再次显示",
            checked = true,
            onCheckedChange = { if (!it) onDebugUnlocked(false) },
        )
        // ------------------------------------------------ 前台应用记录（v1.1.29）
        //
        // 「悬浮窗按应用隐藏」的仪表盘（设计见 docs/设计-悬浮窗按应用隐藏.md）。
        // 为什么历史在这里而不是只在设置里：悬浮窗的价值全在**听风在后台**时，
        // 实时显示反而看不到 —— 正常用车，停车后打开这里回看、勾名单。
        DebugLabel("前台应用记录")
        // 开关就在这块里（v1.1.30 从「设置 → 歌词」搬过来）：记录是排障手段，
        // 日常不该出现在歌词设置里；而查包名 → 加名单的动线全在这一页完成。
        SwitchRow(
            title = "记录前台应用切换",
            subtitle = if (player.foregroundLogEnabled) {
                "记录中：最多 300 条，只写本机（关掉不影响名单生效）"
            } else {
                "关闭。打开后把前台切换写进本机历史，便于查出 360 影像等应用的包名"
            },
            checked = player.foregroundLogEnabled,
            onCheckedChange = onForegroundLogEnabled,
            onReset = resetAction(
                player.foregroundLogEnabled != SettingsDefaults.FOREGROUND_LOG_ENABLED,
            ) { onForegroundLogEnabled(SettingsDefaults.FOREGROUND_LOG_ENABLED) },
        )
        val currentPkg by ForegroundAppState.currentPkg.collectAsStateWithLifecycle()
        var logEntries by remember { mutableStateOf(ForegroundAppLog.read(context)) }
        var logcatProbe by remember { mutableStateOf<Boolean?>(null) }
        var confirmClearLog by remember { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        // 检测源状态每次重组都探一次：三次 Settings.Secure / AppOps 查询很便宜，
        // 换来「用户在系统设置里刚开完无障碍，返回就是最新状态」。
        val detector = ForegroundAppDetector.status(context)

        Text(
            text = "检测通道：" +
                (if (detector.accessibility) "无障碍服务（已开启）" else "无障碍服务（未开启）") +
                " · " +
                (if (detector.usageStats) "使用情况权限（已授予）" else "使用情况权限（未授予）"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "两个通道任一可用即可按名单收起悬浮窗；都不可用时按「不隐藏」放行，" +
                "悬浮窗不会因此消失。没有 adb 也能用：无障碍服务在系统设置里手动开启。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                runCatching { context.startActivity(ForegroundAppDetector.accessibilitySettingsIntent()) }
                    .onFailure {
                        Toast.makeText(context, "打不开无障碍设置页，请手动前往：设置 → 辅助功能", Toast.LENGTH_LONG).show()
                    }
            }) { Text("无障碍设置") }
            TextButton(onClick = {
                runCatching { context.startActivity(ForegroundAppDetector.usageAccessSettingsIntent()) }
                    .onFailure {
                        Toast.makeText(context, "打不开使用情况权限页（部分车机没有这一页）", Toast.LENGTH_LONG).show()
                    }
            }) { Text("使用情况权限") }
            // logcat 实测：用户最初想要的「日志监听」路线要 READ_LOGS（adb）——
            // 这里留一个实测按钮，万一迪丽放松了限制，报告里会显示「可读」。
            TextButton(onClick = {
                scope.launch { logcatProbe = ForegroundAppDetector.logcatReadable() }
            }) { Text("实测日志可读") }
        }
        Text(
            text = "日志可读性：" + when (logcatProbe) {
                null -> "未测试（不影响任何功能，仅作信息）"
                true -> "可读（本机允许读取系统日志）"
                false -> "不可读（符合预期：Android 4.1 起需 adb 授予 READ_LOGS）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = "当前前台：" + (currentPkg ?: "未知") +
                (currentPkg?.let { ForegroundAppLog.labelOf(context, it) }?.let { "（$it）" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "最近 ${logEntries.size} 条切换（最新在上）：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (logEntries.isEmpty()) {
            Text(
                text = "还没有记录。先打开上面的「记录前台应用切换」，再正常用车；" +
                    "停车后回到这里，就能看到 360 影像等应用的包名，点右侧「加入名单」即可收起悬浮窗。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(logEntries.reversed(), key = { "${it.timeMs}-${it.pkg}" }) { entry ->
                    // 一行 = 时间 + 「名字 / 包名」两行 + 「加入名单」按钮。
                    // v1.1.30 起可以直接点「加入名单」，不必再复制包名去歌词子页粘贴 ——
                    // 车机上长按复制、切页、粘贴这套动作基本没人愿意做。
                    val inList = entry.pkg in player.lyricOverlayHiddenApps
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = ForegroundAppLog.formatTime(entry.timeMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.label ?: "（应用名解析不到）",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = entry.pkg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            enabled = !inList,
                            onClick = {
                                onLyricOverlayHiddenApps(player.lyricOverlayHiddenApps + entry.pkg)
                                Toast.makeText(
                                    context,
                                    "已加入名单：" + (entry.label ?: entry.pkg),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        ) { Text(if (inList) "已在名单" else "加入名单") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = logEntries.isNotEmpty(),
                onClick = {
                    clipboard.setText(AnnotatedString(ForegroundAppLog.formatForCopy(logEntries)))
                    Toast.makeText(context, "已复制 ${logEntries.size} 条记录", Toast.LENGTH_SHORT).show()
                },
            ) { Text("复制全部") }
            TextButton(onClick = { logEntries = ForegroundAppLog.read(context) }) { Text("刷新") }
            TextButton(
                enabled = logEntries.isNotEmpty(),
                onClick = { confirmClearLog = true },
            ) { Text("清空") }
        }
        if (confirmClearLog) {
            AlertDialog(
                onDismissRequest = { confirmClearLog = false },
                title = { Text("清空前台记录？") },
                text = { Text("清空后不可恢复；不影响「不显示悬浮窗的应用」名单。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearLog = false
                        ForegroundAppLog.clear(context)
                        logEntries = emptyList()
                    }) { Text("清空") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearLog = false }) { Text("取消") }
                },
            )
        }

        // ------------------------------------------------ 悬浮歌词自检
        // 悬浮歌词链路跨 6 个环节，任何一环断掉用户侧都是同一个「没反应」。
        // 这个入口把整条链路一次测完，直接指出断点，省去猜和反复重装。
        DebugLabel("悬浮歌词")
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
        DebugLabel("崩溃日志")
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
                .padding(vertical = 6.dp)
                .windShadow(level = 1, shape = RoundedCornerShape(10.dp)),
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

        // ------------------------------------------------ 在线播放缓存
    // 车机弱网下这是「在线播放能不能用」的关键一环，所以把占用与清理放在诊断页：
    // 用户报「听在线歌费流量 / 加载慢」时，第一件事就是看这里有没有缓存、要不要清。
    SectionTitle("在线播放")
    ActionRow(
        title = "在线缓存",
        subtitle = if (onlineCacheBytes > 0) {
            "已缓存 ${formatByteSize(onlineCacheBytes)}（上限 512 MB，超出按最近最少使用淘汰）；" +
                "点这一行清除，清除后重播需要重新下载"
        } else {
            "还没有缓存。在线歌单播放过的音频会存到本机，重播时不再走网络"
        },
        trailing = formatByteSize(onlineCacheBytes),
        onClick = {
            val ok = OnlineMediaCache.clear(context)
            onlineCacheBytes = OnlineMediaCache.sizeBytes(context)
            Toast.makeText(
                context,
                if (ok) "已清除在线缓存" else "当前没有可清除的缓存",
                Toast.LENGTH_SHORT,
            ).show()
        },
    )

    // ------------------------------------------------ 开机自启
        // 这条链路只有真车能验（开机广播是系统保护广播，adb 发不出去），而失败表现
        // 全是「开机后什么都没发生」。所以把广播是否到达、决策是什么、起播结果原样摆出来 ——
        // 用户看一眼就能知道是「系统没给广播」还是「给了但没播起来」。
        DebugLabel("开机自启")
        val boot = bootInfo
        StatCard(
            rows = listOf(
                "开关" to if (player.autoPlayOnBoot) {
                    "已打开（开机后台起播，不弹界面）"
                } else {
                    "关闭"
                },
                "上次开机广播" to if (boot == null || !boot.received) {
                    "从未收到"
                } else {
                    BootStatusText.agoLabel(System.currentTimeMillis(), boot.atMillis)
                },
                "决策" to (if (boot == null) "—" else BootStatusText.actionLabel(boot.action)),
                "结果" to (if (boot == null) "—" else BootStatusText.resultLabel(boot)),
            )
        )
        ActionRow(
            title = "立刻试一次后台起播",
            subtitle = "不重启也能验一遍：按开机那条路恢复上次队列并起播（不会弹出界面）",
            onClick = {
                onTestBootPlayback()
                Toast.makeText(context, "已触发，退到桌面即可看到它在后台播放", Toast.LENGTH_SHORT).show()
                bootInfo = BootStatus.snapshot(context)
            },
        )
        ActionRow(
            title = "刷新上面的状态",
            subtitle = if (boot == null || !boot.received) {
                "还没收到过开机广播：请确认系统「应用 → 听风 → 权限」里「开机启动 / 自启动」是允许的"
            } else {
                "看一遍这条路走到哪一步了"
            },
            onClick = { bootInfo = BootStatus.snapshot(context) },
        )
    }

    // ------------------------------------------------ 关于
    SectionTitle("关于")
    // 连点 5 次解锁调试分区（v1.1.31）。
    // 计数用 `remember` 而不是持久化：必须「连续」点 —— 退出本页再进来就重新数，
    // 免得几下零散误触攒够 5 次把调试区翻出来。真正的「已解锁」状态才落盘。
    var aboutTaps by remember { mutableStateOf(0) }
    val onAboutTap: () -> Unit = {
        aboutTaps += 1
        if (aboutTaps >= DEBUG_UNLOCK_TAPS) {
            aboutTaps = 0
            onDebugUnlocked(true)
            Toast.makeText(context, "调试功能已显示", Toast.LENGTH_SHORT).show()
        }
    }
    StatCard(
        onClick = if (player.debugUnlocked) null else onAboutTap,
        rows = listOf(
            "应用" to "听风 WindTone",
            "版本" to version,
            "包名" to context.packageName,
            "最低系统" to "Android 7.0（API 24）",
            // v1.0.7 起 Release 按 ABI 分包，这里显示本机架构，方便对照下载
            "CPU 架构" to UpdateChecker.primaryAbiLabel().ifBlank { "—" },
            "更新来源" to "${BuildConfig.RELEASE_OWNER}/${BuildConfig.RELEASE_REPO}",
        )
    )
    Text(
        "歌单、收藏、播放统计、歌词等全部数据都只保存在本机；" +
            "歌词优先读取音频同目录的 .lrc / .krc / .yrc 文件，其次读取音频内嵌歌词。\n" +
            "联网行为只有一处：「检查更新 / 下载新版本」访问 GitHub 上的公共分发仓库" +
            "（LuckyLearning/Release；先直连官方地址，连不上时自动切换第三方加速镜像，" +
            "也可在「下载线路」中手动指定）。" +
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
            update.checking -> "正在检查更新…"
            info != null ->
                "当前 v${update.currentVersion} → 最新 v${info.version}" +
                    UpdateChecker.formatSize(info.apkSizeBytes)
                        .takeIf { it.isNotBlank() }
                        ?.let { " · $it" }
                        .orEmpty()

            else -> "当前版本 v${update.currentVersion} · 安装包由公共仓库分发"
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

    // 线路选择：检查更新与下载安装包共用，直连不通都会退到镜像
    if (showRouteDialog) {
        AlertDialog(
            onDismissRequest = { showRouteDialog = false },
            title = { Text("下载线路") },
            text = {
                Column {
                    Text(
                        "检查更新与下载安装包都走这条线路：「自动」先直连 GitHub 官方地址，" +
                            "连不上时依次尝试加速镜像；手动选定则只走那一条。" +
                            "镜像有缓存，偶尔会慢或滞后，失败时换一条线路再试即可。",
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