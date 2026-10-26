package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.data.OverlayPresetTracker
import com.luckylearning.windtone.data.OVERLAY_FACTORY_PARAMS
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.currentOverlayPreset
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.overlay.OverlayPositioner
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.overlayPresetParams
import com.luckylearning.windtone.ui.theme.windShadow

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.IconButton
import com.luckylearning.windtone.overlay.OverlayCollapseHelper

/**
 * 桌面悬浮歌词的**独立设置子页**（v1.1.52 从「歌词」子页拆出）。
 *
 * 为什么单独成页：悬浮歌词的开关、授权引导、四组外观调节、隐藏名单与
 * 运行状态自检加起来早已独占两屏，和播放页歌词挤在一个子页里只会互相
 * 把对方顶出视口。它本来就是一个完整的功能模块 —— 开关、样式、行为、
 * 排障各自成体系，给它一张自己的分类卡与一个自己的子页才说得通。
 *
 * 「歌词」子页从此只放播放页歌词（字号 / 双语 / 点歌词跳转）。
 */

/**
 * 全屏歌词在「满宽却要点调整」时自动收窄到的宽度（%）。
 *
 * 留 8% 就够左右挪：全屏宽度 92% 在 1920 屏上有 154px 的余量，
 * 车机手指宽度量级下手感正常，视觉上也几乎看不出变窄。
 */
private const val OVERLAY_FULL_EDIT_LEAVE_PERCENT = 92

@Composable
internal fun SettingsOverlayPage(
    player: PlayerUiState,
    canDrawOverlay: Boolean,
    overlayHealth: LyricOverlayBus.Health,
    onLyricOverlay: (Boolean) -> Unit,
    onLyricOverlayLines: (Int) -> Unit,
    onLyricOverlayFullHeight: (Boolean) -> Unit,
    onLyricOverlayFullWidthPercent: (Int) -> Unit,
    onLyricOverlayFullOffsetPercent: (Int) -> Unit,
    onLyricOverlayShowTitle: (Boolean) -> Unit,
    onLyricOverlayTitlePercent: (Int) -> Unit,
    onLyricOverlayTextSp: (Int) -> Unit,
    onLyricOverlayAlign: (Int) -> Unit,
    onLyricOverlayPauseMode: (Int) -> Unit,
    onLyricOverlayWidthPercent: (Int) -> Unit,
    onLyricOverlayBoxHeightPercent: (Int) -> Unit,
    onLyricOverlayFullHeightPercent: (Int) -> Unit,
    onLyricOverlayXPercent: (Int) -> Unit,
    onLyricOverlayYPercent: (Int) -> Unit,
    onLyricOverlayFullYPercent: (Int) -> Unit,
    onLyricOverlayBgColor: (Int) -> Unit,
    onLyricOverlayBgAlphaPercent: (Int) -> Unit,
    // v1.1.52 样式补全 10 项
    onLyricOverlayCornerRadiusDp: (Int) -> Unit,
    onLyricOverlayBorderWidthDp: (Int) -> Unit,
    onLyricOverlayBorderColor: (Int) -> Unit,
    onLyricOverlayLineSpacingDp: (Int) -> Unit,
    onLyricOverlayCurrentColor: (Int) -> Unit,
    onLyricOverlayDimPercent: (Int) -> Unit,
    onLyricOverlayStrokeWidthDp: (Int) -> Unit,
    onLyricOverlayStrokeColor: (Int) -> Unit,
    onLyricOverlayFadeMs: (Int) -> Unit,
    onLyricOverlayTitleMode: (Int) -> Unit,
    onLyricOverlayTextColor: (Int) -> Unit,
    /** 「不显示悬浮窗的应用」名单（v1.1.29）；整体写回，弹窗内逐个勾选 */
    onLyricOverlayHiddenApps: (Set<String>) -> Unit,
    /** 套用样式预设（v1.1.78；批量写盘，走 OverlayActions.applyOverlayPreset） */
    onApplyOverlayPreset: (OverlayPreset) -> Unit = {},
    /** 悬浮窗左侧三颗侧钮的显示开关（v1.2.0，仅车载模式生效） */
    onOverlayHandles: (Boolean) -> Unit = {},
    /** 单颗侧钮开关：0 收起 / 1 播放暂停 / 2 打开播放页（v1.3.0） */
    onOverlayHandle: (Int, Boolean) -> Unit = { _, _ -> },
    /** 侧钮上下顺序 CSV（B1），如 collapse,play_pause,player */
    onOverlayHandleOrder: (String) -> Unit = {},
    onOverlayEditing: (Boolean) -> Unit,
    onOverlayPreview: () -> Unit,
) {
    val context = LocalContext.current
    // 悬浮歌词剩下的外观选项（行数 / 对齐 / 暂停行为）共用一个选项弹层
    var overlayChoice by remember { mutableStateOf<OverlayChoice?>(null) }
    // 「不显示悬浮窗的应用」弹窗（v1.1.29）
    var showHiddenApps by remember { mutableStateOf(false) }
    var colorPickerShown by remember { mutableStateOf(false) }
    // 背景色的「任意颜色」弹层（v1.1.49）：预设色板之外的颜色走这里
    var bgColorPickerShown by remember { mutableStateOf(false) }
    // v1.1.52 样式补全：当前行颜色 / 描边颜色 / 边框颜色各自的任意取色弹层
    var currentColorPickerShown by remember { mutableStateOf(false) }
    var strokeColorPickerShown by remember { mutableStateOf(false) }
    var borderColorPickerShown by remember { mutableStateOf(false) }
    // 「全部恢复默认」的二次确认
    var showResetAll by remember { mutableStateOf(false) }
    // v1.1.78：「高级参数」折叠态。预设网格顶到前面之后，21 项细参默认收起，
    // 「想换样式」的用户先看卡片，「想抠细节」的用户再展开。
    var showOverlayAdvanced by remember { mutableStateOf(false) }
    var overlayHintVisible by remember { mutableStateOf(false) }
    // 三条跳转路都走不通（ROM 连系统授权页都不给开）→ 指引卡里改显示手动路径
    var overlayJumpFailed by remember { mutableStateOf(false) }
    LaunchedEffect(canDrawOverlay) {
        if (canDrawOverlay) {
            overlayHintVisible = false
            overlayJumpFailed = false
        }
    }

    SectionTitle(stringResource(R.string.home_screen_floating_lyrics))
    SwitchRow(
        title = stringResource(R.string.home_screen_floating_lyrics),
        subtitle = if (canDrawOverlay) {
            stringResource(R.string.shows_the_current_lyric_line)
        } else {
            stringResource(R.string.requires_the_display_over_other)
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
                //
                // ⚠️ 各 ROM 对 ACTION_MANAGE_OVERLAY_PERMISSION 的支持并不一致：
                // 带 package: 数据在原生系统上会直接定位到本应用，但部分
                // HyperOS / MIUI 版本不认这个 data，会打开一个空的应用列表 ——
                // 用户视角就是「点了开关什么都没发生」。所以逐级降级，
                // 并且三条路都走不通时把手动路径写出来，绝不静默。
                overlayHintVisible = true
                overlayJumpFailed = !context.startOverlayPermissionPage()
            }
        },
        // 关掉悬浮歌词是「回到默认」，但这里的 ↺ 只负责把开关拨回默认值，
        // 不等同于下面那个「调整位置」——位置是另一件事，不在这里复位。
        onReset = resetAction(player.lyricOverlay != SettingsDefaults.LYRIC_OVERLAY) {
            onLyricOverlay(SettingsDefaults.LYRIC_OVERLAY)
        },
    )
    // 关掉开关时，下面这些**全部不显示**（v1.1.21 用户要求）：形态、宽度、
    // 位置、行数、字号、对齐、底板、暂停表现、文字颜色，全部只对「浮层已经
    // 在屏幕上」这件事有意义。留着它们只会让人以为「改了会生效」，
    // 而实际什么都看不到 —— 展示出来的每一行都必须是当下真正起作用的。
    if (player.lyricOverlay) {
        // 全屏高度开关（v1.1.19）**不进预设体系**：整屏形态是「结构切换」而不是
        // 「皮肤」，与预设混在一起会造成「选了预设却变了结构」的困惑（设计文档 §7.2）。
        // 所以它保持在预设网格之上独立可见。
        // v1.2.0：左侧三颗侧钮（收起 / 播放暂停 / 打开播放页）的显示开关。
        // 只在车载模式下才真正挂出来 —— 手机上那三颗钮纯属遮挡。
        SwitchRow(
            title = "显示悬浮窗侧钮（仅车载模式）",
            subtitle = if (player.overlayHandles) {
                "歌词窗左侧三颗钮：收起 / 播放暂停 / 打开播放页"
            } else {
                "已隐藏；收起与按应用隐藏仍照常工作，只是屏幕上不再显示这三颗钮"
            },
            checked = player.overlayHandles,
            onCheckedChange = onOverlayHandles,
            onReset = resetAction(player.overlayHandles != SettingsDefaults.OVERLAY_HANDLES) {
                onOverlayHandles(SettingsDefaults.OVERLAY_HANDLES)
            },
        )
        // v1.3.0：三颗钮各自可关。整体开关关掉时这些行不显示 —— 它们只在
        // "已经决定显示侧钮"的前提下才有意义，否则就是三行点了没反应的死人开关。
        if (player.overlayHandles) {
            SwitchRow(
                title = "　显示「收起 / 展开」钮",
                subtitle = "关掉后只能从通知栏「收起 / 展开」操作悬浮歌词",
                checked = player.overlayHandleCollapse,
                onCheckedChange = { onOverlayHandle(0, it) },
            )
            SwitchRow(
                title = "　显示「播放 / 暂停」钮",
                subtitle = "关掉后仍可用播放页、通知栏或方向盘控制",
                checked = player.overlayHandlePlayPause,
                onCheckedChange = { onOverlayHandle(1, it) },
            )
            SwitchRow(
                title = "　显示「打开播放页」钮",
                subtitle = "关掉后可从桌面图标或通知栏进入播放页",
                checked = player.overlayHandlePlayer,
                onCheckedChange = { onOverlayHandle(2, it) },
            )
        }
        SwitchRow(
            title = stringResource(R.string.full_screen_height),
            subtitle = if (player.lyricOverlayFullHeight) {
                stringResource(R.string.fills_the_screen_leaving_56dp)
            } else {
                stringResource(R.string.turns_the_single_subtitle_into)
            },
            checked = player.lyricOverlayFullHeight,
            onCheckedChange = onLyricOverlayFullHeight,
            onReset = resetAction(player.lyricOverlayFullHeight != SettingsDefaults.OVERLAY_FULL_HEIGHT) {
                onLyricOverlayFullHeight(SettingsDefaults.OVERLAY_FULL_HEIGHT)
            },
        )

        // ---------------- 样式预设（v1.1.78，FR-06） ----------------
        //
        // 状态卡（上方开关 + 运行态）与网格成对：快捷换样式在网格，深度调参在折叠区。
        // 「当前预设」不落盘，由参数反推（R5）——命中显示其名，出厂默认显示
        // 「默认预设」，改乱显示「自定义」并给「恢复上次套用」入口（FR-04）。
        GroupLabel(stringResource(R.string.style_presets))
        val presetParams = player.overlayPresetParams()
        val matchedPreset = currentOverlayPreset(presetParams)
        // 车机三套（carOnly）仅车载模式展示（D3）；手机模式恒 4 套
        val presetPool = if (player.carMode) OverlayPreset.ALL else OverlayPreset.PHONE
        presetPool.chunked(2).forEach { rowPresets ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowPresets.forEach { preset ->
                    OverlayPresetCard(
                        preset = preset,
                        selected = matchedPreset?.id == preset.id,
                        modifier = Modifier.weight(1f),
                        onClick = { onApplyOverlayPreset(preset) },
                    )
                }
                if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        // FR-04：参数已偏离任何预设（且不是出厂默认）+ 本会话套用过 → 一键恢复原值。
        // 恢复 = 把该预设快照原样重写（R2）；重启后记忆消失，此时网格上仍可重新套用。
        if (matchedPreset == null && presetParams != OVERLAY_FACTORY_PARAMS) {
            OverlayPresetTracker.lastAppliedId?.let { OverlayPreset.byId(it) }?.let { last ->
                ActionRow(
                    title = stringResource(R.string.parameters_changed_restore_the_original, last.displayName),
                    subtitle = stringResource(R.string.last_applied_tap_to_write, last.displayName, last.summary()),
                    onClick = { onApplyOverlayPreset(last) },
                )
            }
        }

        // ---------------- 高级参数（折叠；原四组细参整体收进来） ----------------
        ActionRow(
            title = stringResource(R.string.advanced_parameters),
            subtitle = if (showOverlayAdvanced) {
                stringResource(R.string.collapse)
            } else {
                stringResource(R.string.presets_cover_most_cases_width)
            },
            onClick = { showOverlayAdvanced = !showOverlayAdvanced },
            trailing = if (showOverlayAdvanced) stringResource(R.string.collapse) else stringResource(R.string.expand),
            chevron = true,
        )
        if (showOverlayAdvanced) {
        // v1.1.51：按「位置与尺寸 / 字体 / 背景 / 行为」四组重排。
        // 原则：**能连续调的都换成滑杆**，只有语义上就是二选一的（对齐 / 暂停表现）
        // 才留选项弹层；原先的「底板三档 + 自定义背景开关」并进背景的颜色 + 不透明度。
        //
        // 形态开关仍在最上面：它决定下面出现哪一套尺寸控件。

        // ---------------- 位置与尺寸 ----------------
        GroupLabel(stringResource(R.string.position_and_size))
        if (player.lyricOverlayFullHeight) {
            // 全屏形态：宽度、高度、水平 / 垂直位置都是百分比滑块。
            // 两套值刻意分开存（全屏 vs 单条），切换形态互不覆盖。
            SliderRow(
                title = stringResource(R.string.panel_width, player.lyricOverlayFullWidthPercent),
                subtitle = if (player.lyricOverlayFullWidthPercent >= 100) {
                    stringResource(R.string.full_width_so_there_is)
                } else {
                    stringResource(R.string.of_the_screen_width_leaving, player.lyricOverlayFullWidthPercent, 100 - player.lyricOverlayFullWidthPercent)
                },
                value = player.lyricOverlayFullWidthPercent.toFloat(),
                range = SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first.toFloat()..
                    SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last.toFloat(),
                onValueChange = {
                    onLyricOverlayFullWidthPercent(it.toInt().coerceIn(30, 100))
                },
                onReset = resetAction(
                    player.lyricOverlayFullWidthPercent != SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT,
                ) { onLyricOverlayFullWidthPercent(SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT) },
            )
            SliderRow(
                title = stringResource(R.string.panel_height, player.lyricOverlayFullHeightPercent),
                subtitle = if (player.lyricOverlayFullHeightPercent >= 100) {
                    stringResource(R.string.screen_height_minus_the_head)
                } else {
                    stringResource(R.string.of_the_screen_height_dock, player.lyricOverlayFullHeightPercent)
                },
                value = player.lyricOverlayFullHeightPercent.toFloat(),
                range = SettingsDefaults.OVERLAY_FULL_HEIGHT_RANGE.first.toFloat()..
                    SettingsDefaults.OVERLAY_FULL_HEIGHT_RANGE.last.toFloat(),
                onValueChange = { onLyricOverlayFullHeightPercent(it.toInt().coerceIn(30, 100)) },
                onReset = resetAction(
                    player.lyricOverlayFullHeightPercent != SettingsDefaults.OVERLAY_FULL_HEIGHT_PERCENT,
                ) { onLyricOverlayFullHeightPercent(SettingsDefaults.OVERLAY_FULL_HEIGHT_PERCENT) },
            )
            SliderRow(
                title = stringResource(R.string.horizontal_position, positionLabel(player.lyricOverlayFullOffsetPercent)),
                subtitle = if (player.lyricOverlayFullWidthPercent >= 100) {
                    stringResource(R.string.at_100_width_there_is)
                } else {
                    stringResource(R.string.moves_the_panel_between_the)
                },
                value = player.lyricOverlayFullOffsetPercent.toFloat(),
                range = 0f..100f,
                onValueChange = { onLyricOverlayFullOffsetPercent(it.toInt().coerceIn(0, 100)) },
                onReset = resetAction(
                    player.lyricOverlayFullOffsetPercent != SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT,
                ) {
                    onLyricOverlayFullOffsetPercent(SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT)
                },
            )
            SliderRow(
                title = stringResource(R.string.vertical_position, positionLabel(player.lyricOverlayFullYPercent, horizontal = false)),
                subtitle = if (player.lyricOverlayFullHeightPercent >= 100) {
                    stringResource(R.string.at_100_height_there_is)
                } else {
                    stringResource(R.string.moves_the_panel_between_the_2)
                },
                value = player.lyricOverlayFullYPercent.toFloat(),
                range = 0f..100f,
                onValueChange = { onLyricOverlayFullYPercent(it.toInt().coerceIn(0, 100)) },
                onReset = resetAction(
                    player.lyricOverlayFullYPercent != SettingsDefaults.OVERLAY_FULL_Y_PERCENT,
                ) { onLyricOverlayFullYPercent(SettingsDefaults.OVERLAY_FULL_Y_PERCENT) },
            )
            // 页内示意图（v1.1.22）：全屏浮层在 App 内不绘制，改完当场看不到，
            // 所以给一张跟屏幕同比例的缩略图，宽度 / 高度 / 位置怎么变它就怎么变。
            OverlayFullPreviewMock(
                widthPercent = player.lyricOverlayFullWidthPercent,
                offsetPercent = player.lyricOverlayFullOffsetPercent,
                heightPercent = player.lyricOverlayFullHeightPercent,
                yPercent = player.lyricOverlayFullYPercent,
            )
        } else {
            // 单条字幕：行数（自动 = 按高度反推）、宽度、高度、两个方向的位置
            ActionRow(
                title = stringResource(R.string.floating_lyric_lines),
                subtitle = if (player.lyricOverlayLines == 0 && player.lyricOverlayBoxHeightPercent == 0) {
                    stringResource(R.string.auto_but_the_height_is)
                } else {
                    overlayLinesHint(player.lyricOverlayLines)
                },
                onClick = { overlayChoice = OverlayChoice.LINES },
                chevron = true,
                onReset = resetAction(player.lyricOverlayLines != SettingsDefaults.OVERLAY_LINES) {
                    onLyricOverlayLines(SettingsDefaults.OVERLAY_LINES)
                },
            )
            SliderRow(
                title = stringResource(R.string.floating_lyric_width, player.lyricOverlayWidthPercent),
                subtitle = if (player.lyricOverlayWidthPercent >= 100) {
                    stringResource(R.string.full_width_so_it_cannot)
                } else {
                    stringResource(R.string.percentage_of_the_screen_width)
                },
                value = player.lyricOverlayWidthPercent.toFloat(),
                range = 1f..100f,
                // 拖动时逐帧写回：宽度与屏幕是直接的比例关系，松手才变会让人
                // 以为「拖了半天没反应」。引擎侧每次都会重摆窗口，正好当实时预览用。
                onValueChange = { onLyricOverlayWidthPercent(it.toInt().coerceIn(1, 100)) },
                onReset = resetAction(
                    player.lyricOverlayWidthPercent != SettingsDefaults.OVERLAY_WIDTH_PERCENT,
                ) { onLyricOverlayWidthPercent(SettingsDefaults.OVERLAY_WIDTH_PERCENT) },
            )
            SliderRow(
                title = if (player.lyricOverlayBoxHeightPercent == 0) {
                    stringResource(R.string.height_auto)
                } else {
                    stringResource(R.string.height, player.lyricOverlayBoxHeightPercent)
                },
                subtitle = if (player.lyricOverlayBoxHeightPercent == 0) {
                    stringResource(R.string.auto_sizes_to_the_number)
                } else {
                    stringResource(R.string.of_screen_height_once_fixed, player.lyricOverlayBoxHeightPercent)
                },
                value = player.lyricOverlayBoxHeightPercent.toFloat(),
                range = 0f..100f,
                onValueChange = {
                    val v = it.toInt()
                    // 0 是「自动」档；1..9 没有意义（压成一条缝），直接跳到 10
                    onLyricOverlayBoxHeightPercent(if (v == 0) 0 else v.coerceAtLeast(10))
                },
                onReset = resetAction(
                    player.lyricOverlayBoxHeightPercent != SettingsDefaults.OVERLAY_BOX_HEIGHT_PERCENT,
                ) { onLyricOverlayBoxHeightPercent(SettingsDefaults.OVERLAY_BOX_HEIGHT_PERCENT) },
            )
            SliderRow(
                title = stringResource(R.string.horizontal_position_2, positionLabel(player.lyricOverlayXPercent)),
                subtitle = stringResource(R.string.moves_between_the_left_and),
                value = player.lyricOverlayXPercent.toFloat(),
                range = 0f..100f,
                onValueChange = { onLyricOverlayXPercent(it.toInt().coerceIn(0, 100)) },
                onReset = resetAction(
                    player.lyricOverlayXPercent != SettingsDefaults.OVERLAY_X_PERCENT,
                ) { onLyricOverlayXPercent(SettingsDefaults.OVERLAY_X_PERCENT) },
            )
            SliderRow(
                title = stringResource(R.string.vertical_position_2, positionLabel(player.lyricOverlayYPercent, horizontal = false)),
                subtitle = stringResource(R.string.moves_between_the_top_and),
                value = player.lyricOverlayYPercent.toFloat(),
                range = 0f..100f,
                onValueChange = { onLyricOverlayYPercent(it.toInt().coerceIn(0, 100)) },
                onReset = resetAction(
                    player.lyricOverlayYPercent != SettingsDefaults.OVERLAY_Y_PERCENT,
                ) { onLyricOverlayYPercent(SettingsDefaults.OVERLAY_Y_PERCENT) },
            )
        }

        // ---------------- 字体 ----------------
        GroupLabel(stringResource(R.string.font))
        // v1.1.51：字号从「小 / 中 / 大」三档改成 12–60sp 连续可调。
        // 三档在车机上永远差一截 —— 观看距离、屏幕尺寸、个人视力都不同，
        // 连续滑杆才谈得上「调到自己舒服」。
        SliderRow(
            title = stringResource(R.string.body_size_sp, player.lyricOverlayTextSp),
            subtitle = stringResource(R.string.continuously_adjustable_from_12_to),
            value = player.lyricOverlayTextSp.toFloat(),
            range = SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last.toFloat(),
            onValueChange = {
                onLyricOverlayTextSp(it.roundToInt().coerceIn(
                    SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first,
                    SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last,
                ))
            },
            onReset = resetAction(player.lyricOverlayTextSp != SettingsDefaults.OVERLAY_TEXT_SP) {
                onLyricOverlayTextSp(SettingsDefaults.OVERLAY_TEXT_SP)
            },
        )
        ActionRow(
            title = stringResource(R.string.floating_lyric_alignment),
            subtitle = if (player.lyricOverlayAlign == 1) {
                stringResource(R.string.lyrics_align_left_inside_the)
            } else {
                stringResource(R.string.lyrics_centered_horizontally_in_the)
            },
            onClick = { overlayChoice = OverlayChoice.ALIGN },
            trailing = OVERLAY_ALIGN_LABELS[player.lyricOverlayAlign],
            chevron = true,
            onReset = resetAction(player.lyricOverlayAlign != SettingsDefaults.OVERLAY_ALIGN) {
                onLyricOverlayAlign(SettingsDefaults.OVERLAY_ALIGN)
            },
        )
        ActionRow(
            title = stringResource(R.string.floating_lyric_text_color),
            subtitle = stringResource(R.string.title_context_lines_and_body),
            onClick = { colorPickerShown = true },
            swatch = player.lyricOverlayTextColor,
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayTextColor != SettingsDefaults.OVERLAY_TEXT_COLOR,
            ) { onLyricOverlayTextColor(SettingsDefaults.OVERLAY_TEXT_COLOR) },
        )
        // ---- v1.1.52 样式补全：当前行颜色 / 上下句压暗 / 行距 / 描边 ----
        ActionRow(
            title = stringResource(R.string.current_line_color),
            subtitle = if (player.lyricOverlayCurrentColor == 0) {
                stringResource(R.string.follows_the_text_color_above)
            } else {
                stringResource(R.string.highlights_the_current_line_in)
            },
            onClick = { currentColorPickerShown = true },
            swatch = if (player.lyricOverlayCurrentColor == 0) {
                player.lyricOverlayTextColor
            } else {
                player.lyricOverlayCurrentColor
            },
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayCurrentColor != SettingsDefaults.OVERLAY_CURRENT_COLOR,
            ) { onLyricOverlayCurrentColor(SettingsDefaults.OVERLAY_CURRENT_COLOR) },
        )
        SliderRow(
            title = stringResource(R.string.context_line_brightness, player.lyricOverlayDimPercent),
            subtitle = stringResource(R.string.brightness_kept_for_context_lines),
            value = player.lyricOverlayDimPercent.toFloat(),
            range = SettingsDefaults.OVERLAY_DIM_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_DIM_RANGE.last.toFloat(),
            onValueChange = { onLyricOverlayDimPercent(it.roundToInt().coerceIn(
                SettingsDefaults.OVERLAY_DIM_RANGE.first,
                SettingsDefaults.OVERLAY_DIM_RANGE.last,
            )) },
            onReset = resetAction(
                player.lyricOverlayDimPercent != SettingsDefaults.OVERLAY_DIM_PERCENT,
            ) { onLyricOverlayDimPercent(SettingsDefaults.OVERLAY_DIM_PERCENT) },
        )
        SliderRow(
            title = if (player.lyricOverlayLineSpacingDp == 0) {
                stringResource(R.string.lyric_line_spacing_compact)
            } else {
                stringResource(R.string.lyric_line_spacing_dp, player.lyricOverlayLineSpacingDp)
            },
            subtitle = stringResource(R.string.gap_between_lyric_lines_reduce),
            value = player.lyricOverlayLineSpacingDp.toFloat(),
            range = SettingsDefaults.OVERLAY_LINE_SPACING_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_LINE_SPACING_RANGE.last.toFloat(),
            onValueChange = { onLyricOverlayLineSpacingDp(it.roundToInt().coerceIn(
                SettingsDefaults.OVERLAY_LINE_SPACING_RANGE.first,
                SettingsDefaults.OVERLAY_LINE_SPACING_RANGE.last,
            )) },
            onReset = resetAction(
                player.lyricOverlayLineSpacingDp != SettingsDefaults.OVERLAY_LINE_SPACING_DP,
            ) { onLyricOverlayLineSpacingDp(SettingsDefaults.OVERLAY_LINE_SPACING_DP) },
        )
        SliderRow(
            title = if (player.lyricOverlayStrokeWidthDp == 0) {
                stringResource(R.string.text_outline_auto)
            } else {
                stringResource(R.string.text_outline_dp, player.lyricOverlayStrokeWidthDp)
            },
            subtitle = if (player.lyricOverlayStrokeWidthDp == 0) {
                stringResource(R.string.auto_3dp_without_a_backdrop)
            } else {
                stringResource(R.string.outline_width_around_the_text)
            },
            value = player.lyricOverlayStrokeWidthDp.toFloat(),
            range = SettingsDefaults.OVERLAY_STROKE_WIDTH_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_STROKE_WIDTH_RANGE.last.toFloat(),
            onValueChange = { onLyricOverlayStrokeWidthDp(it.roundToInt().coerceIn(
                SettingsDefaults.OVERLAY_STROKE_WIDTH_RANGE.first,
                SettingsDefaults.OVERLAY_STROKE_WIDTH_RANGE.last,
            )) },
            onReset = resetAction(
                player.lyricOverlayStrokeWidthDp != SettingsDefaults.OVERLAY_STROKE_WIDTH_DP,
            ) { onLyricOverlayStrokeWidthDp(SettingsDefaults.OVERLAY_STROKE_WIDTH_DP) },
        )
        ActionRow(
            title = stringResource(R.string.outline_color),
            subtitle = stringResource(R.string.color_of_the_text_outline),
            onClick = { strokeColorPickerShown = true },
            swatch = player.lyricOverlayStrokeColor,
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayStrokeColor != SettingsDefaults.OVERLAY_STROKE_COLOR,
            ) { onLyricOverlayStrokeColor(SettingsDefaults.OVERLAY_STROKE_COLOR) },
        )
        // 顶部「歌名 + 歌手」两行小字（v1.1.15，默认关闭）：开关与它的字号
        // 从此挨在一起 —— 早先字号行飘在页面另一头，看着像另一个功能的设置。
        SwitchRow(
            title = stringResource(R.string.show_title_and_artist),
            subtitle = if (player.lyricOverlayShowTitle) {
                stringResource(R.string.adds_two_small_lines_at)
            } else {
                stringResource(R.string.lyrics_only_no_title_lines)
            },
            checked = player.lyricOverlayShowTitle,
            onCheckedChange = onLyricOverlayShowTitle,
            onReset = resetAction(player.lyricOverlayShowTitle != SettingsDefaults.OVERLAY_SHOW_TITLE) {
                onLyricOverlayShowTitle(SettingsDefaults.OVERLAY_SHOW_TITLE)
            },
        )
        if (player.lyricOverlayShowTitle) {
            // 标题字号用「正文的比例」而不是独立 sp：正文调大时标题自动跟着放大，
            // 既不用再手动追一次，也保证标题永远小于歌词、不会把歌词挤出去。
            SliderRow(
                title = stringResource(R.string.title_size_of_body, player.lyricOverlayTitlePercent),
                subtitle = stringResource(R.string.affects_only_the_two_small),
                value = player.lyricOverlayTitlePercent.toFloat(),
                range = SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.first.toFloat()..
                    SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.last.toFloat(),
                onValueChange = {
                    onLyricOverlayTitlePercent(it.roundToInt().coerceIn(
                        SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.first,
                        SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.last,
                    ))
                },
            onReset = resetAction(
                player.lyricOverlayTitlePercent != SettingsDefaults.OVERLAY_TITLE_PERCENT,
            ) { onLyricOverlayTitlePercent(SettingsDefaults.OVERLAY_TITLE_PERCENT) },
            )
            // v1.1.52：标题显示哪些内容（歌名+歌手 / 只歌名 / 只歌手）
            ActionRow(
                title = stringResource(R.string.title_content),
                subtitle = when (player.lyricOverlayTitleMode) {
                    1 -> stringResource(R.string.title_only_a_more_compact)
                    2 -> stringResource(R.string.artist_only)
                    else -> stringResource(R.string.both_title_and_artist_default)
                },
                onClick = { overlayChoice = OverlayChoice.TITLE_MODE },
                trailing = OVERLAY_TITLE_MODE_LABELS[player.lyricOverlayTitleMode],
                chevron = true,
                onReset = resetAction(
                    player.lyricOverlayTitleMode != SettingsDefaults.OVERLAY_TITLE_MODE,
                ) { onLyricOverlayTitleMode(SettingsDefaults.OVERLAY_TITLE_MODE) },
            )
        }

        // ---------------- 背景 ----------------
        // v1.1.51：原来的「底板三档 + 自定义背景开关 + 色板 + 不透明度」四件套
        // 语义重复（同一个结果有两套开关），并成「颜色 + 不透明度」两项；
        // 0% 就是无底板，文字自动换硬描边，不再需要一个开关。
        GroupLabel(stringResource(R.string.background))
        SliderRow(
            title = stringResource(R.string.background_opacity, player.lyricOverlayBgAlphaPercent),
            subtitle = if (player.lyricOverlayBgAlphaPercent == 0) {
                stringResource(R.string.t_0_no_backdrop_the_background_label)
            } else {
                stringResource(R.string.t_0_is_fully_transparent_100_label)
            },
            value = player.lyricOverlayBgAlphaPercent.toFloat(),
            range = 0f..100f,
            onValueChange = { onLyricOverlayBgAlphaPercent(it.roundToInt().coerceIn(0, 100)) },
            onReset = resetAction(
                player.lyricOverlayBgAlphaPercent != SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT,
            ) { onLyricOverlayBgAlphaPercent(SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT) },
        )
        OverlayBgPalette(
            selected = player.lyricOverlayBgColor,
            onPick = onLyricOverlayBgColor,
        )
        // 预设之外的颜色：色相 / 饱和度 / 亮度三档滑杆，可调出任意颜色
        ActionRow(
            title = stringResource(R.string.background_color),
            subtitle = if (player.lyricOverlayBgAlphaPercent == 0) {
                stringResource(R.string.opacity_is_0_so_the)
            } else {
                stringResource(R.string.beyond_the_preset_swatches_hue)
            },
            onClick = { bgColorPickerShown = true },
            swatch = player.lyricOverlayBgColor,
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayBgColor != SettingsDefaults.OVERLAY_BG_COLOR,
            ) { onLyricOverlayBgColor(SettingsDefaults.OVERLAY_BG_COLOR) },
        )
        // ---- v1.1.52 样式补全：底板圆角 / 边框 ----
        SliderRow(
            title = stringResource(R.string.backdrop_radius_dp, player.lyricOverlayCornerRadiusDp),
            subtitle = stringResource(R.string.corner_radius_of_the_backdrop),
            value = player.lyricOverlayCornerRadiusDp.toFloat(),
            range = SettingsDefaults.OVERLAY_CORNER_RADIUS_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_CORNER_RADIUS_RANGE.last.toFloat(),
            onValueChange = { onLyricOverlayCornerRadiusDp(it.roundToInt().coerceIn(
                SettingsDefaults.OVERLAY_CORNER_RADIUS_RANGE.first,
                SettingsDefaults.OVERLAY_CORNER_RADIUS_RANGE.last,
            )) },
            onReset = resetAction(
                player.lyricOverlayCornerRadiusDp != SettingsDefaults.OVERLAY_CORNER_RADIUS_DP,
            ) { onLyricOverlayCornerRadiusDp(SettingsDefaults.OVERLAY_CORNER_RADIUS_DP) },
        )
        SliderRow(
            title = if (player.lyricOverlayBorderWidthDp == 0) {
                stringResource(R.string.backdrop_border_none)
            } else {
                stringResource(R.string.backdrop_border_dp, player.lyricOverlayBorderWidthDp)
            },
            subtitle = if (player.lyricOverlayBorderWidthDp == 0) {
                stringResource(R.string.no_border_default_add_one)
            } else {
                stringResource(R.string.a_thin_border_around_the)
            },
            value = player.lyricOverlayBorderWidthDp.toFloat(),
            range = SettingsDefaults.OVERLAY_BORDER_WIDTH_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_BORDER_WIDTH_RANGE.last.toFloat(),
            onValueChange = { onLyricOverlayBorderWidthDp(it.roundToInt().coerceIn(
                SettingsDefaults.OVERLAY_BORDER_WIDTH_RANGE.first,
                SettingsDefaults.OVERLAY_BORDER_WIDTH_RANGE.last,
            )) },
            onReset = resetAction(
                player.lyricOverlayBorderWidthDp != SettingsDefaults.OVERLAY_BORDER_WIDTH_DP,
            ) { onLyricOverlayBorderWidthDp(SettingsDefaults.OVERLAY_BORDER_WIDTH_DP) },
        )
        if (player.lyricOverlayBorderWidthDp > 0) {
            ActionRow(
                title = stringResource(R.string.border_color),
                subtitle = stringResource(R.string.color_of_the_backdrop_border),
                onClick = { borderColorPickerShown = true },
                swatch = player.lyricOverlayBorderColor,
                chevron = true,
                onReset = resetAction(
                    player.lyricOverlayBorderColor != SettingsDefaults.OVERLAY_BORDER_COLOR,
                ) { onLyricOverlayBorderColor(SettingsDefaults.OVERLAY_BORDER_COLOR) },
            )
        }

        // ---------------- 行为 ----------------
        GroupLabel(stringResource(R.string.behavior))
        ActionRow(
            title = stringResource(R.string.floating_lyrics_when_paused),
            subtitle = if (player.lyricOverlayPauseMode == 1) {
                stringResource(R.string.hide_on_pause_and_reappear)
            } else {
                stringResource(R.string.fade_to_translucent_so_you)
            },
            onClick = { overlayChoice = OverlayChoice.PAUSE },
            trailing = OVERLAY_PAUSE_LABELS[player.lyricOverlayPauseMode],
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayPauseMode != SettingsDefaults.OVERLAY_PAUSE_MODE,
            ) { onLyricOverlayPauseMode(SettingsDefaults.OVERLAY_PAUSE_MODE) },
        )
        // v1.1.52：歌词行切换时的淡入时长；0 = 直接切换不动画
        SliderRow(
            title = if (player.lyricOverlayFadeMs == 0) {
                stringResource(R.string.line_transition_off)
            } else {
                stringResource(R.string.line_transition_ms, player.lyricOverlayFadeMs)
            },
            subtitle = if (player.lyricOverlayFadeMs == 0) {
                stringResource(R.string.switches_lines_instantly_with_no)
            } else {
                stringResource(R.string.fade_in_duration_for_a)
            },
            value = player.lyricOverlayFadeMs.toFloat(),
            range = SettingsDefaults.OVERLAY_FADE_RANGE.first.toFloat()..
                SettingsDefaults.OVERLAY_FADE_RANGE.last.toFloat(),
            onValueChange = { onLyricOverlayFadeMs(it.roundToInt().coerceIn(
                SettingsDefaults.OVERLAY_FADE_RANGE.first,
                SettingsDefaults.OVERLAY_FADE_RANGE.last,
            )) },
            onReset = resetAction(
                player.lyricOverlayFadeMs != SettingsDefaults.OVERLAY_FADE_MS,
            ) { onLyricOverlayFadeMs(SettingsDefaults.OVERLAY_FADE_MS) },
        )
        // v1.1.29：不显示悬浮窗的应用（360 影像 / 视频类等打开时收起浮层）
        ActionRow(
            title = stringResource(R.string.apps_without_the_overlay),
            subtitle = if (player.lyricOverlayHiddenApps.isEmpty()) {
                stringResource(R.string.nothing_configured_yet_floating_lyrics_2)
            } else {
                stringResource(R.string.apps_configured_tap_to_edit, player.lyricOverlayHiddenApps.size)
            },
            onClick = { showHiddenApps = true },
            chevron = true,
            onReset = resetAction(player.lyricOverlayHiddenApps.isNotEmpty()) {
                onLyricOverlayHiddenApps(emptySet())
            },
        )

        // v1.1.52：一键恢复默认。悬浮歌词的设置项如今横跨四组三十多项，
        // 逐行点 ↺ 太折磨 —— 给一个总入口，二次确认防误触。
        // 不动的两样：主开关（用户既然开着就是想用，恢复样式不该把它关掉）
        // 与隐藏名单（那是「哪些 App 别打扰我」的意志，不是样式）。
        ActionRow(
            title = stringResource(R.string.restore_all_defaults),
            subtitle = stringResource(R.string.restores_shape_size_position_font),
            onClick = { showResetAll = true },
        )
        } // end if (showOverlayAdvanced)
    }
    // 关闭态只留一句「打开后有什么可调」，免得这一整块消失得像设置丢了。
    if (!player.lyricOverlay) {
        Text(
            text = stringResource(R.string.once_on_you_can_adjust),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }

    // 运行状态：开关打开且已授权时显示。这一行是排障的关键 ——
    // 悬浮窗启动失败（挂窗被 ROM 拒绝 / 前台服务被系统拦）在
    // 旧版本里是完全静默的，用户只看到「开关开了但什么都没发生」。
    if (player.lyricOverlay && canDrawOverlay) {
        val failed = overlayHealth.status == LyricOverlayBus.Status.FAILED
        Text(
            text = when (overlayHealth.status) {
                LyricOverlayBus.Status.RUNNING ->
                    stringResource(R.string.running_go_to_the_home, overlayHealth.detail)
                LyricOverlayBus.Status.FAILED ->
                    stringResource(R.string.start_failed_check_settings_apps, overlayHealth.detail)
                LyricOverlayBus.Status.IDLE -> stringResource(R.string.overlay_not_running)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
    }
    if (overlayHintVisible && !canDrawOverlay) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .windShadow(level = 1, shape = RoundedCornerShape(10.dp)),
        ) {
            Text(
                text = if (overlayJumpFailed) {
                    stringResource(R.string.could_not_open_the_system)
                } else {
                    stringResource(R.string.permission_path_settings_apps_permissions)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
    // 位置调整：开关打开且已授权时才出现。
    //
    // 两种形态给的是**两条不同的操作路径**，按钮文案也据此分开：
    //  - 单条字幕：进入后横竖都能拖，窗口小而自由；
    //  - 全屏歌词：只能左右拖（高度由屏幕决定），拖完双击即可锁定。
    // 不合成一个按钮，是因为「拖到什么程度算对」在两种形态下完全不同 ——
    // 一个提示「上下左右」，另一个只提示「左右」，合并会让人以为全屏也能上下挪。
    if (player.lyricOverlay && canDrawOverlay) {
        ActionRow(
            title = when {
                !player.overlayEditing -> if (player.lyricOverlayFullHeight) {
                    stringResource(R.string.adjust_horizontal_position_on_the)
                } else {
                    stringResource(R.string.adjust_floating_lyric_position)
                }
                else -> stringResource(R.string.finish_adjusting)
            },
            subtitle = when {
                player.overlayEditing && player.lyricOverlayFullHeight ->
                    stringResource(R.string.adjusting_drag_the_lyrics_on)
                player.overlayEditing ->
                    stringResource(R.string.drag_the_lyrics_anywhere_on)
                // 满宽时全屏窗口盖住整块屏：直接进编辑态会「吃触摸但拖不动」，
                // 所以这里先说清楚点下去会发生什么（实现里会自动留出 8% 余量）。
                player.lyricOverlayFullHeight && player.lyricOverlayFullWidthPercent >= 100 ->
                    stringResource(R.string.currently_full_width_with_no)
                player.lyricOverlayFullHeight ->
                    stringResource(R.string.temporarily_unlocks_dragging_within_the)
                else ->
                    stringResource(R.string.temporarily_unlocks_dragging_the_position)
            },
            onClick = {
                val entering = !player.overlayEditing
                if (entering && player.lyricOverlayFullHeight) {
                    // 满宽进编辑态等于「能吃触摸但拖不动」：桌面被盖住、用户还以为拖成功了。
                    // 这里先收 8% 留白，把「点了就能拖」变成确定性结果。
                    val narrowed = player.lyricOverlayFullWidthPercent >= 100
                    if (narrowed) onLyricOverlayFullWidthPercent(OVERLAY_FULL_EDIT_LEAVE_PERCENT)
                    // 全屏编辑态在 App 内是隐藏的（否则会盖住本页、连「完成调整」都点不到），
                    // 所以必须说清楚「去哪儿拖」——否则用户会觉得按了没反应。
                    android.widget.Toast.makeText(
                        context,
                        if (narrowed) {
                            AppStrings.s(R.string.adjusting_width_narrowed_to_92)
                        } else {
                            AppStrings.s(R.string.adjusting_drag_the_lyrics_on_2)
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onOverlayEditing(entering)
            },
        )
    }

    // 悬浮歌词剩下的三个选项共用同一个弹层：标题 / 说明 / 选项列表都由 [OverlayChoice] 提供，
    // 选中项右侧打 ✓，点任意一项即生效并关闭（改完引擎会给一次 6 秒前台预览）。
    // v1.1.51 起字号与底板不再是选项 —— 字号是滑杆，底板并进背景的颜色 + 不透明度。
    overlayChoice?.let { choice ->
        val selected = when (choice) {
            OverlayChoice.LINES -> player.lyricOverlayLines
            OverlayChoice.ALIGN -> player.lyricOverlayAlign
            OverlayChoice.PAUSE -> player.lyricOverlayPauseMode
            OverlayChoice.TITLE_MODE -> player.lyricOverlayTitleMode
        }
        AlertDialog(
            onDismissRequest = { overlayChoice = null },
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
                                    when (choice) {
                                        OverlayChoice.LINES -> onLyricOverlayLines(option.value)
                                        OverlayChoice.ALIGN -> onLyricOverlayAlign(option.value)
                                        OverlayChoice.PAUSE -> onLyricOverlayPauseMode(option.value)
                                        OverlayChoice.TITLE_MODE -> onLyricOverlayTitleMode(option.value)
                                    }
                                    overlayChoice = null
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
                TextButton(onClick = { overlayChoice = null }) { Text(stringResource(R.string.off)) }
            },
        )
    }

    // 歌词颜色选择弹层（效果图 ㉙E）：预设色板 / 自定义。
    // 选中即落盘，悬浮窗还会顺带给一次 6 秒预览。
    if (colorPickerShown) {
        ColorPickerSheet(
            title = stringResource(R.string.floating_lyric_text_color),
            intro = stringResource(R.string.color_of_the_lyric_line),
            value = player.lyricOverlayTextColor,
            onPick = { argb -> onLyricOverlayTextColor(argb) },
            onDismiss = { colorPickerShown = false },
        )
    }

    // 背景色的任意取色（v1.1.49）。预设列表关掉：上面那块色板就是背景专用的快选，
    // 这里只负责「要一个色板上没有的颜色」。
    if (bgColorPickerShown) {
        ColorPickerSheet(
            title = stringResource(R.string.overlay_background_color),
            intro = stringResource(R.string.changes_hue_only_opacity_comes),
            value = player.lyricOverlayBgColor,
            showPresets = false,
            onPick = { argb -> onLyricOverlayBgColor(argb) },
            onDismiss = { bgColorPickerShown = false },
        )
    }

    // v1.1.52 样式补全：当前行颜色 / 描边颜色 / 边框颜色的任意取色。
    // 预设色板对「文字类」颜色帮助有限（用户调的多是氛围色），直接给 HSV 三滑杆。
    if (currentColorPickerShown) {
        ColorPickerSheet(
            title = stringResource(R.string.current_line_color),
            intro = stringResource(R.string.separate_highlight_color_for_the),
            value = if (player.lyricOverlayCurrentColor == 0) {
                player.lyricOverlayTextColor
            } else {
                player.lyricOverlayCurrentColor
            },
            showPresets = false,
            onPick = { argb -> onLyricOverlayCurrentColor(argb) },
            onDismiss = { currentColorPickerShown = false },
        )
    }
    if (strokeColorPickerShown) {
        ColorPickerSheet(
            title = stringResource(R.string.outline_color),
            intro = stringResource(R.string.outline_color_around_the_text),
            value = player.lyricOverlayStrokeColor,
            showPresets = false,
            onPick = { argb -> onLyricOverlayStrokeColor(argb) },
            onDismiss = { strokeColorPickerShown = false },
        )
    }
    if (borderColorPickerShown) {
        ColorPickerSheet(
            title = stringResource(R.string.border_color),
            intro = stringResource(R.string.color_of_the_backdrop_border_2),
            value = player.lyricOverlayBorderColor,
            showPresets = false,
            onPick = { argb -> onLyricOverlayBorderColor(argb) },
            onDismiss = { borderColorPickerShown = false },
        )
    }

    // v1.1.52：全部恢复默认的二次确认。确认后按「形态 → 尺寸位置 → 字体 →
    // 背景 → 行为」的分组顺序逐项写回出厂值（值都来自 SettingsDefaults，
    // 与各行 ↺ 的默认完全同源，不会出现两处「默认」不一致）。
    if (showResetAll) {
        AlertDialog(
            onDismissRequest = { showResetAll = false },
            title = { Text(stringResource(R.string.restore_all_defaults_2)) },
            text = {
                Text(stringResource(R.string.all_floating_lyric_styling_shape))
            },
            confirmButton = {
                TextButton(onClick = {
                    // 形态与行数
                    onLyricOverlayFullHeight(SettingsDefaults.OVERLAY_FULL_HEIGHT)
                    onLyricOverlayLines(SettingsDefaults.OVERLAY_LINES)
                    // 全屏形态的尺寸位置
                    onLyricOverlayFullWidthPercent(SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT)
                    onLyricOverlayFullHeightPercent(SettingsDefaults.OVERLAY_FULL_HEIGHT_PERCENT)
                    onLyricOverlayFullOffsetPercent(SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT)
                    onLyricOverlayFullYPercent(SettingsDefaults.OVERLAY_FULL_Y_PERCENT)
                    // 单条形态的尺寸位置
                    onLyricOverlayWidthPercent(SettingsDefaults.OVERLAY_WIDTH_PERCENT)
                    onLyricOverlayBoxHeightPercent(SettingsDefaults.OVERLAY_BOX_HEIGHT_PERCENT)
                    onLyricOverlayXPercent(SettingsDefaults.OVERLAY_X_PERCENT)
                    onLyricOverlayYPercent(SettingsDefaults.OVERLAY_Y_PERCENT)
                    // 字体
                    onLyricOverlayTextSp(SettingsDefaults.OVERLAY_TEXT_SP)
                    onLyricOverlayAlign(SettingsDefaults.OVERLAY_ALIGN)
                    onLyricOverlayTextColor(SettingsDefaults.OVERLAY_TEXT_COLOR)
                    onLyricOverlayCurrentColor(SettingsDefaults.OVERLAY_CURRENT_COLOR)
                    onLyricOverlayDimPercent(SettingsDefaults.OVERLAY_DIM_PERCENT)
                    onLyricOverlayStrokeWidthDp(SettingsDefaults.OVERLAY_STROKE_WIDTH_DP)
                    onLyricOverlayStrokeColor(SettingsDefaults.OVERLAY_STROKE_COLOR)
                    onLyricOverlayLineSpacingDp(SettingsDefaults.OVERLAY_LINE_SPACING_DP)
                    // 标题
                    onLyricOverlayShowTitle(SettingsDefaults.OVERLAY_SHOW_TITLE)
                    onLyricOverlayTitlePercent(SettingsDefaults.OVERLAY_TITLE_PERCENT)
                    onLyricOverlayTitleMode(SettingsDefaults.OVERLAY_TITLE_MODE)
                    // 背景
                    onLyricOverlayBgAlphaPercent(SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT)
                    onLyricOverlayBgColor(SettingsDefaults.OVERLAY_BG_COLOR)
                    onLyricOverlayCornerRadiusDp(SettingsDefaults.OVERLAY_CORNER_RADIUS_DP)
                    onLyricOverlayBorderWidthDp(SettingsDefaults.OVERLAY_BORDER_WIDTH_DP)
                    onLyricOverlayBorderColor(SettingsDefaults.OVERLAY_BORDER_COLOR)
                    // 行为
                    onLyricOverlayPauseMode(SettingsDefaults.OVERLAY_PAUSE_MODE)
                    onLyricOverlayFadeMs(SettingsDefaults.OVERLAY_FADE_MS)
                    showResetAll = false
                }) { Text(stringResource(R.string.restore)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetAll = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 「不显示悬浮窗的应用」弹窗（v1.1.29）：本机全部可启动应用 + 每行开关。
    // 逐个勾选时整体写回（引擎侧去重 + 剔空白项），名单与检测都即时生效。
    if (showHiddenApps) {
        OverlayHiddenAppsDialog(
            hiddenApps = player.lyricOverlayHiddenApps,
            onToggle = { pkg, checked ->
                val next = player.lyricOverlayHiddenApps.toMutableSet()
                if (checked) next.add(pkg) else next.remove(pkg)
                onLyricOverlayHiddenApps(next)
            },
            onClear = { onLyricOverlayHiddenApps(emptySet()) },
            onDismiss = { showHiddenApps = false },
        )
    }
}

/**
 * 全屏歌词的**页内示意图**（v1.1.22）。
 *
 * 为什么需要它：全屏这一档在 App 前台一律不画浮层 —— 整屏歌词会盖住设置页，
 * 宽度滑块、靠左/居中/靠右按钮全被字压住（真机上就是这么被用户拍回来的），
 * 而且整屏吃触摸时连「完成调整」都点不到。但把浮层一关，用户改宽度就彻底
 * 看不到反馈了。所以用一块 16:9 的缩略屏把「面板占多宽、站在哪」画出来：
 * 纯 Compose 绘制，不碰 WindowManager，也不需要悬浮窗权限。
 *
 * 图里三条横向短线代表歌词行（当前行一条更亮），底部那条浅色带是车机 Dock
 * —— 面板高度恒为「屏高 − 56dp」，示意里也把这段让位画出来，免得用户以为
 * 满高贴着最底。
 */
@Composable
private fun OverlayFullPreviewMock(
    widthPercent: Int,
    offsetPercent: Int,
    heightPercent: Int,
    yPercent: Int,
) {
    val screenColor = MaterialTheme.colorScheme.surface
    val frameColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val panelColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val panelEdge = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val currentLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f)
    val dockColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)

    Column(modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)) {
        // 缩略图不铺满整行：铺满时它自己就成了「一块大面板」，
        // 反而看不出「面板在屏幕里占多少」。限宽 + 居中，让它明显是「一台屏幕的缩影」。
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    // 定高 + 16:9 反推宽度（170dp 高 ≈ 302dp 宽）：既是一眼能看懂的缩略图，
                    // 又不会在车机的大屏上变成一块占掉整屏的「大面板」——
                    // 早先写成 fillMaxWidth(0.62f) 时算出来近 500dp 宽，整块滚出视口。
                    .height(170.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(screenColor)
                    .border(1.dp, frameColor, RoundedCornerShape(8.dp)),
            ) {
                val panelW = size.width * widthPercent.coerceIn(1, 100) / 100f
                val travel = (size.width - panelW).coerceAtLeast(0f)
                val x = travel * offsetPercent.coerceIn(0, 100) / 100f
                // 与 OverlayPositioner.fullFrame 同口径：底部要让出车机 Dock。
                // 示意图按屏高的 6% 画（56dp 在车机常见屏高下大致就是这个比例）。
                val dockH = size.height * 0.06f
                val usableH = size.height - dockH
                // v1.1.48：高度与纵向位置也画出来 —— 全屏浮层在 App 内不绘制
                // （否则会盖住这些滑块），没有这张图就是「调完当场什么都看不到」
                val panelH = usableH * heightPercent.coerceIn(30, 100) / 100f
                val travelY = (usableH - panelH).coerceAtLeast(0f)
                val y = travelY * yPercent.coerceIn(0, 100) / 100f

                drawRect(color = panelColor, topLeft = Offset(x, y), size = Size(panelW, panelH))
                // 左右两条边线：面板贴边时也能一眼看出边界在哪
                drawRect(color = panelEdge, topLeft = Offset(x, y), size = Size(1.5f, panelH))
                drawRect(
                    color = panelEdge,
                    topLeft = Offset((x + panelW - 1.5f).coerceAtLeast(x), y),
                    size = Size(1.5f, panelH),
                )

                // 歌词示意：上 2 条压暗、中间一条亮（当前行）、下 2 条压暗
                val lineH = (panelH * 0.035f).coerceAtLeast(3f)
                listOf(0.32f, 0.44f, 0.58f, 0.70f, 0.82f).forEach { fy ->
                    val isCurrent = fy == 0.58f
                    val lineW = if (isCurrent) panelW * 0.58f else panelW * 0.40f
                    drawRect(
                        color = if (isCurrent) currentLineColor else lineColor,
                        topLeft = Offset(x + (panelW - lineW) / 2f, panelH * fy),
                        size = Size(lineW, if (isCurrent) lineH * 1.5f else lineH),
                    )
                }

                // 底部 Dock 让位带（这一段不画歌词）
                drawRect(
                    color = dockColor,
                    topLeft = Offset(0f, panelH),
                    size = Size(size.width, dockH),
                )
            }
        }
        Text(
            text = stringResource(R.string.diagram_width_and_the_grey, widthPercent, OverlayPositioner.offsetLabel(offsetPercent)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 背景色板（v1.1.48）＋「预设之外」的入口（v1.1.49）。
 *
 * 先用预设色而不是直接甩一个 HSV 取色器：车机上用手指调色相滑块基本调不准，
 * 而背景色实际只需要「够深、能压住画面」这几档，一点就中。
 * 但「只有这 8 个色」确实不够用（用户 2026-10-22 提的就是这句），
 * 所以在色板下面补一行 [ActionRow] 打开任意取色弹层 —— 快选与自由取色各司其职。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayBgPalette(selected: Int, onPick: (Int) -> Unit) {
    val presets = listOf(
        0xFF101014.toInt(), // 深黑（默认）
        0xFF1D2330.toInt(), // 深蓝灰
        0xFF14261C.toInt(), // 深绿
        0xFF2A1620.toInt(), // 酒红
        0xFF241B33.toInt(), // 深紫
        0xFF2B2118.toInt(), // 深棕
        0xFF3A3A3A.toInt(), // 中灰
        0xFFE8E8E8.toInt(), // 浅灰（配深色文字）
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.choose_background_color),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        // 用 FlowRow 而不是 Row：手机竖屏放 8 个 36dp 圆点会溢出，
        // 换行比裁掉最后几个颜色体面得多
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            presets.forEach { color ->
                val isSelected = (selected and 0x00FFFFFF) == (color and 0x00FFFFFF)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color or 0xFF000000.toInt()))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable { onPick(color) },
                )
            }
        }
    }
}

/**
 * 位置百分比的人话。
 *
 * 两个轴共用同一份设置语义（0..100 是「可移动余量」的百分比），但说法必须按轴分开 ——
 * 同一个数字在横向是「靠左」、在纵向是「靠上」，混着写只会让人看不懂。
 */
private fun positionLabel(percent: Int, horizontal: Boolean = true): String {
    val v = percent.coerceIn(0, 100)
    val start = if (horizontal) AppStrings.s(R.string.far_left) else AppStrings.s(R.string.top)
    val end = if (horizontal) AppStrings.s(R.string.far_right) else AppStrings.s(R.string.bottom)
    return when {
        v <= 2 -> start
        v >= 98 -> end
        kotlin.math.abs(v - 50) <= 2 -> AppStrings.s(R.string.center)
        v < 50 -> AppStrings.s(
            R.string.offset,
            AppStrings.s(if (horizontal) R.string.dir_left else R.string.dir_top),
            v,
        )
        else -> AppStrings.s(
            R.string.offset,
            AppStrings.s(if (horizontal) R.string.dir_right else R.string.dir_bottom),
            v,
        )
    }
}

/**
 * 预设网格卡（v1.1.78，FR-06）。
 *
 * 卡片信息三层：预设名（选中转 primary + ✓）/ 一句话定位 / 关键参数摘要。
 * 选中态由参数反推匹配决定，**不是**「最后点的是哪张」——用户手动调参后
 * 高亮会自然让位给「自定义」，界面永远与实际参数一致（R5 的直接受益）。
 */
@Composable
private fun OverlayPresetCard(
    preset: OverlayPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    scheme.primary.copy(alpha = 0.10f)
                } else {
                    scheme.surfaceVariant.copy(alpha = 0.45f)
                },
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.current_preset),
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            preset.tagline,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            preset.summary(),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
