package com.luckylearning.windtone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.currentOverlayPreset
import com.luckylearning.windtone.playback.Pitch
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.UpdateUiState
import com.luckylearning.windtone.state.overlayPresetParams
import com.luckylearning.windtone.ui.theme.windShadow

/**
 * 车载模式专用设置页（v1.1.32；v1.1.98+ FR-07 悬浮窗样式大卡）。
 *
 * ## 为什么要另做一套
 *
 * Google 车机设计规范（Android for Cars · Driver Distraction Guidelines）对「设置」这类
 * 非驾驶主任务的要求可以压成三句：
 *  1. **层级要浅**：行驶中不允许多级导航，一个设置最多「打开 → 拨一下」；
 *  2. **控件要大**：触控目标 ≥ 76dp、主操作 ≥ 88dp，行内不做小号文字与密集列表；
 *  3. **now playing 常在**：任何画面都要能一步回到正在播放。
 *
 * 手机版设置是「主页 6 张分类卡 → 子页 → 弹层」三层，车机横屏下还要滚很久 ——
 * 第三条更是完全没有：设置一盖上来，底部大播放条就被挡住，想回播放页得
 * 「返回 → 返回 → 点播放条」。
 *
 * 所以车载下改成**一页到底**：
 *  - 只保留行车中真正会改的项（速度 / 变调 / 跳过静音 / 续播 / 开机播 / 歌词字号 / 悬浮歌词 / 曲库 / 更新）；
 *  - 全部就地操作，没有子页 —— 层级 1；
 *  - 底部常驻 [CarNowPlayingDock]，点歌名或「播放页」按钮**一步回到播放页**；
 *  - 均衡器、悬浮歌词外观、数据维护、下载线路这类「停车才调」的，收进最后一行
 *    「完整设置」，进去才是手机版那套六卡结构（明确标注「建议停车后调整」）。
 *
 * 手机端（`carMode == false`）一行代码都不走这里，仍是原来的六卡 + 子页。
 */
@Composable
internal fun CarSettingsScreen(
    player: PlayerUiState,
    update: UpdateUiState,
    minSongDurationSec: Int,
    canDrawOverlay: Boolean,
    onClose: () -> Unit,
    /** 一步回播放页（调用方负责先关掉设置页） */
    onOpenPlayer: () -> Unit,
    /** 进手机版那套完整设置（停车后调整高级项） */
    onOpenFullSettings: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    onPitch: (Int) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onAutoResume: (Boolean) -> Unit,
    onAutoPlayOnBoot: (Boolean) -> Unit,
    onLyricScale: (Float) -> Unit,
    onLyricOverlay: (Boolean) -> Unit,
    /** 套用车机悬浮歌词样式预设（FR-07；写全局外观参数，与 OverlayActions.applyOverlayPreset 同源） */
    onApplyOverlayPreset: (OverlayPreset) -> Unit,
    /** 悬浮窗正文字号（sp）；与预设快照 textSp 同源 */
    onLyricOverlayTextSp: (Int) -> Unit,
    /** 悬浮窗水平 / 垂直位置（可移动余量百分比 0..100） */
    onLyricOverlayXPercent: (Int) -> Unit,
    onLyricOverlayYPercent: (Int) -> Unit,
    onRescan: () -> Unit,
    onOpenScanScope: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ------------------------------------------------------------ 顶栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                CarSettingsIconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回曲库",
                        tint = scheme.onBackground,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                // 标题右侧不放任何跳转：车机上「多一个入口」就多一次误触，
                // 回播放页的入口在底部常驻条上（离手最近，且不会随滚动跑掉）。
                Text(
                    "v${update.currentVersion}",
                    fontSize = 16.sp,
                    color = scheme.onSurfaceVariant,
                )
            }

            // ---------------------------------------------------------- 正文
            // 限宽 1100dp：与手机版设置页的车载上限保持一致，避免开关被推到
            // 屏幕另一端（1707dp 全宽时视线要横跨一两千像素）。
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .widthIn(max = 1100.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 32.dp),
            ) {
                // ------------------------------------------------ 播放
                CarSectionLabel("播放")
                CarStepperRow(
                    title = "播放速度",
                    subtitle = "0.75x ~ 2.00x，每档 0.05x",
                    value = "%.2fx".format(player.speed),
                    onMinus = {
                        onSpeed((player.speed - SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX))
                    },
                    onPlus = {
                        onSpeed((player.speed + SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX))
                    },
                    minusDescription = "降低播放速度",
                    plusDescription = "提高播放速度",
                )
                CarStepperRow(
                    title = "变调",
                    subtitle = "升降调不改速度（±12 半音 = 一个八度）",
                    value = Pitch.labelOf(player.pitchSemitones),
                    onMinus = {
                        onPitch((player.pitchSemitones - 1).coerceAtLeast(Pitch.MIN_SEMITONES))
                    },
                    onPlus = {
                        onPitch((player.pitchSemitones + 1).coerceAtMost(Pitch.MAX_SEMITONES))
                    },
                    minusDescription = "降低一个半音",
                    plusDescription = "升高一个半音",
                )
                CarToggleRow(
                    title = "跳过静音",
                    subtitle = "自动跳过录音开头的空白段",
                    checked = player.skipSilence,
                    onCheckedChange = onSkipSilence,
                )
                CarToggleRow(
                    title = "继续上次播放",
                    subtitle = "打开应用时从上次的进度接着播",
                    checked = player.autoResume,
                    onCheckedChange = onAutoResume,
                )
                CarToggleRow(
                    title = "开机后台播放",
                    subtitle = "通电后后台续播，不打开界面（需系统「开机启动」权限）",
                    checked = player.autoPlayOnBoot,
                    onCheckedChange = onAutoPlayOnBoot,
                )

                // ------------------------------------------------ 歌词
                CarSectionLabel("歌词")
                CarStepperRow(
                    title = "歌词字号",
                    subtitle = "只影响播放页歌词，车载下已自动放大一档",
                    value = "%.1fx".format(player.lyricScale),
                    onMinus = {
                        onLyricScale((player.lyricScale - LYRIC_SCALE_STEP).coerceIn(
                            LYRIC_SCALE_MIN, LYRIC_SCALE_MAX,
                        ))
                    },
                    onPlus = {
                        onLyricScale((player.lyricScale + LYRIC_SCALE_STEP).coerceIn(
                            LYRIC_SCALE_MIN, LYRIC_SCALE_MAX,
                        ))
                    },
                    minusDescription = "减小歌词字号",
                    plusDescription = "增大歌词字号",
                )
                CarToggleRow(
                    title = "桌面悬浮歌词",
                    subtitle = if (canDrawOverlay) {
                        "退到桌面后在最上层显示当前歌词行"
                    } else {
                        "需要先在系统里允许「显示在其他应用上层」，打开时会跳转授权"
                    },
                    checked = player.lyricOverlay,
                    onCheckedChange = onLyricOverlay,
                )

                // ------------------------------------------------ 悬浮窗样式（FR-07）
                // 车机设置专用大卡：C1/C2/C3 + 字号步进 + 四角 chips。
                // 套用写回全局外观参数（R4）；不含「驾驶中简化」（FR-08 已否决）。
                CarSectionLabel("悬浮窗样式")
                Text(
                    "车机专属三套 · 点选后立刻套用，退出车机后样式仍保持",
                    fontSize = 15.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                val matchedCarPreset = currentOverlayPreset(player.overlayPresetParams())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OverlayPreset.CAR.forEach { preset ->
                        CarOverlayPresetCard(
                            preset = preset,
                            selected = matchedCarPreset?.id == preset.id,
                            icon = carOverlayPresetIcon(preset.id),
                            onClick = { onApplyOverlayPreset(preset) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                CarStepperRow(
                    title = "文字大小",
                    subtitle = "悬浮窗正文字号（12~60sp）；改动后若偏离预设会显示为自定义",
                    value = "${player.lyricOverlayTextSp}sp",
                    onMinus = {
                        onLyricOverlayTextSp(
                            (player.lyricOverlayTextSp - OVERLAY_TEXT_SP_STEP).coerceIn(
                                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first,
                                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last,
                            ),
                        )
                    },
                    onPlus = {
                        onLyricOverlayTextSp(
                            (player.lyricOverlayTextSp + OVERLAY_TEXT_SP_STEP).coerceIn(
                                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first,
                                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last,
                            ),
                        )
                    },
                    minusDescription = "减小悬浮窗字号",
                    plusDescription = "增大悬浮窗字号",
                )
                CarCornerChipsRow(
                    xPercent = player.lyricOverlayXPercent,
                    yPercent = player.lyricOverlayYPercent,
                    onSelect = { x, y ->
                        onLyricOverlayXPercent(x)
                        onLyricOverlayYPercent(y)
                    },
                )

                // ------------------------------------------------ 曲库
                CarSectionLabel("曲库")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CarWideButton("重新扫描", AppIcons.Cloud, onRescan, Modifier.weight(1f))
                    CarWideButton("扫描范围", AppIcons.Folder, onOpenScanScope, Modifier.weight(1f))
                }
                Text(
                    if (minSongDurationSec > 0) {
                        "当前过滤：短于 $minSongDurationSec 秒的音频不进曲库"
                    } else {
                        "当前未开启短音频过滤"
                    },
                    fontSize = 15.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )

                // ------------------------------------------------ 更新
                CarSectionLabel("更新")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "当前版本 v${update.currentVersion}",
                            fontSize = 20.sp,
                            color = scheme.onSurface,
                        )
                        Text(
                            when {
                                update.downloading -> "正在下载 ${update.progress}%"
                                update.info != null -> "发现新版本 v${update.info?.version}"
                                update.checking -> "正在检查…"
                                else -> "有新版本时会在这里提示"
                            },
                            fontSize = 15.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    CarWideButton(
                        label = "检查更新",
                        icon = AppIcons.Info,
                        onClick = onCheckUpdate,
                        modifier = Modifier.width(220.dp),
                    )
                }

                // ------------------------------------------------ 更多（停车后）
                CarSectionLabel("更多")
                CarEntryRow(
                    title = "完整设置",
                    subtitle = "均衡器与音效 · 悬浮歌词外观 · 双语歌词 · 数据维护 · 下载线路",
                    onClick = onOpenFullSettings,
                )
                Text(
                    "以上选项建议停车后调整；行驶中只用车机这一页。",
                    fontSize = 15.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 24.dp),
                )
            }

            // ------------------------------------------------- 常驻「正在播放」
            CarNowPlayingDock(
                player = player,
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

// ================================================================ 私有规格常量

/** 速度档位：与手机版滑块同范围（0.75x ~ 2.00x），车机上改成一档一按，不用拖 */
private const val SPEED_MIN = 0.75f
private const val SPEED_MAX = 2.0f
private const val SPEED_STEP = 0.05f

/** 歌词字号档位：与手机版滑块同范围（0.7x ~ 1.6x） */
private const val LYRIC_SCALE_MIN = 0.7f
private const val LYRIC_SCALE_MAX = 1.6f
private const val LYRIC_SCALE_STEP = 0.05f

/** 悬浮窗正文字号步进（sp）；范围见 SettingsDefaults.OVERLAY_TEXT_SP_RANGE */
private const val OVERLAY_TEXT_SP_STEP = 2

/** 行高下限：Google 车机规范的 76dp 触控目标，整行可点 */
/** 常规档行高（v1.1.98 起实际值由 [CarMetrics.rowMinHeight] 决定） */
private val CAR_ROW_MIN_HEIGHT: Dp = 76.dp

/** 常驻播放条高度（比首页的 112dp 矮一档：设置在车上不是主画面，但要留住入口） */
private val CAR_DOCK_BAR_HEIGHT: Dp = 100.dp

// ================================================================ 行组件

@Composable
private fun CarSectionLabel(text: String) {
    Text(
        text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

/**
 * 开关行：整行可点（76dp 高），右侧开关只是状态的呈现。
 * 焦点环是车机刚需 —— 旋钮 / 方向盘按键操作时没有触摸点。
 */
@Composable
private fun CarToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = if (focused) androidx.compose.foundation.BorderStroke(3.dp, scheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .heightIn(min = CAR_ROW_MIN_HEIGHT)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .windShadow(level = 1, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, color = scheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 15.sp, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(18.dp))
            // 开关本身不单独接点击：整行已经是热区，两处 onClick 会造成
            // 「点边缘不响应」的错觉（Compose 的点击只给最内层）。
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}

/**
 * 减 / 加步进行：车机上不用 Slider —— 拖动需要按住并平移，行车中极难命中，
 * 而且拖到什么值只能看数字跳。两个 76dp 圆键一档一按，反馈确定。
 */
@Composable
private fun CarStepperRow(
    title: String,
    subtitle: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusDescription: String,
    plusDescription: String,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .heightIn(min = CAR_ROW_MIN_HEIGHT)
            .windShadow(level = 1, shape = RoundedCornerShape(16.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, color = scheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 15.sp, color = scheme.onSurfaceVariant)
            }
            CarStepButton(MinusIcon, minusDescription, onMinus)
            Text(
                value,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = 150.dp)
                    .padding(horizontal = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            CarStepButton(Icons.Filled.Add, plusDescription, onPlus)
        }
    }
}

@Composable
private fun CarStepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CAR_ROW_MIN_HEIGHT)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .then(if (focused) Modifier.border(3.dp, scheme.primary, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = scheme.onSurface, modifier = Modifier.size(30.dp))
    }
}

/** 宽按钮：曲库 / 更新这类「一个动作」的原语，76dp 高、图标 + 文字 */
@Composable
private fun CarWideButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        border = if (focused) androidx.compose.foundation.BorderStroke(3.dp, scheme.primary) else null,
        modifier = modifier
            .height(rememberCarMetrics().rowMinHeight)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .windShadow(level = 1, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 20.sp, color = scheme.onSurface)
        }
    }
}

/** 进入另一层（车机上只留「完整设置」这一处） */
@Composable
private fun CarEntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = if (focused) androidx.compose.foundation.BorderStroke(3.dp, scheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CAR_ROW_MIN_HEIGHT)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .windShadow(level = 1, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, color = scheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 15.sp, color = scheme.onSurfaceVariant)
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun CarSettingsIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CAR_TOUCH_TARGET)
            .clip(CircleShape)
            .then(if (focused) Modifier.border(3.dp, scheme.primary, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        content()
    }
}

// ================================================================ 常驻正在播放

/**
 * 设置页底部的常驻「正在播放」条 —— 本版「任何页一步回播放页」的落点。
 *
 * 三条路都通向播放页：封面、歌名区、最右显式按钮。控制键只留 ⏮ ⏯ ⏭
 * （用户在设置页最常见的是「换一首 / 停一下」，不是收藏）。
 * 没有当前曲时仍然显示，只把控制键换成一句说明 —— 入口不能时有时无。
 */
@Composable
private fun CarNowPlayingDock(
    player: PlayerUiState,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val song = player.current

    Surface(color = scheme.surfaceContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(CAR_DOCK_BAR_HEIGHT)
                .padding(horizontal = 20.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceContainerHighest)
                    .clickable(onClick = onOpenPlayer),
                contentAlignment = Alignment.Center,
            ) {
                if (song != null) {
                    CoverArt(song = song, size = 64.dp, cornerRadius = 14.dp)
                } else {
                    Icon(
                        AppIcons.MusicNote,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenPlayer),
            ) {
                Text(
                    song?.title ?: "还没有在播放的歌曲",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song?.artistOrUnknown ?: "在曲库里点一首歌即可开始",
                    fontSize = 15.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (song != null) {
                Spacer(Modifier.width(12.dp))
                CarSettingsIconButton(onClick = onPrevious) {
                    Icon(AppIcons.SkipPrevious, contentDescription = "上一首", tint = scheme.onSurface, modifier = Modifier.size(30.dp))
                }
                CarSettingsIconButton(onClick = onTogglePlay) {
                    Icon(
                        if (player.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (player.isPlaying) "暂停" else "播放",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(34.dp),
                    )
                }
                CarSettingsIconButton(onClick = onNext) {
                    Icon(AppIcons.SkipNext, contentDescription = "下一首", tint = scheme.onSurface, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            // 显式入口：车机上不指望用户猜到「点歌名能进播放页」
            Surface(
                color = scheme.primary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .height(rememberCarMetrics().rowMinHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenPlayer),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Icon(
                        AppIcons.Lyrics,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "播放页",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onPrimary,
                    )
                }
            }
        }
    }
}

// 「减」图标 Material 核心图标集里没有（`Icons.Filled.Remove` 在体积巨大的
// icons-extended 包里），AppIcons 也没有现成的，这里按同一套路径写法自绘一条：
// **绝对坐标、整块填充**（相对小写 m 在比亚迪车机的渲染链上会画歪，见 AppIcons 注释）。

// ================================================================ FR-07 车机悬浮窗样式

/** 车机三套预设卡的示意图标（效果图约 56dp） */
private fun carOverlayPresetIcon(id: String): ImageVector = when (id) {
    "C1" -> AppIcons.Speed
    "C2" -> AppIcons.Lyrics
    else -> AppIcons.MusicNote
}

/**
 * 四角快捷落点（FR-07「位置四角 chips」）。
 * 百分比是可移动余量上的位置：0 = 贴该方向边，100 = 贴对边。
 */
private data class CarCornerChip(
    val label: String,
    val xPercent: Int,
    val yPercent: Int,
)

private val CAR_CORNER_CHIPS = listOf(
    CarCornerChip("左上", 0, 0),
    CarCornerChip("右上", 100, 0),
    CarCornerChip("左下", 0, 100),
    CarCornerChip("右下", 100, 100),
)

/** 当前 x/y 是否贴近某一四角落点（±8 视为选中，避免拖动微调后完全对不上） */
private fun CarCornerChip.matches(xPercent: Int, yPercent: Int): Boolean =
    kotlin.math.abs(xPercent - this.xPercent) <= 8 &&
        kotlin.math.abs(yPercent - this.yPercent) <= 8

/**
 * 车机预设大卡：56dp 图标 + 名称 + 一句话定位；选中 primary 描边。
 * 与手机 SettingsOverlayPage 的 OverlayPresetCard 同语义，版式按车机触控放大。
 */
@Composable
private fun CarOverlayPresetCard(
    preset: OverlayPreset,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHigh,
        shape = shape,
        border = when {
            focused || selected -> androidx.compose.foundation.BorderStroke(3.dp, scheme.primary)
            else -> androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)
        },
        modifier = modifier
            .heightIn(min = 108.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .windShadow(level = 1, shape = shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "套用悬浮窗样式 ${preset.displayName}"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerHighest),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    preset.displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    preset.tagline,
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "当前预设",
                    tint = scheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** 显示位置：四角 chips（FR-07） */
@Composable
private fun CarCornerChipsRow(
    xPercent: Int,
    yPercent: Int,
    onSelect: (x: Int, y: Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CAR_ROW_MIN_HEIGHT)
            .windShadow(level = 1, shape = RoundedCornerShape(16.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("显示位置", fontSize = 22.sp, color = scheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text("四角快捷；精细拖动仍可在完整设置里调", fontSize = 15.sp, color = scheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CAR_CORNER_CHIPS.forEach { chip ->
                    val selected = chip.matches(xPercent, yPercent)
                    val chipShape = RoundedCornerShape(14.dp)
                    Surface(
                        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
                        shape = chipShape,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .focusable()
                            .clip(chipShape)
                            .clickable { onSelect(chip.xPercent, chip.yPercent) }
                            .semantics { contentDescription = "悬浮窗位置${chip.label}" },
                    ) {
                        Text(
                            chip.label,
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) scheme.onPrimary else scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

private val MinusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Remove",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M5 11L19 11L19 13L5 13Z").toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
