package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import android.content.Context
import android.widget.Toast
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.BootStatus
import com.luckylearning.windtone.data.BootStatusText
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.AccessibilityWatchdog
import com.luckylearning.windtone.overlay.ForegroundAppDetector
import com.luckylearning.windtone.overlay.ForegroundProbe
import com.luckylearning.windtone.diag.DiagnosticsReport
import com.luckylearning.windtone.diag.ForensicsScript
import com.luckylearning.windtone.diag.SoftEqProbe
import com.luckylearning.windtone.playback.softEq.SoftEqPath
import com.luckylearning.windtone.BuildConfig
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.data.AppIntegrity
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal const val DEBUG_UNLOCK_TAPS = 5

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

// ================================================================ 子页：关于与更新

/**
 * 「关于与更新」（2026-10-30 由原「关于与诊断」拆出）。
 *
 * 拆分理由：原页把四类东西焊在一起 —— 播放统计（看数据）、数据与存储（维护动作）、
 * 调试（排障）、关于（版本与更新），用户找「版本号」和找「清缓存」要进同一页。
 *
 * 现在这里只留**关于 + 更新**：版本卡、检查更新、隐私说明，末行进诊断与排障子页。
 * 播放统计与维护动作已搬到「曲库与存储」。
 *
 * ⚠️ **本函数只做组合**：分区实现都在 `SettingsAboutSections.kt`，
 * 加一条信息 / 调顺序都只改这里的一行（与曲库页同一口径）。
 */
@Composable
internal fun SettingsAboutPage(
    player: PlayerUiState,
    update: UpdateUiState,
    autoCheckUpdate: Boolean,
    onDebugUnlocked: (Boolean) -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    // 分区顺序表（自上而下）：
    //   ① 版本信息卡 —— 连点 5 次解锁调试
    //   ② 检查更新 —— 进更新子页
    //   ③ 后台运行 —— 电池优化白名单状态与跳转（2026-11-01 从排障页提上来）
    //   ④ 隐私说明 —— 只在本机 / 唯一联网点
    //   ⑤ 诊断与排障 —— 排障工具入口，固定页尾
    AboutVersionSection(
        debugUnlocked = player.debugUnlocked,
        onDebugUnlocked = onDebugUnlocked,
    )
    UpdateEntrySection(
        update = update,
        autoCheckUpdate = autoCheckUpdate,
        onOpenUpdate = onOpenUpdate,
    )
    BackgroundRunSection()
    PrivacyNoticeSection()
    DiagnosticsEntrySection(onOpenDiagnostics = onOpenDiagnostics)
}

// ================================================================ 子页：诊断与排障

/**
 * 「诊断与排障」（2026-10-30 从「关于与诊断」拆出，即原来的「调试」分区）。
 *
 * 内容与门禁都没变：仍由 `player.debugUnlocked` 控制（v1.1.31 用户拍板，
 * 解锁方式仍是「关于」版本卡连点 5 次）。变化只有位置 —— 它不再和版本号同屏，
 * 而是二级页；拆页之后普通用户根本不会逛到这里，这正是当初加门禁想要的效果。
 *
 * 未解锁时给一行明确提示而不是整页空白：新结构下入口在「关于」页，
 * 用户从那里点进来看到空白只会以为坏了。
 */
@Composable
@OptIn(UnstableApi::class)
internal fun SettingsDiagnosisPage(
    player: PlayerUiState,
    /** 诊断包要读曲库规模（曲数 / 分组数），与内存段一起构成排障快照 */
    library: LibraryUiState,
    crashLogCount: Int,
    crashLogText: String?,
    onLoadCrashLog: () -> Unit,
    onExportCrashLog: () -> Unit,
    onClearCrashLog: () -> Unit,
    onOverlayPreview: () -> Unit,
    onLyricOverlay: (Boolean) -> Unit,
    onForegroundLogEnabled: (Boolean) -> Unit,
    onLyricOverlayHiddenApps: (Set<String>) -> Unit,
    onTestBootPlayback: () -> Unit,
    onDebugUnlocked: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showClearCrashConfirm by remember { mutableStateOf(false) }
    // 悬浮歌词自检结果
    var showOverlayCheck by remember { mutableStateOf(false) }
    var overlayCheck by remember { mutableStateOf<OverlaySnapshot?>(null) }
    // 开机自启的落盘证据（v1.1.27）：进页面读一次，随时可手动刷新
    var bootInfo by remember { mutableStateOf<BootStatus.Snapshot?>(null) }
    LaunchedEffect(Unit) { bootInfo = BootStatus.snapshot(context) }

    if (!player.debugUnlocked) {
        SectionTitle(stringResource(R.string.debug))
        Text(
            stringResource(R.string.debug_tools_hidden_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    // ------------------------------------------------ 调试（默认隐藏，v1.1.31）
    //
    // 排障工具集中在这里 —— 它们都是**只在出问题时才用**的东西（查包名 / 查链路 / 查崩溃 / 查开机）。
    // ⚠️ 整段默认**不渲染**：普通用户用不上，摆在设置页里只会误点。
    //    打开：在下方「关于」卡上连点 5 次（`DEBUG_UNLOCK_TAPS`，计数只活在本页，退出即清零）。
    //    关闭：本区第一个开关，写回 `SettingsDefaults.DEBUG_UNLOCKED`。
    if (player.debugUnlocked) {
        SectionTitle(stringResource(R.string.debug))
        // 分区自身的开关：关掉即整段消失。放在最上面而不是最下面 ——
        // 关掉之后这一区就没了，开关必须在「消失之前」够得着的位置。
        SwitchRow(
            title = stringResource(R.string.show_debug_tools),
            subtitle = stringResource(R.string.hides_this_section_when_off),
            checked = true,
            onCheckedChange = { if (!it) onDebugUnlocked(false) },
        )
        // ------------------------------------------------ 前台应用记录（v1.1.29）
        //
        // 「悬浮窗按应用隐藏」的仪表盘（设计见 docs/设计-悬浮窗按应用隐藏.md）。
        // 为什么历史在这里而不是只在设置里：悬浮窗的价值全在**听风在后台**时，
        // 实时显示反而看不到 —— 正常用车，停车后打开这里回看、勾名单。
        DebugLabel(stringResource(R.string.foreground_app_history))
        // 开关就在这块里（v1.1.30 从「设置 → 歌词」搬过来）：记录是排障手段，
        // 日常不该出现在歌词设置里；而查包名 → 加名单的动线全在这一页完成。
        SwitchRow(
            title = stringResource(R.string.record_foreground_app_switches),
            subtitle = if (player.foregroundLogEnabled) {
                stringResource(R.string.recording_up_to_300_entries)
            } else {
                stringResource(R.string.off_turn_on_to_log)
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
        // v1.1.82：车机上跳转「无障碍设置」失败时的路径向导
        var showForegroundHelp by remember { mutableStateOf(false) }
        // v1.1.84：车机打不开「电池优化」页时的路径向导
        var showBatteryHelp by remember { mutableStateOf(false) }
        // v1.1.82：车机现场取证（见 ForegroundProbe）
        var probeReport by remember { mutableStateOf<String?>(null) }
        // v1.1.88：完整诊断包（环境 / 权限 / 播放 / 悬浮窗 / 曲库 / 事件日志）
        var diagReport by remember { mutableStateOf<String?>(null) }
        var probePath by remember { mutableStateOf<String?>(null) }
        val clipboard = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        // 2026-10-31：这行原本每次重组都执行，而 `status()` 内部会 fork 一个 logcat
        // 子进程并 waitFor()（30~200ms 主线程阻塞）—— 车机上每次前台切换、每次拖动
        // 设置滑块都卡一下。现在拆成两路：两项权限状态随前台切换刷新（2 次 IPC，便宜），
        // logcat 只在下面按钮点击时探一次（见 `logcatProbe`）。
        var detector by remember {
            mutableStateOf(ForegroundAppDetector.status(context, includeLogcat = false))
        }
        LaunchedEffect(currentPkg) {
            detector = ForegroundAppDetector.status(context, includeLogcat = false)
        }

        Text(
            text = stringResource(
                R.string.detection_channels,
                if (detector.accessibility) {
                    stringResource(R.string.accessibility_service_on)
                } else {
                    stringResource(R.string.accessibility_service_off)
                },
                if (detector.usageStats) {
                    stringResource(R.string.usage_access_granted)
                } else {
                    stringResource(R.string.usage_access_not_granted)
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v1.1.82：车机（DiLink）把无障碍设置页整个裁掉了，状态行必须**如实**
        // 说「本机没有这个页面」，而不是让用户以为是自己没找到。
        val accessibilityPageAvailable = remember {
            ForegroundAppDetector.canOpenAccessibilityPage(context)
        }
        if (!accessibilityPageAvailable && !detector.usageStats) {
            Text(
                text = stringResource(R.string.diag_a11y_no_page_tip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = stringResource(R.string.either_channel_is_enough_to),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v1.2.36：曾经开过、现在关了 → 诊断区置顶恢复条（通知可能被划掉或没通知权限）
        if (AccessibilityWatchdog.shouldPromptRecovery(
                AccessibilityWatchdog.seenOn(context),
                detector.accessibility,
            )
        ) {
            ActionRow(
                title = stringResource(R.string.a11y_watch_title),
                subtitle = stringResource(R.string.a11y_watch_banner_sub),
                onClick = {
                    if (!ForegroundAppDetector.openAccessibilitySettings(context)) {
                        showForegroundHelp = true
                    }
                },
                trailing = stringResource(R.string.a11y_watch_reopen),
                chevron = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // v1.1.81：车机（比亚迪 DiLink 等）常常没有「无障碍」这一页，
            // 旧实现只会弹一句 toast，用户面前没有出口。现在跳转失败就打开路径向导。
            val openAccessibility: @Composable () -> Unit = {
                TextButton(onClick = {
                    if (!ForegroundAppDetector.openAccessibilitySettings(context)) {
                        showForegroundHelp = true
                    }
                }) {
                    Text(
                        if (accessibilityPageAvailable) {
                            stringResource(R.string.accessibility_settings)
                        } else {
                            // 如实说明：不是没找到，是这台车机没有这一页
                            stringResource(R.string.diag_a11y_settings_no_page)
                        },
                    )
                }
            }
            val openUsageAccess: @Composable () -> Unit = {
                TextButton(onClick = {
                    if (!ForegroundAppDetector.openUsageAccessSettings(context)) {
                        showForegroundHelp = true
                    }
                }) { Text(stringResource(R.string.usage_access)) }
            }
            // v1.1.82：本机没有无障碍页时，把**能用的那条**放到前面 —— 车机用户的
            // 第一眼应该落在「使用情况访问」上，而不是一个点了只会弹向导的按钮。
            if (accessibilityPageAvailable) {
                openAccessibility()
                openUsageAccess()
            } else {
                openUsageAccess()
                openAccessibility()
            }
            // logcat 实测：用户最初想要的「日志监听」路线要 READ_LOGS（adb）——
            // 这里留一个实测按钮，万一迪丽放松了限制，报告里会显示「可读」。
            TextButton(onClick = {
                scope.launch { logcatProbe = ForegroundAppDetector.logcatReadable() }
            }) { Text(stringResource(R.string.log_readability)) }
        }
        // 路径向导：车机没有无障碍设置页时的出口。
        // 内容刻意写成「三条路径 + 一个等效方案」，因为 DiLink 各版本把这一项放在
        // 系统 / 应用 / 辅助功能三个不同位置，猜单一路径必然有人找不到。
        if (showForegroundHelp) {
            val steps = stringResource(R.string.fg_help_body)
            val copiedMsg = stringResource(R.string.fg_help_copied)
            AlertDialog(
                onDismissRequest = { showForegroundHelp = false },
                title = { Text(stringResource(R.string.fg_help_title)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(steps, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(steps))
                        Toast.makeText(
                            context,
                            AppStrings.s(R.string.fg_help_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(stringResource(R.string.fg_help_copy)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        ForegroundAppDetector.openSystemSettings(context)
                        showForegroundHelp = false
                    }) { Text(stringResource(R.string.fg_help_open_settings)) }
                },
            )
        }
        Text(
            text = stringResource(
                R.string.log_readability_state,
                when (logcatProbe) {
                    null -> stringResource(R.string.not_tested_informational_only)
                    true -> stringResource(R.string.readable_system_logs_are_accessible)
                    false -> stringResource(R.string.not_readable_expected_since_android)
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))
        // v1.1.83：车机息屏后系统会冻结后台，1.5 秒轮询随之停摆 —— 这是「按应用隐藏」
        // 在车上失效的第二个原因（第一个是没有无障碍页）。白名单申请不需要 adb。
        val ignoringBattery = ForegroundAppDetector.ignoringBatteryOptimizations(context)
        ActionRow(
            title = stringResource(R.string.diag_battery_title),
            subtitle = if (ignoringBattery) {
                stringResource(R.string.diag_battery_on)
            } else {
                stringResource(R.string.diag_battery_off)
            },
            onClick = {
                if (!ForegroundAppDetector.requestIgnoreBatteryOptimizations(context)) {
                    showBatteryHelp = true
                }
            },
            trailing = if (ignoringBattery) {
                stringResource(R.string.diag_battery_joined)
            } else {
                stringResource(R.string.diag_battery_go_settings)
            },
            chevron = true,
        )
        // E5：诊断页直接可读 SoftEq 相位 / 确认 / 曲线（点开复制短报）
        val softEq by SoftEqPath.state.collectAsStateWithLifecycle()
        ActionRow(
            title = "软 EQ 状态",
            subtitle = SoftEqProbe.describePhase(softEq.phase) +
                " · 确认=" + (if (softEq.userConfirmSoft) "开" else "关") +
                " · " + softEq.curveSource.name +
                (if (softEq.curvePresetId.isEmpty()) "" else "/" + softEq.curvePresetId),
            onClick = {
                val report = SoftEqProbe.buildReport(context)
                clipboard.setText(AnnotatedString(report))
                diagReport = report
                Toast.makeText(context, "软 EQ 摘要已复制", Toast.LENGTH_SHORT).show()
            },
            chevron = true,
        )
        // v1.1.88：诊断包。用户问「为什么这样」时，最省事的是贴一段文本，
        // 而不是来回问「你什么机型 / 开没开权限 / 放的是哪首歌」。
        ActionRow(
            title = stringResource(R.string.diag_pack_title),
            subtitle = stringResource(R.string.diag_pack_subtitle),
            onClick = {
                val report = DiagnosticsReport.build(
                    DiagnosticsReport.collect(context, player, library),
                )
                DiagnosticsReport.saveToFile(context, report)
                diagReport = report
            },
            chevron = true,
        )

        // A1：一键取证 —— 只复制 adb 脚本，不改任何设置
        var forensicsText by remember { mutableStateOf<String?>(null) }
        ActionRow(
            title = stringResource(R.string.diag_forensics_title),
            subtitle = stringResource(R.string.diag_forensics_subtitle),
            onClick = {
                val script = ForensicsScript.build(
                    packageName = context.packageName,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                )
                clipboard.setText(AnnotatedString(script))
                forensicsText = script
                Toast.makeText(context, AppStrings.s(R.string.diag_forensics_copied), Toast.LENGTH_SHORT).show()
            },
            chevron = true,
        )
        if (forensicsText != null) {
            val script = forensicsText!!
            AlertDialog(
                onDismissRequest = { forensicsText = null },
                title = { Text(stringResource(R.string.diag_forensics_dialog_title)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            script,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(script))
                        Toast.makeText(context, AppStrings.s(R.string.diag_forensics_copied), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.diag_forensics_copy_again)) }
                },
                dismissButton = {
                    TextButton(onClick = { forensicsText = null }) { Text(stringResource(R.string.diag_close)) }
                },
            )
        }
        if (diagReport != null) {
            val report = diagReport!!
            AlertDialog(
                onDismissRequest = { diagReport = null },
                title = { Text(stringResource(R.string.diag_pack_dialog_title)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            report,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report))
                        Toast.makeText(context, AppStrings.s(R.string.diag_pack_copied), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { diagReport = null }) { Text(stringResource(R.string.diag_close)) }
                },
            )
        }

        // v1.1.82：车机排障报告。远程看不到车机里有什么，把现场证据一次性取出来，
        // 用户复制或落盘带回来即可判断「是无障碍被裁、还是授权没给、还是服务被杀」。
        ActionRow(
            title = stringResource(R.string.diag_probe_title),
            subtitle = stringResource(R.string.diag_probe_subtitle),
            onClick = {
                val facts = ForegroundProbe.collect(context)
                val report = ForegroundProbe.buildReport(facts)
                probePath = ForegroundProbe.saveToFile(context, report)
                probeReport = report
            },
            chevron = true,
        )
        // 「电池优化」页在 DiLink 上同样被裁：三段候选都失败时给路径，不要只留一个死按钮。
        if (showBatteryHelp) {
            BatteryHelpDialog(onDismiss = { showBatteryHelp = false })
        }
        if (probeReport != null) {
            val report = probeReport!!
            AlertDialog(
                onDismissRequest = { probeReport = null },
                title = { Text(stringResource(R.string.diag_probe_dialog_title)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            report,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        probePath?.let { saved ->
                            Text(
                                stringResource(R.string.diag_probe_saved, saved),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report))
                        Toast.makeText(context, AppStrings.s(R.string.diag_probe_copied), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { probeReport = null }) { Text(stringResource(R.string.diag_close)) }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        // v1.1.97：非官方签名构建（改包 / 二次分发）在这里如实标注；
        // 官方包不会出现这一行。自更新已被停用，原因也写在这儿。
        if (!AppIntegrity.isOfficial) {
            Text(
                text = "⚠️ ${AppIntegrity.describe()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.diag_unofficial_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }

        Text(
            text = stringResource(R.string.in_foreground, (currentPkg ?: stringResource(R.string.unknown))) +
                (currentPkg?.let { ForegroundAppLog.labelOf(context, it) }?.let {
                    context.getString(R.string.diag_label_paren, it)
                } ?: ""),
            style = MaterialTheme.typography.labelMedium,
        )
        // v1.1.90：最近切换的应用（内存环形，**不需要**打开「前台应用记录」开关）。
        // 用途很具体：用户「哪个应用该隐藏」往往是刚试出来的 —— 切过去看一眼没隐藏，
        // 回来这里就能看到包名并一键加入，不必再走「搜索框输包名」那条路。
        val recentSwitches by ForegroundAppState.recentFlow.collectAsStateWithLifecycle()
        val recentUnique = recentSwitches.asReversed().distinctBy { it.pkg }.take(8)
        if (recentUnique.isNotEmpty()) {
            Text(
                stringResource(R.string.diag_recent_apps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recentUnique.forEach { entry ->
                val inList = entry.pkg in player.lyricOverlayHiddenApps
                val label = ForegroundAppLog.labelOf(context, entry.pkg) ?: entry.pkg
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.pkg,
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
                            Toast.makeText(context, AppStrings.s(R.string.diag_added, label), Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(if (inList) stringResource(R.string.in_list) else stringResource(R.string.add_to_list)) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Text(
            text = stringResource(R.string.last_switches_newest_first, logEntries.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (logEntries.isEmpty()) {
            Text(
                text = stringResource(R.string.nothing_recorded_yet_turn_on),
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
                                text = entry.label ?: stringResource(R.string.app_name_unavailable),
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
                                    AppStrings.s(R.string.already_listed, (entry.label ?: entry.pkg)),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        ) { Text(if (inList) stringResource(R.string.in_list) else stringResource(R.string.add_to_list)) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = logEntries.isNotEmpty(),
                onClick = {
                    clipboard.setText(AnnotatedString(ForegroundAppLog.formatForCopy(logEntries)))
                    Toast.makeText(context, AppStrings.s(R.string.copied_entries, logEntries.size), Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.copy_all)) }
            TextButton(onClick = { logEntries = ForegroundAppLog.read(context) }) { Text(stringResource(R.string.refresh)) }
            TextButton(
                enabled = logEntries.isNotEmpty(),
                onClick = { confirmClearLog = true },
            ) { Text(stringResource(R.string.common_clear)) }
        }
        if (confirmClearLog) {
            AlertDialog(
                onDismissRequest = { confirmClearLog = false },
                title = { Text(stringResource(R.string.clear_foreground_history)) },
                text = { Text(stringResource(R.string.this_cannot_be_undone_the)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearLog = false
                        ForegroundAppLog.clear(context)
                        logEntries = emptyList()
                    }) { Text(stringResource(R.string.common_clear)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearLog = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        // ------------------------------------------------ 悬浮歌词自检
        // 悬浮歌词链路跨 6 个环节，任何一环断掉用户侧都是同一个「没反应」。
        // 这个入口把整条链路一次测完，直接指出断点，省去猜和反复重装。
        DebugLabel(stringResource(R.string.floating_lyrics))
        ActionRow(
            title = stringResource(R.string.floating_lyrics_self_check),
            subtitle = stringResource(R.string.checks_permission_switch_service_window),
            onClick = {
                overlayCheck = LyricOverlayBridge.snapshot(context)
                showOverlayCheck = true
            },
            chevron = true,
        )

        // ------------------------------------------------ 崩溃日志
        DebugLabel(stringResource(R.string.crash_log))
        ActionRow(
            title = if (crashLogCount > 0) stringResource(R.string.view_log_entries, crashLogCount) else stringResource(R.string.no_crash_records),
            subtitle = stringResource(R.string.crash_data_stays_on_this),
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
                Text(stringResource(R.string.export_log))
            }
            // 不可撤销 → 二次确认
            TextButton(
                onClick = { showClearCrashConfirm = true },
                enabled = crashLogCount > 0,
            ) { Text(stringResource(R.string.clear_log)) }
        }

        // v1.1.80：删掉「后台预取 / 并行分片」两行。
        // 功能本体照旧在跑（预取 + 5 段下载都由引擎自动决定），但这两行只是把内部状态
        // 摆给用户看、外加一个「停止预取」的手动按钮 —— 属于调试残留，
        // 放在「关于与诊断」里既解决不了问题，还容易被误当成可调开关。
        // 排障需要时看 Logcat（tag: CachePrefetcher / ParallelDownload）即可。

        // ------------------------------------------------ 开机自启
        // 这条链路只有真车能验（开机广播是系统保护广播，adb 发不出去），而失败表现
        // 全是「开机后什么都没发生」。所以把广播是否到达、决策是什么、起播结果原样摆出来 ——
        // 用户看一眼就能知道是「系统没给广播」还是「给了但没播起来」。
        DebugLabel(stringResource(R.string.start_on_boot))
        val boot = bootInfo
        StatCard(
            rows = listOf(
                stringResource(R.string.switch_label) to if (player.autoPlayOnBoot) {
                    stringResource(R.string.on_resumes_playback_in_the)
                } else {
                    stringResource(R.string.off)
                },
                stringResource(R.string.last_boot_broadcast) to if (boot == null || !boot.received) {
                    stringResource(R.string.never_received)
                } else {
                    BootStatusText.agoLabel(System.currentTimeMillis(), boot.atMillis)
                },
                stringResource(R.string.decision) to (if (boot == null) "—" else BootStatusText.actionLabel(boot.action)),
                stringResource(R.string.result) to (if (boot == null) "—" else BootStatusText.resultLabel(boot)),
            )
        )
        ActionRow(
            title = stringResource(R.string.try_background_resume_now),
            subtitle = stringResource(R.string.verify_without_rebooting_restores_the),
            onClick = {
                onTestBootPlayback()
                Toast.makeText(context, AppStrings.s(R.string.triggered_go_to_the_home), Toast.LENGTH_SHORT).show()
                bootInfo = BootStatus.snapshot(context)
            },
        )
        ActionRow(
            title = stringResource(R.string.refresh_the_status_above),
            subtitle = if (boot == null || !boot.received) {
                stringResource(R.string.no_boot_broadcast_yet_make)
            } else {
                stringResource(R.string.walk_this_path_and_show)
            },
            onClick = { bootInfo = BootStatus.snapshot(context) },
        )
    }

    if (showClearCrashConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCrashConfirm = false },
            title = { Text(stringResource(R.string.clear_crash_log)) },
            text = {
                Text(
                    stringResource(R.string.clears_every_crash_record_stored),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCrashLog()
                        showClearCrashConfirm = false
                    },
                ) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCrashConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showOverlayCheck && overlayCheck != null) {
        val snap = overlayCheck!!
        AlertDialog(
            onDismissRequest = { showOverlayCheck = false },
            title = { Text(stringResource(R.string.floating_lyrics_self_check)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = snap.report(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 动作按「能否直接解决问题」排；全部就地执行，不关弹窗，
                    // 方便用户一件事做完立刻回到这里看数字有没有变。
                    TextButton(onClick = onOverlayPreview) { Text(stringResource(R.string.show_a_6_second_preview)) }
                    if (!snap.permissionGranted || !snap.windowAttached) {
                        TextButton(
                            onClick = { context.startOverlayPermissionPage() },
                        ) { Text(stringResource(R.string.grant_in_system_settings)) }
                    }
                    TextButton(
                        onClick = {
                            // 重新拉起 = 走一遍开关打开的全流程（落盘 + 起服务 + 预览）
                            onLyricOverlay(true)
                            overlayCheck = LyricOverlayBridge.snapshot(context)
                        },
                    ) { Text(stringResource(R.string.restart_service)) }
                    TextButton(
                        onClick = {
                            // 窗口跑到屏幕外时的唯一出路：桌面上根本抓不到它，
                            // 只能从这儿复位，并顺手给一次预览确认位置对不对。
                            LyricOverlayBridge.resetPosition(context)
                            onOverlayPreview()
                            overlayCheck = LyricOverlayBridge.snapshot(context)
                        },
                    ) { Text(stringResource(R.string.reset_position_and_preview)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOverlayCheck = false }) { Text(stringResource(R.string.off)) }
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
    /** 下载中切换线路（中止旧下载、按新线路重下）—— v1.1.74，下载弹窗用 */
    onSwitchDownloadRoute: (DownloadRoute) -> Unit,
    /** 测各条线路延迟 —— v1.1.74，「下载线路」弹窗的「测试延迟」按钮 */
    onPingRoutes: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onIgnoreUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
) {
    var showRouteDialog by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.update))
    val info = update.info
    ActionRow(
        title = when {
            update.checking -> stringResource(R.string.checking_for_updates)
            info != null -> stringResource(R.string.version_is_available_2, info.version)
            else -> stringResource(R.string.check_for_updates)
        },
        subtitle = when {
            update.checking -> stringResource(R.string.checking_for_updates)
            info != null ->
                stringResource(R.string.current_v_latest_v, update.currentVersion, info.version, UpdateChecker.formatSize(info.apkSizeBytes))
                        .takeIf { it.isNotBlank() }
                        ?.let { " · $it" }
                        .orEmpty()

            else -> stringResource(R.string.current_version_packages_are_distributed, update.currentVersion)
        },
        onClick = onCheckUpdate,
    )
    // v1.2.8：已下载待装的包在这里**常驻**一个安装入口。
    // 此前「立即安装」只在下载完的那次弹窗里，用户切出去（授权 / 接电话 / 杀后台）
    // 回来弹窗没了，只能从头再下载。待装状态现在会持久化并在这里恢复：
    // 点这一行直接拉起安装器，不重新下载。
    val readyVersion = update.readyApkVersion.ifBlank { info?.version.orEmpty() }
    if (update.readyApkPath != null && !update.downloading && readyVersion.isNotBlank()) {
        ActionRow(
            title = stringResource(R.string.install_downloaded_update),
            subtitle = stringResource(R.string.update_ready_no_redownload, readyVersion),
            onClick = onInstallUpdate,
            chevron = true,
        )
    }
    SwitchRow(
        title = stringResource(R.string.check_for_updates_on_launch),
        subtitle = stringResource(R.string.when_off_the_app_makes),
        checked = autoCheckUpdate,
        onCheckedChange = onAutoCheckUpdate,
        onReset = resetAction(autoCheckUpdate != SettingsDefaults.AUTO_UPDATE) {
            onAutoCheckUpdate(SettingsDefaults.AUTO_UPDATE)
        },
    )
    ActionRow(
        title = stringResource(R.string.download_route),
        subtitle = "${downloadRoute.label} · ${downloadRoute.hint}",
        onClick = { showRouteDialog = true },
        chevron = true,
        onReset = resetAction(downloadRoute != SettingsDefaults.UPDATE_ROUTE) {
            onDownloadRoute(SettingsDefaults.UPDATE_ROUTE)
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onOpenReleasePage) { Text(stringResource(R.string.open_release_page)) }
        if (info != null) {
            TextButton(onClick = onIgnoreUpdate) { Text(stringResource(R.string.skip_this_version)) }
        }
    }

    // 线路选择：检查更新与下载安装包共用，直连不通都会退到镜像
    if (showRouteDialog) {
        AlertDialog(
            onDismissRequest = { showRouteDialog = false },
            title = { Text(stringResource(R.string.download_route)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.both_checking_and_downloading_use),
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
                            Text(
                                routeLatencyText(update, route),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            if (route == downloadRoute) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = onPingRoutes,
                        enabled = !update.pingingRoutes,
                    ) {
                        Text(if (update.pingingRoutes) stringResource(R.string.testing_latency) else stringResource(R.string.test_latency))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRouteDialog = false }) { Text(stringResource(R.string.done)) } },
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
            onSwitchRoute = onSwitchDownloadRoute,
            onPingRoutes = onPingRoutes,
        )
    }
}

/**
 * 线路延迟的展示文案（与 SettingsDialogs 里的同名函数同语义）：
 * 空 = 未测过；「…」= 正在测；「xxx ms」= 测通；「超时」= 测不通。
 */
private fun routeLatencyText(update: UpdateUiState, route: DownloadRoute): String {
    if (!update.routeLatencies.containsKey(route)) {
        return if (update.pingingRoutes) "…" else ""
    }
    return update.routeLatencies[route]?.let { "$it ms" } ?: AppStrings.s(R.string.timed_out)
}
