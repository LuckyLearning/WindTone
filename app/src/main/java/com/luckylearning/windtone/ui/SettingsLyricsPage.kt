package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import android.content.Context
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.overlayPresetLabel
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.overlay.OverlayPositioner
import com.luckylearning.windtone.overlay.OverlaySnapshot
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState
import com.luckylearning.windtone.state.overlayPresetParams

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.lyrics.BilingualMode
import com.luckylearning.windtone.playback.Pitch
import com.luckylearning.windtone.ui.theme.windShadow
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


// ================================================================ 子页：歌词

@Composable
internal fun SettingsLyricsPage(
    player: PlayerUiState,
    onLyricScale: (Float) -> Unit,
    /** 点歌词行是否跳转（默认关，v1.1.23） */
    onLyricTapSeeks: (Boolean) -> Unit,
    /** 播放页左侧竖排是否显示「无障碍状态」按钮（v1.1.92） */
    onPlayerA11yAction: (Boolean) -> Unit = {},
    onLyricBilingual: (Int) -> Unit,
    /** 打开「桌面悬浮歌词」子页（v1.1.80：它不再与「歌词」平级，收进本页） */
    onOpenOverlay: () -> Unit = {},
    /** 全屏模式的宽度（屏宽百分比，30..100）—— v1.1.20，与单条字幕的宽度分开 */
    /** 全屏模式的水平位置（余量百分比：0 最左 / 50 居中 / 100 最右） */
    /** 标题字号：正文的百分比（40..100，v1.1.51 取代原来的三档） */
    /** 正文字号：sp（12..60，v1.1.51 起连续可调） */
    // 自定义尺寸 / 位置 / 背景（v1.1.48）
) {
    val context = LocalContext.current
    var bilingualChoice by remember { mutableStateOf(false) }


    // ------------------------------------------------ 播放页歌词
    SectionTitle(stringResource(R.string.player_lyrics))
    SliderRow(
        title = stringResource(R.string.lyric_text_size_x, "%.1f".format(player.lyricScale)),
        value = player.lyricScale,
        range = 0.7f..1.6f,
        onValueChange = onLyricScale,
        onReset = resetAction(abs(player.lyricScale - SettingsDefaults.LYRIC_SCALE) > 0.001f) {
            onLyricScale(SettingsDefaults.LYRIC_SCALE)
        },
    )
    // v1.1.92：播放页左侧竖排的「前台识别状态」按钮。
    // 车机上无障碍会被系统悄悄关掉，这个按钮是播放页里唯一能一眼确认「还活着」的入口；
    // 觉得占位置的人可以关掉，关掉只影响显示，不影响功能。
    SwitchRow(
        title = "播放页显示「前台识别」状态按钮",
        subtitle = if (player.showA11yAction) {
            "左侧竖排多一颗无障碍图标：灰/红=未开启，主题色=已开启；点它直接进系统无障碍页"
        } else {
            "已隐藏；按应用隐藏仍正常工作，只是播放页不再显示这个状态入口"
        },
        checked = player.showA11yAction,
        onCheckedChange = onPlayerA11yAction,
        onReset = resetAction(player.showA11yAction != SettingsDefaults.PLAYER_A11Y_ACTION) {
            onPlayerA11yAction(SettingsDefaults.PLAYER_A11Y_ACTION)
        },
    )
    // 点歌词行跳转（v1.1.23，默认关）：车机上歌词要上下拖动，而「可点」会把
    // 带位移的滑动判成点击，进度莫名其妙跳走。想要这个能力的人自己打开。
    SwitchRow(
        title = stringResource(R.string.tap_lyrics_to_seek),
        subtitle = if (player.lyricTapSeeks) {
            stringResource(R.string.tapping_a_line_seeks_to)
        } else {
            stringResource(R.string.off_recommended_lyrics_only_scroll)
        },
        checked = player.lyricTapSeeks,
        onCheckedChange = onLyricTapSeeks,
        onReset = resetAction(player.lyricTapSeeks != SettingsDefaults.LYRIC_TAP_SEEKS) {
            onLyricTapSeeks(SettingsDefaults.LYRIC_TAP_SEEKS)
        },
    )
    // v1.1.18：双语歌词显示模式（默认「中英都显示」= 历史行为）
    ActionRow(
        title = stringResource(R.string.bilingual_lyrics_2, BilingualMode.of(player.lyricBilingual).shortLabel),
        subtitle = stringResource(R.string.which_lines_translated_lyrics_show),
        onClick = { bilingualChoice = true },
        chevron = true,
    )
    // ⚠️ 这里原先还有两项：「逐字歌词」开关与「已唱部分的颜色」（v1.1.8 移除）。
    //    逐字染色（含给普通 LRC 派生字级时间轴、以及它专用的已唱色）已整体删除，
    //    播放页现在只有「当前行整行高亮」——靠主题色 + 字号 + 字重区分，没有可调项。
    //    重开后两个键（`lyric_word_by_word` / `lyric_sung_color`）会留在老用户
    //    的 SharedPreferences 里，是刻意不清理的死键，别再当 bug 排查。

    // v1.1.80：悬浮歌词收进「歌词」页。
    // 口径（用户）：它只是**歌词的一种显示位置**（屏幕上 vs 桌面上），不是并列的另一类设置；
    // 主页也因此收回一张卡。进入后子页标题仍是「悬浮歌词」，返回逐级退回本页。
    SectionTitle(stringResource(R.string.floating_lyrics))
    val presetNow = overlayPresetLabel(player.overlayPresetParams())
    ActionRow(
        title = stringResource(R.string.home_screen_floating_lyrics),
        subtitle = when {
            !player.lyricOverlay -> stringResource(R.string.off_shows_the_current_lyric)
            player.lyricOverlayFullHeight -> stringResource(R.string.on_full_screen_lyrics_style, presetNow)
            else -> stringResource(R.string.on_single_subtitle_style, presetNow)
        },
        onClick = onOpenOverlay,
        chevron = true,
    )

    // v1.1.3：三个子页入口行已升级为主页的六张分类卡（见本文件开头的
    // SettingsPage.ROOT 分支）—— 原来这里只有音乐库 / 更新 / 关于与诊断
    // 三条 ActionRow，现在外观 / 播放 / 歌词也各自成了分类卡。

    // 双语歌词选择弹层（v1.1.18）：结构与悬浮歌词那套一致，但没有 6 秒预览 ——
    // 选中即对当前歌立即重算，播放页本来就看得到差别。
    if (bilingualChoice) {
        val choice = BilingualChoice.MODE
        val selected = player.lyricBilingual
        AlertDialog(
            onDismissRequest = { bilingualChoice = false },
            title = { Text(choice.title) },
            text = {
                Column {
                    Text(
                        choice.intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    choice.options.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onLyricBilingual(option.value)
                                    bilingualChoice = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (option.value == selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    option.hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (option.value == selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { bilingualChoice = false }) { Text(stringResource(R.string.off)) }
            },
        )
    }
}
