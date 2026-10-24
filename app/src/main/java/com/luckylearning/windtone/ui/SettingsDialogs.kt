package com.luckylearning.windtone.ui

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
import com.luckylearning.windtone.playback.Pitch
import kotlin.math.abs

/**
 * 应用内更新对话框，三种形态：
 *  1. 发现新版本 → 展示更新说明 + 「下载并安装」
 *  2. 下载中     → 进度条（不可取消）+「切换线路」（v1.1.74：不再自动换线，慢了手动切）
 *  3. 下载完成   → 「立即安装」；未授权「安装未知应用」时先引导授权
 */
@Composable
internal fun UpdateDialog(
    update: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
    onSwitchRoute: (DownloadRoute) -> Unit = {},
    onPingRoutes: () -> Unit = {},
) {
    val info = update.info

    if (update.downloading) {
        // 「切换线路」展开的线路列表（下载弹窗内就地展开，不另起一层弹窗）
        var showRoutes by remember { mutableStateOf(false) }
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
                    TextButton(onClick = { showRoutes = !showRoutes }) {
                        Text(if (showRoutes) "收起线路列表" else "切换线路")
                    }
                    if (showRoutes) {
                        DownloadRoute.values().forEach { route ->
                            DownloadRouteRow(
                                route = route,
                                selected = route == update.route,
                                latencyText = routeLatencyText(update, route),
                                onClick = {
                                    if (route != update.route) onSwitchRoute(route)
                                    showRoutes = false
                                },
                            )
                        }
                        TextButton(
                            onClick = onPingRoutes,
                            enabled = !update.pingingRoutes,
                        ) {
                            Text(if (update.pingingRoutes) "正在测试延迟…" else "测试延迟")
                        }
                        Text(
                            "切换后会中止当前下载，按新线路从头重新下载。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
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

/**
 * 下载中弹窗的「切换线路」列表行，与「设置 → 更新 → 下载线路」弹窗同款样式。
 */
@Composable
private fun DownloadRouteRow(
    route: DownloadRoute,
    selected: Boolean,
    latencyText: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                route.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                route.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (latencyText.isNotBlank()) {
            Text(
                latencyText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "当前线路",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 线路延迟的展示文案：
 * 空 = 未测过；「…」= 正在测；「xxx ms」= 测通；「超时」= 测不通（[UpdateUiState.routeLatencies] 里为 null）。
 */
private fun routeLatencyText(update: UpdateUiState, route: DownloadRoute): String {
    if (!update.routeLatencies.containsKey(route)) {
        return if (update.pingingRoutes) "…" else ""
    }
    return update.routeLatencies[route]?.let { "$it ms" } ?: "超时"
}

/** 选项弹层里的一项 */
internal data class ChoiceOption(val value: Int, val label: String, val hint: String)

/**
 * 「双语歌词」显示模式（v1.1.18）：中英都显示 / 只显示中文 / 只显示英文。
 *
 * 与悬浮歌词的 [OverlayChoice] 分开立枚举：语义上它属于**播放页歌词**，
 * 而且没有「6 秒预览」那套联动（切换是对当前歌立即重算，播放页本身就看得到）。
 * 取值与 `lyrics/BilingualMode.index` 一一对应。
 */
internal enum class BilingualChoice(
    val title: String,
    val intro: String,
    val options: List<ChoiceOption>,
) {
    MODE(
        "双语歌词",
        "对同时带原文和翻译的歌词生效：只影响播放页与悬浮窗的显示，" +
            "不改歌词文件，导出 LRC 时翻译仍然完整保留。",
        listOf(
            ChoiceOption(0, "中英都显示", "原文一行、译文一行，默认"),
            ChoiceOption(1, "只显示中文", "外文歌词换中文译文显示"),
            ChoiceOption(2, "只显示英文", "中文歌词换英文译文显示"),
        ),
    ),
    ;
}


/**
 * 悬浮歌词的外观选项（行数 / 对齐 / 暂停表现）。
 *
 * 把「标题 + 说明 + 选项列表」一起收进枚举，是为了让这些设置项共用同一个弹层实现——
 * 每多一个选项就多复制一份 `AlertDialog` 是不可维护的，而且样式迟早会走样。
 *
 * ⚠️ v1.1.51：字号与底板**不再是选项**。字号改成了滑杆（12–60sp，连续可调），
 * 底板并进「背景颜色 + 不透明度」——「能自由调节的就不要再给固定档位」。
 */
internal enum class OverlayChoice(
    val title: String,
    val intro: String,
    val options: List<ChoiceOption>,
) {
    LINES(
        "悬浮歌词行数",
        "桌面悬浮歌词一次显示几行（当前行居中高亮，上下带上下文）。" +
            "选「自动」时按悬浮窗高度算：窗口高就多给几行，窗口矮就只留当前句，不会被裁掉。" +
            "选中后立刻给一次 6 秒预览。",
        listOf(ChoiceOption(0, "自动（按高度适配）", overlayLinesHint(0))) +
            (1..5).map { n -> ChoiceOption(n, "$n 行", overlayLinesHint(n)) },
    ),
    ALIGN(
        "悬浮歌词对齐",
        "歌词文字在悬浮窗**内部**怎么排（窗口在屏幕上的位置由「调整悬浮歌词位置」自由拖动，与此无关）。" +
            "居中适合每句都不长的歌词；居左更适合长句——每行都从同一条竖线起排，" +
            "读起来比居中的参差边缘省力。",
        listOf(
            ChoiceOption(0, "居中", "窗内每行居中，默认"),
            ChoiceOption(1, "居左", "窗内每行从左沿起排"),
        ),
    ),
    PAUSE(
        "暂停时的悬浮歌词",
        "暂停之后字幕怎么处理。",
        listOf(
            ChoiceOption(0, "淡出", "默认，保留最后一句便于回看唱到哪"),
            ChoiceOption(1, "隐藏", "暂停即消失，桌面更干净"),
        ),
    ),
    // v1.1.52：标题从「显示 / 不显示」开关升级为可选内容 —— 只显示歌名 / 只显示歌手。
    // 「显示歌名与歌手」开关保留（关 = 完全不显示），打开后由这里决定显示哪几行。
    TITLE_MODE(
        "标题内容",
        "悬浮窗顶部的小字标题显示哪些内容（需要先打开上面的「显示歌名与歌手」）。",
        listOf(
            ChoiceOption(0, "歌名 + 歌手", "默认，两行都显示"),
            ChoiceOption(1, "只显示歌名", "去掉歌手行，标题更紧凑"),
            ChoiceOption(2, "只显示歌手", "去掉歌名行"),
        ),
    ),
}

/**
 * 「N 行」这一项的一行说明（设置页副标题与弹层选项共用，避免两处措辞不一致）。
 *
 * 偶数行时下方多一行：当前行尽量靠中间，而不是贴在最上面。
 */
internal fun overlayLinesHint(lines: Int): String = when (lines) {
    0 -> "按窗口高度自动决定能放几行；高度为「自动」时按 3 行显示"
    1 -> "只显示当前歌词行（一行放不下时自动折行）"
    2 -> "当前行 + 下一句"
    3 -> "上一行 / 当前行 / 下一行，当前行高亮"
    4 -> "上 1 行 + 当前行 + 下 2 行"
    5 -> "上下各 2 行，当前行居中高亮"
    else -> "当前行 + 上下文"
}

/** 设置页行尾摘要用的短标签（与弹层选项一一对应，按档位下标取） */
internal val OVERLAY_ALIGN_LABELS = listOf("居中", "居左")
internal val OVERLAY_PAUSE_LABELS = listOf("淡出", "隐藏")
internal val OVERLAY_TITLE_MODE_LABELS = listOf("歌名 + 歌手", "只歌名", "只歌手")
