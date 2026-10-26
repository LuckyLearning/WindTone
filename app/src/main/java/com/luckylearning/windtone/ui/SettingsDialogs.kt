package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

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
            title = { Text(stringResource(R.string.downloading_the_new_version)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.download_route_2, update.route.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showRoutes = !showRoutes }) {
                        Text(if (showRoutes) stringResource(R.string.collapse_route_list) else stringResource(R.string.switch_route))
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
                            Text(if (update.pingingRoutes) stringResource(R.string.testing_latency) else stringResource(R.string.test_latency))
                        }
                        Text(
                            stringResource(R.string.switching_aborts_the_current_download),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        if (update.progress >= 0) stringResource(R.string.downloaded, update.progress)
                        else stringResource(R.string.downloaded_2, UpdateChecker.formatSize(update.downloadedBytes).ifBlank { "0 MB" }),
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
            title = { Text(stringResource(R.string.package_ready)) },
            text = {
                Text(
                    if (update.needInstallPermission) {
                        stringResource(R.string.the_system_needs_permission_for)
                    } else {
                        stringResource(R.string.tapping_install_now_opens_the)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = if (update.needInstallPermission) onGrantPermission else onInstall) {
                    Text(if (update.needInstallPermission) stringResource(R.string.grant) else stringResource(R.string.install_now))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) } },
        )
        return
    }

    if (info == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.version_is_available_2, info.version)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.current_version_v, update.currentVersion, UpdateChecker.formatSize(info.apkSizeBytes))
                            .takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.new_version, it) }
                            .orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.download_route_change_it_in, update.route.label),
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
                    Text(stringResource(R.string.no_release_notes_were_provided), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenReleasePage) { Text(stringResource(R.string.open_the_release_page_in)) }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.download_and_install)) } },
        dismissButton = { TextButton(onClick = onIgnore) { Text(stringResource(R.string.skip)) } },
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
                contentDescription = stringResource(R.string.current_route),
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
    return update.routeLatencies[route]?.let { "$it ms" } ?: AppStrings.s(R.string.timed_out)
}

/** 选项弹层里的一项 */
internal data class ChoiceOption(val value: Int, val label: String, val hint: String)

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
        AppStrings.s(R.string.floating_lyric_lines),
        AppStrings.s(R.string.how_many_lines_the_home),
        listOf(ChoiceOption(0, AppStrings.s(R.string.auto_fit_to_height), overlayLinesHint(0))) +
            (1..5).map { n -> ChoiceOption(n, AppStrings.s(R.string.lines, n), overlayLinesHint(n)) },
    ),
    ALIGN(
        AppStrings.s(R.string.floating_lyric_alignment),
        AppStrings.s(R.string.how_lyric_text_is_laid),
        listOf(
            ChoiceOption(0, AppStrings.s(R.string.center), AppStrings.s(R.string.each_line_centered_in_the)),
            ChoiceOption(1, AppStrings.s(R.string.align_left), AppStrings.s(R.string.each_line_starts_at_the)),
        ),
    ),
    PAUSE(
        AppStrings.s(R.string.floating_lyrics_when_paused),
        AppStrings.s(R.string.what_the_subtitle_does_when),
        listOf(
            ChoiceOption(0, AppStrings.s(R.string.fade_out), AppStrings.s(R.string.default_keeps_the_last_line)),
            ChoiceOption(1, AppStrings.s(R.string.hide), AppStrings.s(R.string.disappears_on_pause_for_a)),
        ),
    ),
    // v1.1.52：标题从「显示 / 不显示」开关升级为可选内容 —— 只显示歌名 / 只显示歌手。
    // 「显示歌名与歌手」开关保留（关 = 完全不显示），打开后由这里决定显示哪几行。
    TITLE_MODE(
        AppStrings.s(R.string.title_content),
        AppStrings.s(R.string.what_the_small_header_shows),
        listOf(
            ChoiceOption(0, AppStrings.s(R.string.title_artist), AppStrings.s(R.string.default_both_lines)),
            ChoiceOption(1, AppStrings.s(R.string.title_only), AppStrings.s(R.string.drops_the_artist_line_for)),
            ChoiceOption(2, AppStrings.s(R.string.artist_only), AppStrings.s(R.string.drops_the_title_line)),
        ),
    ),
}

/**
 * 「N 行」这一项的一行说明（设置页副标题与弹层选项共用，避免两处措辞不一致）。
 *
 * 偶数行时下方多一行：当前行尽量靠中间，而不是贴在最上面。
 */
internal fun overlayLinesHint(lines: Int): String = when (lines) {
    0 -> AppStrings.s(R.string.line_count_follows_the_window)
    1 -> AppStrings.s(R.string.current_line_only_wraps_when)
    2 -> AppStrings.s(R.string.current_next)
    3 -> AppStrings.s(R.string.previous_current_and_next_the)
    4 -> AppStrings.s(R.string.t_1_above_current_2_below_label)
    5 -> AppStrings.s(R.string.t_2_above_and_below_current_label)
    else -> AppStrings.s(R.string.current_context)
}

/** 设置页行尾摘要用的短标签（与弹层选项一一对应，按档位下标取） */
internal val OVERLAY_ALIGN_LABELS = listOf(AppStrings.s(R.string.center), AppStrings.s(R.string.align_left))
internal val OVERLAY_PAUSE_LABELS = listOf(AppStrings.s(R.string.fade_out), AppStrings.s(R.string.hide))
internal val OVERLAY_TITLE_MODE_LABELS = listOf(AppStrings.s(R.string.title_artist), AppStrings.s(R.string.title_only_2), AppStrings.s(R.string.artist_only_2))
