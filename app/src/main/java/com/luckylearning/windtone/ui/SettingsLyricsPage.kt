package com.luckylearning.windtone.ui

import android.content.Context
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.overlay.OverlayPositioner
import com.luckylearning.windtone.overlay.OverlaySnapshot
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState

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

/**
 * 全屏歌词在「满宽却要点调整」时自动收窄到的宽度（%）。
 *
 * 留 8% 就够左右挪：全屏宽度 92% 在 1920 屏上有 154px 的余量，
 * 车机手指宽度量级下手感正常，视觉上也几乎看不出变窄。
 */
private const val OVERLAY_FULL_EDIT_LEAVE_PERCENT = 92

@Composable
internal fun SettingsLyricsPage(
    player: PlayerUiState,
    canDrawOverlay: Boolean,
    overlayHealth: LyricOverlayBus.Health,
    onLyricScale: (Float) -> Unit,
    onLyricBilingual: (Int) -> Unit,
    onLyricOverlay: (Boolean) -> Unit,
    onLyricOverlayLines: (Int) -> Unit,
    onLyricOverlayFullHeight: (Boolean) -> Unit,
    /** 全屏模式的宽度（屏宽百分比，30..100）—— v1.1.20，与单条字幕的宽度分开 */
    onLyricOverlayFullWidthPercent: (Int) -> Unit,
    /** 全屏模式的水平位置（余量百分比：0 最左 / 50 居中 / 100 最右） */
    onLyricOverlayFullOffsetPercent: (Int) -> Unit,
    onLyricOverlayShowTitle: (Boolean) -> Unit,
    onLyricOverlayTitleScale: (Int) -> Unit,
    onLyricOverlayTextScale: (Int) -> Unit,
    onLyricOverlayAlign: (Int) -> Unit,
    onLyricOverlayBackdrop: (Int) -> Unit,
    onLyricOverlayPauseMode: (Int) -> Unit,
    onLyricOverlayWidthPercent: (Int) -> Unit,
    onLyricOverlayTextColor: (Int) -> Unit,
    onOverlayEditing: (Boolean) -> Unit,
    onOverlayPreview: () -> Unit,
) {
    val context = LocalContext.current
    // 悬浮歌词的外观选项（行数 / 字号 / 对齐 / 底板 / 暂停行为）共用一个选项弹层
    var overlayChoice by remember { mutableStateOf<OverlayChoice?>(null) }
    // 歌词颜色选择弹层是否打开（v1.1.8 起只剩「悬浮窗文字颜色」一处，
    // 原先用 ColorSlot 区分三处颜色，现在一个布尔量就够）
    var colorPickerShown by remember { mutableStateOf(false) }
    var overlayHintVisible by remember { mutableStateOf(false) }
    // 三条跳转路都走不通（ROM 连系统授权页都不给开）→ 指引卡里改显示手动路径
    var overlayJumpFailed by remember { mutableStateOf(false) }
    var bilingualChoice by remember { mutableStateOf(false) }
    LaunchedEffect(canDrawOverlay) {
        if (canDrawOverlay) {
            overlayHintVisible = false
            overlayJumpFailed = false
        }
    }


    // ------------------------------------------------ 播放页歌词
    SectionTitle("播放页歌词")
    SliderRow(
        title = "歌词字号 ${"%.1f".format(player.lyricScale)}x",
        value = player.lyricScale,
        range = 0.7f..1.6f,
        onValueChange = onLyricScale,
        onReset = resetAction(abs(player.lyricScale - SettingsDefaults.LYRIC_SCALE) > 0.001f) {
            onLyricScale(SettingsDefaults.LYRIC_SCALE)
        },
    )
    // v1.1.18：双语歌词显示模式（默认「中英都显示」= 历史行为）
    ActionRow(
        title = "双语歌词 · ${BilingualMode.of(player.lyricBilingual).shortLabel}",
        subtitle = "带翻译的歌词显示哪些行；只影响显示，导出的 LRC 不受影响",
        onClick = { bilingualChoice = true },
        chevron = true,
    )
    // ⚠️ 这里原先还有两项：「逐字歌词」开关与「已唱部分的颜色」（v1.1.8 移除）。
    //    逐字染色（含给普通 LRC 派生字级时间轴、以及它专用的已唱色）已整体删除，
    //    播放页现在只有「当前行整行高亮」——靠主题色 + 字号 + 字重区分，没有可调项。
    //    重开后两个键（`lyric_word_by_word` / `lyric_sung_color`）会留在老用户
    //    的 SharedPreferences 里，是刻意不清理的死键，别再当 bug 排查。

    // ------------------------------------------------ 桌面悬浮歌词
    SectionTitle("桌面悬浮歌词")
    SwitchRow(
        title = "桌面悬浮歌词",
        subtitle = if (canDrawOverlay) {
            "退到桌面 / 导航页后，在屏幕最上层显示当前歌词行（App 内自动隐藏，开启瞬间有 6 秒预览）"
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
        // v1.1.19：全屏高度。放在所有外观项之前 —— 它是**形态**开关（单条字幕 / 整屏歌词），
        // 行数、宽度、位置这些都在它之下，先定形态再调细节。
        SwitchRow(
            title = "全屏高度",
            subtitle = if (player.lyricOverlayFullHeight) {
                "占满屏幕（底部给车机 Dock 留 56dp），按播放页排版：当前行居中放大，上下各 5 句压暗"
            } else {
                "打开后从单条字幕变成整屏歌词，内容与播放页歌词区一致"
            },
            checked = player.lyricOverlayFullHeight,
            onCheckedChange = onLyricOverlayFullHeight,
            onReset = resetAction(player.lyricOverlayFullHeight != SettingsDefaults.OVERLAY_FULL_HEIGHT) {
                onLyricOverlayFullHeight(SettingsDefaults.OVERLAY_FULL_HEIGHT)
            },
        )
        // v1.1.20：「形态 → 尺寸 → 位置」三段式。
        // 全屏高度这一档要能改宽度、也要能左右挪，而且**不能和单条字幕共用一套值**——
        // 两种形态的排版诉求相反（整屏看歌词 vs 一行字幕），共用只会互相打架。
        // 所以这里按形态分岔，控件、按钮、落盘字段三样都各走各的。
        if (player.lyricOverlayFullHeight) {
            SliderRow(
                title = "全屏宽度 ${player.lyricOverlayFullWidthPercent}%",
                subtitle = if (player.lyricOverlayFullWidthPercent >= 100) {
                    "已占满屏宽（此时左右没有余量）。调小一点即可左右挪动，两侧留白也更聚焦"
                } else {
                    "屏宽的 ${player.lyricOverlayFullWidthPercent}%，两侧共留出 " +
                        "${100 - player.lyricOverlayFullWidthPercent}% 的余量供左右移动；" +
                        "宽度越窄，长句折行越早"
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
                title = "水平位置 " + OverlayPositioner.offsetLabel(player.lyricOverlayFullOffsetPercent),
                subtitle = if (player.lyricOverlayFullWidthPercent >= 100) {
                    "宽度 100% 时没有可移动的余量，位置固定；先把上面的宽度调小"
                } else {
                    "在左右留白之间移动歌词面板；也可以到桌面上直接左右拖（两处是同一份设置）"
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
            // 三个大按钮：车机上用滑块找「正好靠边」很难，一键到位更实际
            OverlayOffsetPresets(
                current = player.lyricOverlayFullOffsetPercent,
                enabled = player.lyricOverlayFullWidthPercent < 100,
                onPick = onLyricOverlayFullOffsetPercent,
            )
            // 页内示意图（v1.1.22）：全屏浮层在 App 内不再绘制（见 LyricOverlayService
            // 的 fullInApp），否则整屏歌词会压在这些滑块和按钮上，一边调一边看不见。
            // 代价是「改完当场看不到」，所以这里补一张跟屏幕同比例的缩略图：
            // 调宽度它就跟着变窄，调位置它就跟着左右走，调完再退到桌面看真效果。
            OverlayFullPreviewMock(
                widthPercent = player.lyricOverlayFullWidthPercent,
                offsetPercent = player.lyricOverlayFullOffsetPercent,
            )
        } else {
            ActionRow(
                title = "悬浮歌词行数",
                subtitle = overlayLinesHint(player.lyricOverlayLines),
                onClick = { overlayChoice = OverlayChoice.LINES },
                chevron = true,
                onReset = resetAction(player.lyricOverlayLines != SettingsDefaults.OVERLAY_LINES) {
                    onLyricOverlayLines(SettingsDefaults.OVERLAY_LINES)
                },
            )
            // 宽度之所以是滑块而不是档位：屏幕尺寸千差万别，档位永远差一个「刚好」。
            SliderRow(
                title = "悬浮歌词宽度 ${player.lyricOverlayWidthPercent}%",
                // 宽度与「横向能拖多远」是同一件事的两面，到了 100% 就直接说破，
                // 否则用户会像 2026-10-16 那样把「拖不动」当 bug 报回来。
                subtitle = if (player.lyricOverlayWidthPercent >= 100) {
                    "已占满屏宽，无法左右移动；调小一点即可拖到屏幕任意位置"
                } else {
                    "屏幕宽度的百分比（1–100%）。窗口越窄，能左右移动的范围越大；" +
                        "窗口越宽，长句折行越晚"
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
        }
        // v1.1.15：顶部「歌名 + 歌手」两行小字（默认关闭，不打扰老用户）
        SwitchRow(
            title = "显示歌名与歌手",
            subtitle = if (player.lyricOverlayShowTitle) {
                "悬浮窗最上面加两行小字：歌名 / 歌手，下面是歌词"
            } else {
                "只显示歌词，不加标题行"
            },
            checked = player.lyricOverlayShowTitle,
            onCheckedChange = onLyricOverlayShowTitle,
            onReset = resetAction(player.lyricOverlayShowTitle != SettingsDefaults.OVERLAY_SHOW_TITLE) {
                onLyricOverlayShowTitle(SettingsDefaults.OVERLAY_SHOW_TITLE)
            },
        )
        // 标题字号只在开着的时候才出现：关着还摆一行字号设置，只会让人以为它没生效
        if (player.lyricOverlayShowTitle) {
            ActionRow(
                title = "歌名歌手字号",
                subtitle = "比歌词小一号；车机上看着小就调大一档",
                onClick = { overlayChoice = OverlayChoice.TITLE_SCALE },
                trailing = OVERLAY_TEXT_SCALE_LABELS[player.lyricOverlayTitleScale],
                chevron = true,
                onReset = resetAction(
                    player.lyricOverlayTitleScale != SettingsDefaults.OVERLAY_TITLE_SCALE,
                ) { onLyricOverlayTitleScale(SettingsDefaults.OVERLAY_TITLE_SCALE) },
            )
        }

        // 悬浮歌词的外观选项：字号 / 对齐 / 底板 / 暂停行为。
        // 所有项共用同一个选项弹层，改完立刻给一次 6 秒预览。
        // v1.1.21 起这一整块只在开关打开时出现 —— 早先允许「开着之前先预设」，
        // 但实际用起来是：关着开关还摆一排会失效的控件，用户改完回桌面看不到任何
        // 变化，只会当成 bug。宁可少给一个预设置口，也不给一个骗人的控件。
        ActionRow(
            title = "悬浮歌词字号",
            subtitle = "大屏与车机上建议调大一档",
            onClick = { overlayChoice = OverlayChoice.TEXT_SCALE },
            trailing = OVERLAY_TEXT_SCALE_LABELS[player.lyricOverlayTextScale],
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayTextScale != SettingsDefaults.OVERLAY_TEXT_SCALE,
            ) { onLyricOverlayTextScale(SettingsDefaults.OVERLAY_TEXT_SCALE) },
        )
        ActionRow(
            title = "悬浮歌词对齐",
            subtitle = if (player.lyricOverlayAlign == 1) {
                "歌词在窗内靠左排，适合长句与固定视线"
            } else {
                "歌词在窗内水平居中（窗口位置由拖动决定）"
            },
            onClick = { overlayChoice = OverlayChoice.ALIGN },
            trailing = OVERLAY_ALIGN_LABELS[player.lyricOverlayAlign],
            chevron = true,
            onReset = resetAction(player.lyricOverlayAlign != SettingsDefaults.OVERLAY_ALIGN) {
                onLyricOverlayAlign(SettingsDefaults.OVERLAY_ALIGN)
            },
        )
        ActionRow(
            title = "悬浮歌词底板",
            subtitle = if (player.lyricOverlayBackdrop == 0) {
                "无底板最干净，但亮色壁纸上白字可能看不清"
            } else {
                "垫一层底色，任何壁纸上都读得清"
            },
            onClick = { overlayChoice = OverlayChoice.BACKDROP },
            trailing = OVERLAY_BACKDROP_LABELS[player.lyricOverlayBackdrop],
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayBackdrop != SettingsDefaults.OVERLAY_BACKDROP,
            ) { onLyricOverlayBackdrop(SettingsDefaults.OVERLAY_BACKDROP) },
        )
        ActionRow(
            title = "暂停时的悬浮歌词",
            subtitle = if (player.lyricOverlayPauseMode == 1) {
                "暂停即隐藏，恢复播放时再出现"
            } else {
                "淡出到半透明，方便看清唱到哪一句"
            },
            onClick = { overlayChoice = OverlayChoice.PAUSE },
            trailing = OVERLAY_PAUSE_LABELS[player.lyricOverlayPauseMode],
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayPauseMode != SettingsDefaults.OVERLAY_PAUSE_MODE,
            ) { onLyricOverlayPauseMode(SettingsDefaults.OVERLAY_PAUSE_MODE) },
        )

        // 文字颜色。宽度滑块已经随形态放到上面那组里（全屏 / 单条各一套），不在这里重复。
        ActionRow(
            title = "悬浮歌词文字颜色",
            subtitle = "悬浮窗是固定色，不跟随主题；当前行与上下句靠明暗区分",
            onClick = { colorPickerShown = true },
            swatch = player.lyricOverlayTextColor,
            chevron = true,
            onReset = resetAction(
                player.lyricOverlayTextColor != SettingsDefaults.OVERLAY_TEXT_COLOR,
            ) { onLyricOverlayTextColor(SettingsDefaults.OVERLAY_TEXT_COLOR) },
        )
    }
    // 关闭态只留一句「打开后有什么可调」，免得这一整块消失得像设置丢了。
    if (!player.lyricOverlay) {
        Text(
            text = "打开后可调形态（单条字幕 / 全屏歌词）、宽度、位置、字号、对齐、底板、颜色与暂停表现。",
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
                    "运行中：${overlayHealth.detail}，退到桌面即可看到歌词行"
                LyricOverlayBus.Status.FAILED ->
                    "启动失败：${overlayHealth.detail}。" +
                        "可到 设置 → 应用 → 显示悬浮窗（部分系统叫「后台弹出界面」）确认，" +
                        "详情见 关于与诊断 → 崩溃日志"
                LyricOverlayBus.Status.IDLE -> "悬浮窗未启动"
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
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = if (overlayJumpFailed) {
                    "未能打开系统授权页（本机 ROM 未提供该入口）。\n" +
                        "请手动前往：设置 → 应用 → 听风 → 权限 → " +
                        "「显示悬浮窗」或「出现在其他应用上层」→ 允许。\n" +
                        "部分系统还需要同时允许「后台弹出界面」，否则授权后仍不显示。"
                } else {
                    "授权路径：设置 → 应用 → 权限 → 出现在其他应用上 → 允许。\n" +
                        "部分比亚迪车机（DiLink）需要把本应用加入白名单，或先强停一次应用进程，" +
                        "否则授权后界面可能黑屏或歌词不显示。"
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
                    "在桌面上左右调整位置"
                } else {
                    "调整悬浮歌词位置"
                }
                else -> "完成调整"
            },
            subtitle = when {
                player.overlayEditing && player.lyricOverlayFullHeight ->
                    "已进入调整：退到桌面左右拖动歌词，双击即可锁定；也可以点这里锁定"
                player.overlayEditing ->
                    "拖动屏幕上的歌词（横竖都可以）到合适位置，然后点这里锁定"
                // 满宽时全屏窗口盖住整块屏：直接进编辑态会「吃触摸但拖不动」，
                // 所以这里先说清楚点下去会发生什么（实现里会自动留出 8% 余量）。
                player.lyricOverlayFullHeight && player.lyricOverlayFullWidthPercent >= 100 ->
                    "当前满宽没有余量。点一下会先把宽度收到 92% 留出两侧空间，就能左右拖了"
                player.lyricOverlayFullHeight ->
                    "临时解锁，在留白范围内左右拖动；也可以直接用上面的宽度与水平位置滑块"
                else ->
                    "临时解锁，把歌词拖到想要的位置；位置会记住，锁定后不挡触摸"
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
                            "已进入调整：宽度收到 92% 留出余量。退到桌面左右拖动，双击锁定"
                        } else {
                            "已进入调整：退到桌面左右拖动歌词，双击锁定"
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onOverlayEditing(entering)
            },
        )
    }

    // v1.1.3：三个子页入口行已升级为主页的六张分类卡（见本文件开头的
    // SettingsPage.ROOT 分支）—— 原来这里只有音乐库 / 更新 / 关于与诊断
    // 三条 ActionRow，现在外观 / 播放 / 歌词也各自成了分类卡。

    // 悬浮歌词的五个外观选项共用同一个弹层：标题 / 说明 / 选项列表都由 [OverlayChoice] 提供，
    // 选中项右侧打 ✓，点任意一项即生效并关闭（改完引擎会给一次 6 秒前台预览）。
    overlayChoice?.let { choice ->
        val selected = when (choice) {
            OverlayChoice.LINES -> player.lyricOverlayLines
            OverlayChoice.TITLE_SCALE -> player.lyricOverlayTitleScale
            OverlayChoice.TEXT_SCALE -> player.lyricOverlayTextScale
            OverlayChoice.ALIGN -> player.lyricOverlayAlign
            OverlayChoice.BACKDROP -> player.lyricOverlayBackdrop
            OverlayChoice.PAUSE -> player.lyricOverlayPauseMode
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
                                        OverlayChoice.TITLE_SCALE -> onLyricOverlayTitleScale(option.value)
                                        OverlayChoice.TEXT_SCALE -> onLyricOverlayTextScale(option.value)
                                        OverlayChoice.ALIGN -> onLyricOverlayAlign(option.value)
                                        OverlayChoice.BACKDROP -> onLyricOverlayBackdrop(option.value)
                                        OverlayChoice.PAUSE -> onLyricOverlayPauseMode(option.value)
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
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { overlayChoice = null }) { Text("关闭") }
            },
        )
    }

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
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { bilingualChoice = false }) { Text("关闭") }
            },
        )
    }

    // 歌词颜色选择弹层（效果图 ㉙E）：预设色板 / 自定义。
    // 选中即落盘，悬浮窗还会顺带给一次 6 秒预览。
    if (colorPickerShown) {
        ColorPickerSheet(
            title = "悬浮歌词文字颜色",
            intro = "桌面悬浮歌词里那句话的颜色。悬浮窗固定不跟随主题。",
            value = player.lyricOverlayTextColor,
            onPick = { argb -> onLyricOverlayTextColor(argb) },
            onDismiss = { colorPickerShown = false },
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
private fun OverlayFullPreviewMock(widthPercent: Int, offsetPercent: Int) {
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
                val panelH = size.height - dockH

                drawRect(color = panelColor, topLeft = Offset(x, 0f), size = Size(panelW, panelH))
                // 左右两条边线：面板贴边时也能一眼看出边界在哪
                drawRect(color = panelEdge, topLeft = Offset(x, 0f), size = Size(1.5f, panelH))
                drawRect(
                    color = panelEdge,
                    topLeft = Offset((x + panelW - 1.5f).coerceAtLeast(x), 0f),
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
            text = "示意图：宽 $widthPercent%、" +
                "${OverlayPositioner.offsetLabel(offsetPercent)}；" +
                "底部灰色是车机 Dock（56dp 内不画歌词）。" +
                "实际效果退到桌面查看 —— 全屏歌词不会盖住这一页，否则没法边调边看。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 全屏歌词的三个位置快捷键：靠左 / 居中 / 靠右。
 *
 * 为什么在滑块之外还要这组按钮：车机上手指精确定位滑块很难，
 * 「靠左贴边」这种诉求用滑块找不准，一键到位才实际；
 * 滑块留着是为了「就差一点点」的微调。两者写的是同一个百分比，不会互相打架。
 *
 * 宽度占满屏时整组置灰 —— 此时余量为 0，按了也不会动，置灰比按了没反应诚实。
 */
@Composable
private fun OverlayOffsetPresets(
    current: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp),
    ) {
        listOf(0 to "靠左", 50 to "居中", 100 to "靠右").forEach { (value, label) ->
            val selected = abs(current - value) <= 2
            // 未选中也给一圈细描边：三块 surfaceVariant 底色的按钮并排时，
            // 不加边框会糊成一条横条，看着像「不可点」而不是「三个选项」。
            val stroke = when {
                !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
            }
            Surface(
                color = when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, stroke),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onPick(value) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            selected -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
