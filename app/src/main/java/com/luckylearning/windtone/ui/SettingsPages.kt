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


// ================================================================ 主页 / 外观 / 播放 / 音乐库 四个子页

// ================================================================ 主页（六张分类卡）

@Composable
internal fun SettingsRootPage(
    player: PlayerUiState,
    update: UpdateUiState,
    minSongDurationSec: Int,
    onNavigate: (SettingsPage) -> Unit,
) {
    CategoryRow(
        title = "外观",
        subtitle = "主题取色 · 车载模式",
        icon = AppIcons.Palette,
        onClick = { onNavigate(SettingsPage.APPEARANCE) },
    )
    CategoryRow(
        title = "播放",
        subtitle = "速度 ${"%.2f".format(player.speed)}x · 变调 ${Pitch.labelOf(player.pitchSemitones)} · 跳过静音 · 均衡器与音效",
        icon = Icons.Filled.PlayArrow,
        onClick = { onNavigate(SettingsPage.PLAYBACK) },
    )
    CategoryRow(
        title = "歌词",
        subtitle = "字号 ${"%.1f".format(player.lyricScale)}x · 悬浮歌词",
        icon = AppIcons.LyricsBubble,
        onClick = { onNavigate(SettingsPage.LYRICS) },
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
        onClick = { onNavigate(SettingsPage.LIBRARY) },
    )
    CategoryRow(
        title = "更新",
        subtitle = "检查更新 · 自动检查 · 下载线路",
        icon = AppIcons.Cloud,
        onClick = { onNavigate(SettingsPage.UPDATE) },
        trailing = if (update.info != null) "有新版本" else "v${update.currentVersion}",
    )
    CategoryRow(
        title = "关于与诊断",
        subtitle = "版本信息 · 播放统计 · 崩溃日志 · 隐私说明",
        icon = AppIcons.Info,
        onClick = { onNavigate(SettingsPage.DIAGNOSTICS) },
    )
}

// ================================================================ 子页：外观

@Composable
internal fun SettingsAppearancePage(
    player: PlayerUiState,
    dynamicTheme: Boolean,
    onDynamicTheme: (Boolean) -> Unit,
    onCarMode: (Boolean) -> Unit,
) {
    // 车载模式的长说明从副标题搬进弹窗，副标题只留一行摘要
    var showCarModeInfo by remember { mutableStateOf(false) }


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
}

// ================================================================ 子页：播放

@Composable
internal fun SettingsPlaybackPage(
    player: PlayerUiState,
    onSpeed: (Float) -> Unit,
    onPitch: (Int) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
) {
    var showEq by remember { mutableStateOf(false) }


    // ------------------------------------------------ 速度与变调
    // 两者在播放器里是同一个 `PlaybackParameters`（见 PlaybackEngine
    // 的 applyPlaybackParams），界面上也放在同一组里。
    SectionTitle("速度与变调")
    SliderRow(
        title = "默认播放速度 ${"%.2f".format(player.speed)}x",
        value = player.speed,
        range = 0.75f..2.0f,
        onValueChange = onSpeed,
        onReset = resetAction(abs(player.speed - SettingsDefaults.SPEED) > 0.001f) {
            onSpeed(SettingsDefaults.SPEED)
        },
    )
    SliderRow(
        title = "变调 ${Pitch.labelOf(player.pitchSemitones)}",
        subtitle = "升降调不改速度（±12 半音 = 一个八度）",
        value = player.pitchSemitones.toFloat(),
        range = Pitch.MIN_SEMITONES.toFloat()..Pitch.MAX_SEMITONES.toFloat(),
        onValueChange = { onPitch(it.roundToInt()) },
        onReset = resetAction(!Pitch.isDefault(player.pitchSemitones)) {
            onPitch(SettingsDefaults.PITCH_SEMITONES)
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


    if (showEq) EqSheet(onClose = { showEq = false })
}


// ================================================================ 子页：音乐库

@Composable
internal fun SettingsLibraryPage(
    minSongDurationSec: Int,
    onMinSongDurationSec: (Int) -> Unit,
    onRestoreExcluded: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenDuplicates: () -> Unit,
    library: LibraryUiState,
) {
    var showMinDurationDialog by remember { mutableStateOf(false) }


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
}
