package com.luckylearning.windtone.ui

import android.content.Context
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.data.buildLibraryStats
import com.luckylearning.windtone.data.formatByteSize
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
    minSongDurationSec: Int,
    pages: List<SettingsPage>,
    onNavigate: (SettingsPage) -> Unit,
) {
    pages.forEach { page ->
        when (page) {
            SettingsPage.APPEARANCE -> CategoryRow(
                title = "外观",
                subtitle = "主题取色 · 车载模式",
                icon = AppIcons.Palette,
                onClick = { onNavigate(SettingsPage.APPEARANCE) },
            )
            SettingsPage.PLAYBACK -> CategoryRow(
                title = "播放",
                subtitle = "速度 ${"%.2f".format(player.speed)}x · 变调 ${Pitch.labelOf(player.pitchSemitones)} · 跳过静音 · 均衡器与音效",
                icon = Icons.Filled.PlayArrow,
                onClick = { onNavigate(SettingsPage.PLAYBACK) },
            )
            SettingsPage.LYRICS -> CategoryRow(
                title = "歌词",
                subtitle = "字号 ${"%.1f".format(player.lyricScale)}x · 双语歌词 · 桌面悬浮歌词",
                icon = AppIcons.LyricsBubble,
                onClick = { onNavigate(SettingsPage.LYRICS) },
            )
            SettingsPage.LIBRARY -> CategoryRow(
                title = "音乐库",
                subtitle = if (minSongDurationSec > 0) {
                    "格式与容量 · 过滤 < ${minSongDurationSec} 秒"
                } else {
                    "格式与容量 · 未开启短音频过滤"
                },
                icon = AppIcons.LibraryMusic,
                onClick = { onNavigate(SettingsPage.LIBRARY) },
            )
            // v1.1.80：更新从独立分类卡降为「关于与诊断」里的入口行
            SettingsPage.DIAGNOSTICS -> CategoryRow(
                title = "关于与诊断",
                subtitle = "版本信息 · 检查更新 · 播放统计 · 隐私说明",
                icon = AppIcons.Info,
                onClick = { onNavigate(SettingsPage.DIAGNOSTICS) },
            )
            // v1.1.80：实验室功能 —— 实验性入口先放这里，确认稳定后再移出去
            SettingsPage.LAB -> CategoryRow(
                title = "实验室功能",
                subtitle = "在线播放 · 调整菜单顺序（实验性功能，稳定后再移出）",
                icon = AppIcons.Science,
                onClick = { onNavigate(SettingsPage.LAB) },
            )
            else -> Unit
        }
    }
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
        subtitle = "启动直进播放页 · 大控件首页 · 切换后立即生效",
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
                        "切换后立即生效：打开就转横屏并展开播放页，不用重开应用" +
                        "（关闭时不打断当前页面，只是下次启动回到曲库）。\n\n" +
                        "曲库页换成车机版式：顶部横向分类、行高与字号放大、底部大播放条，" +
                        "触摸目标都 ≥76dp，并关掉长按多选与滑动切歌这类在车上容易误触的手势。\n\n" +
                        "播放页 5 秒无操作会自动收起顶栏、控制键与底部状态行，只留歌词、歌名、" +
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
    onAutoResume: (Boolean) -> Unit,
    /** 开机后台起播（v1.1.24） */
    onAutoPlayOnBoot: (Boolean) -> Unit,
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
    // 放在「跳过静音」前面：只退过一次界面就回来的人找的是这一条，
    // 而它此前只能靠「恢复队列但停在暂停态」隐式体现，界面上没有名字。
    SwitchRow(
        title = "打开应用继续上次播放",
        subtitle = "从上次的进度接着播；关闭后只恢复队列与进度，停在暂停态",
        checked = player.autoResume,
        onCheckedChange = onAutoResume,
        onReset = resetAction(player.autoResume != SettingsDefaults.AUTO_RESUME) {
            onAutoResume(SettingsDefaults.AUTO_RESUME)
        },
    )
    // 开机自启（v1.1.24）：车机上「通电就播」但不弹界面。
    // 放在这里而不是「外观 → 车载」，因为它是播放行为，跟车载模式是否打开无关。
    SwitchRow(
        title = "开机后台播放",
        subtitle = if (player.autoPlayOnBoot) {
            "开机后在后台恢复上次队列并直接播放，不打开主界面（需要系统「开机启动」权限）"
        } else {
            "关闭时开机不做任何播放入口动作；打开后上车通电即续播，且不弹界面"
        },
        checked = player.autoPlayOnBoot,
        onCheckedChange = onAutoPlayOnBoot,
        onReset = resetAction(player.autoPlayOnBoot != SettingsDefaults.AUTO_PLAY_ON_BOOT) {
            onAutoPlayOnBoot(SettingsDefaults.AUTO_PLAY_ON_BOOT)
        },
    )
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
        // 「（需先开始播放）」太绝对：有些车机即使正在播放也建不出效果器，
        // 那句话会把人引去反复点播放（2026-10-18 真机反馈）。改成说清依赖什么。
        subtitle = "方案 / 预设 / 频段曲线 / 低音 / 环绕 / 响度 / 混响 / 淡入淡出 / 音量平衡；" +
            "可用性取决于本机音频系统，车机可能只支持车辆自带的音效设置",
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
    onOpenStats: () -> Unit,
    /** 打开「百度网盘」子页（v1.1.39） */
    onOpenBaidu: () -> Unit,
    library: LibraryUiState,
) {
    var showMinDurationDialog by remember { mutableStateOf(false) }

    // 摘要由纯函数算，且只跟着曲库变 —— 每次重组都重算一遍在几百首的量级上不痛，
    // 但 `remember(songs)` 让这页在滚动 / 弹层开关时完全不碰统计。
    val stats = remember(library.songs) { buildLibraryStats(library.songs) }


    // ------------------------------------------------ 本地曲库
    SectionTitle("本地曲库")
    ActionRow(
        title = "格式与容量",
        subtitle = "各格式的首数与占用、无损 / 有损占比（全部按本机文件大小统计）",
        onClick = onOpenStats,
        trailing = if (stats.totalSongs > 0) {
            "${stats.totalSongs} 首 · ${formatByteSize(stats.totalBytes)}"
        } else {
            "—"
        },
        chevron = true,
    )
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

    ActionRow(
        title = "百度网盘",
        subtitle = "扫码授权后直接播放网盘里的音乐（只读；需放在「我的应用数据/<应用名>」目录）",
        onClick = onOpenBaidu,
        chevron = true,
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
