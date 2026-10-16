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


// ================================================================ 子页：歌词

// ================================================================ 子页：歌词

@Composable
internal fun SettingsLyricsPage(
    player: PlayerUiState,
    canDrawOverlay: Boolean,
    overlayHealth: LyricOverlayBus.Health,
    onLyricScale: (Float) -> Unit,
    onLyricWordByWord: (Boolean) -> Unit,
    onLyricSungColor: (Int) -> Unit,
    onLyricOverlay: (Boolean) -> Unit,
    onLyricOverlayLines: (Int) -> Unit,
    onLyricOverlayTextScale: (Int) -> Unit,
    onLyricOverlayAlign: (Int) -> Unit,
    onLyricOverlayBackdrop: (Int) -> Unit,
    onLyricOverlayPauseMode: (Int) -> Unit,
    onLyricOverlayWidthPercent: (Int) -> Unit,
    onLyricOverlayTextColor: (Int) -> Unit,
    onLyricOverlaySungColor: (Int) -> Unit,
    onOverlayEditing: (Boolean) -> Unit,
    onOverlayPreview: () -> Unit,
) {
    val context = LocalContext.current
    // 悬浮歌词的外观选项（行数 / 字号 / 对齐 / 底板 / 暂停行为）共用一个选项弹层
    var overlayChoice by remember { mutableStateOf<OverlayChoice?>(null) }
    // v1.1.4：三处可配的歌词颜色（播放页已唱色 / 悬浮窗文字色 / 悬浮窗已唱色）
    // 共用一个选择弹层 —— 它们只是「写回哪个字段」不同，界面完全一样
    var colorPicker by remember { mutableStateOf<ColorSlot?>(null) }
    var overlayHintVisible by remember { mutableStateOf(false) }
    // 三条跳转路都走不通（ROM 连系统授权页都不给开）→ 指引卡里改显示手动路径
    var overlayJumpFailed by remember { mutableStateOf(false) }
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
    SwitchRow(
        title = "逐字歌词",
        subtitle = "当前行随播放逐字点亮；无逐字时间轴的歌词按行时长自动派生",
        checked = player.lyricWordByWord,
        onCheckedChange = onLyricWordByWord,
        onReset = resetAction(player.lyricWordByWord != SettingsDefaults.LYRIC_WORD_BY_WORD) {
            onLyricWordByWord(SettingsDefaults.LYRIC_WORD_BY_WORD)
        },
    )
    // v1.1.4：逐字歌词「已唱部分」的颜色。此前写死成 colorScheme.primary，
    // 现在允许固定成某个颜色；「跟随主题」保持原样 —— 那是默认，不是特例。
    ActionRow(
        title = "已唱部分的颜色",
        subtitle = "逐字歌词里已经唱过的那一段；默认跟随主题色（随封面取色漂移）",
        onClick = { colorPicker = ColorSlot.LYRIC_SUNG },
        trailing = LyricsColors.label(player.lyricSungColor),
        chevron = true,
        onReset = resetAction(
            player.lyricSungColor != SettingsDefaults.LYRIC_SUNG_COLOR,
        ) { onLyricSungColor(SettingsDefaults.LYRIC_SUNG_COLOR) },
    )

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
    // 悬浮歌词的五个外观选项：行数 / 字号 / 对齐 / 底板 / 暂停行为。
    // 都紧跟在开关下方，与它绑成一个整体；开关关着也允许**预设**，
    // 所以不做可见性 gating。五项共用同一个选项弹层，改完立刻给一次 6 秒预览。
    ActionRow(
        title = "悬浮歌词行数",
        subtitle = if (player.lyricOverlayLines == 3) {
            "3 行：上一行 / 当前行 / 下一行，当前行高亮"
        } else {
            "1 行：只显示当前歌词行（一行放不下时自动折行）"
        },
        onClick = { overlayChoice = OverlayChoice.LINES },
        chevron = true,
        onReset = resetAction(player.lyricOverlayLines != SettingsDefaults.OVERLAY_LINES) {
            onLyricOverlayLines(SettingsDefaults.OVERLAY_LINES)
        },
    )
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

    // v1.1.4 新增三项：宽度滑块 + 两处颜色。都紧跟在五个外观选项之后，仍属「外观」范畴。
    // 宽度之所以是滑块而不是档位：屏幕尺寸千差万别，档位永远差一个「刚好」。
    SliderRow(
        title = "悬浮歌词宽度 ${player.lyricOverlayWidthPercent}%",
        // 宽度与「横向能拖多远」是同一件事的两面，到了 100% 就直接说破，
        // 否则用户会像 2026-10-16 那样把「拖不动」当 bug 报回来。
        subtitle = if (player.lyricOverlayWidthPercent >= 100) {
            "已占满屏宽，悬浮窗无法左右移动；调小一点即可拖到屏幕任意位置"
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
    ActionRow(
        title = "悬浮歌词文字颜色",
        subtitle = "还没唱到的那部分文字的颜色，默认纯白",
        onClick = { colorPicker = ColorSlot.OVERLAY_TEXT },
        swatch = player.lyricOverlayTextColor,
        chevron = true,
        onReset = resetAction(
            player.lyricOverlayTextColor != SettingsDefaults.OVERLAY_TEXT_COLOR,
        ) { onLyricOverlayTextColor(SettingsDefaults.OVERLAY_TEXT_COLOR) },
    )
    ActionRow(
        title = "悬浮歌词已唱颜色",
        subtitle = "逐字高亮里已唱过的那一段，默认暖黄",
        onClick = { colorPicker = ColorSlot.OVERLAY_SUNG },
        swatch = player.lyricOverlaySungColor,
        chevron = true,
        onReset = resetAction(
            player.lyricOverlaySungColor != SettingsDefaults.OVERLAY_SUNG_COLOR,
        ) { onLyricOverlaySungColor(SettingsDefaults.OVERLAY_SUNG_COLOR) },
    )
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
    // 位置调整：开关打开且已授权时才出现。进入后悬浮窗临时可拖动，
    // 拖完点「完成」锁回穿透态。
    if (player.lyricOverlay && canDrawOverlay) {
        ActionRow(
            title = if (player.overlayEditing) "完成调整" else "调整悬浮歌词位置",
            subtitle = if (player.overlayEditing) {
                "拖动屏幕上的歌词（横竖都可以）到合适位置，然后点这里锁定（锁定后点击穿透）"
            } else {
                "临时解锁，把歌词拖到想要的位置；位置会记住，锁定后不挡触摸"
            },
            onClick = { onOverlayEditing(!player.overlayEditing) },
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

    // 三处歌词颜色共用同一个选择弹层（效果图 ㉙E）：跟随主题（仅播放页已唱色）/
    // 预设色板 / 自定义。选中即落盘，悬浮窗那两处还会顺带给一次 6 秒预览。
    colorPicker?.let { slot ->
        ColorPickerSheet(
            title = slot.title,
            intro = slot.intro,
            value = when (slot) {
                ColorSlot.LYRIC_SUNG -> player.lyricSungColor
                ColorSlot.OVERLAY_TEXT -> player.lyricOverlayTextColor
                ColorSlot.OVERLAY_SUNG -> player.lyricOverlaySungColor
            },
            canFollowTheme = slot.canFollowTheme,
            onPick = { argb ->
                when (slot) {
                    ColorSlot.LYRIC_SUNG -> onLyricSungColor(argb)
                    ColorSlot.OVERLAY_TEXT -> onLyricOverlayTextColor(argb)
                    ColorSlot.OVERLAY_SUNG -> onLyricOverlaySungColor(argb)
                }
            },
            onDismiss = { colorPicker = null },
        )
    }
}
