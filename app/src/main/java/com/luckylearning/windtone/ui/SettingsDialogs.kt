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
 *  2. 下载中     → 进度条（不可取消，避免留下半截文件）
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
internal data class OverlayOption(val value: Int, val label: String, val hint: String)

/**
 * 悬浮歌词五个外观选项（行数 / 字号 / 对齐 / 底板 / 暂停表现）。
 *
 * 把「标题 + 说明 + 选项列表」一起收进枚举，是为了让五个设置项共用同一个弹层实现——
 * 每多一个选项就多复制一份 `AlertDialog` 是不可维护的，而且样式迟早会走样。
 */
internal enum class OverlayChoice(
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
internal val OVERLAY_TEXT_SCALE_LABELS = listOf("小", "中", "大")
internal val OVERLAY_ALIGN_LABELS = listOf("居中", "居左")
internal val OVERLAY_BACKDROP_LABELS = listOf("无底板", "半透明", "深色实底")
internal val OVERLAY_PAUSE_LABELS = listOf("淡出", "隐藏")
