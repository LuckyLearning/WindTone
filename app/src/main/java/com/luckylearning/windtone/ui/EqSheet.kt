@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.luckylearning.windtone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.EqPresets
import com.luckylearning.windtone.playback.ReverbPresets

/**
 * 「均衡器与音效」弹层（效果图 ⑲）。
 *
 * 2026-10-17 从 `Sheets.kt` 拆出并重排：原先是「一组横向滚动的预设片 + 一长条滑块」，
 * 预设只能横向翻着找、四组可调项挤在一起看不出层次。现在按**决策顺序**分三段：
 *  ① 预设——先给一张自动换行、一屏能看全的预设网，选中项下面说明「这条曲线到底做了什么」；
 *  ② 频段——先把当前曲线画出来（折线预览），再给频段滑块；曲线是「调之前先看一眼」的依据；
 *  ③ 增强音效——低音 / 环绕 / 响度 / 混响，每项一行「标题 + 右侧当前值」，值不再藏在滑块里。
 *
 * 三处口径沿用既有约定，不要改：
 *  - 预设曲线全部内置（[EqPresets]，不用平台预设——各 ROM 名字不一致且无法本地化）；
 *  - 手动拖频段滑块 = 离开预设，进入「自定义」（`presetId` 清空）；
 *  - 标题行的 ↺ 只在偏离出厂状态时出现，点它 = 关掉均衡器 + 频段全平 + 全部增强归零。
 */
@Composable
fun EqSheet(onClose: () -> Unit) {
    val state by AudioFx.state.collectAsState()
    // 只有真的偏离出厂状态才画 ↺（与设置页同一条口径：默认状态下不摆按钮）。
    // 混响也在判据里：单开了混响同样是「动过」。
    val reverbPreset = ReverbPresets.byId(state.reverbId)
    val customized = state.available && (
        state.enabled ||
            state.presetId.isNotEmpty() ||
            state.bassStrength > 0 ||
            state.virtualStrength > 0 ||
            state.loudnessGain > 0 ||
            ReverbPresets.isCustomized(reverbPreset) ||
            state.bandLevels.any { it != 0 }
        )

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容比屏幕长时整页滚（预设网 + 曲线 + 频段 + 四项增强，一屏放不下）
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("均衡器与音效", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 恢复默认：关掉均衡器 + 频段全平 + 三处增强归零 + 混响关闭（= 全新安装那份值）。
                // 均衡器的值没有对应的设置页行（那一行只是弹层入口），所以这里必须有。
                if (customized) {
                    ResetButton(onClick = AudioFx::reset)
                    Spacer(Modifier.width(4.dp))
                }
                Switch(checked = state.enabled, onCheckedChange = AudioFx::setEnabled, enabled = state.available)
            }

            if (!state.available) {
                Text(
                    "当前设备不可用或播放器尚未启动。\n开始播放任意一首歌曲后即可调节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                PresetSection(state)
                CurveSection(state)
                EnhancementSection(state, reverbPreset)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ================================================================ ① 预设

/**
 * 预设选择：分组 tab + **自动换行**的预设片 + 选中项说明卡。
 *
 * 为什么从横向滚动换成换行网：一组 8 个片，横向滚动时最后两个永远在屏幕外，
 * 「有没有我想要的」这件事本身就得靠滑一下才知道；换行之后整组一屏看全。
 */
@Composable
private fun PresetSection(state: AudioFx.EqState) {
    val current = EqPresets.byId(state.presetId)
    // 初始组 = **当前预设所在的那一组**（自定义则落在「听感」）。
    // 不留 key：弹层每次打开都是一次新的组合，这里只决定「打开时先显示哪一组」，
    // 之后用户切 tab 就归用户管 —— 否则选了流派预设再打开会停在「听感」，
    // 选中态那一枚藏在另一组里，看着像「没选上」。
    var group by remember { mutableStateOf(current?.group ?: EqPresets.Group.FEEL) }

    SectionLabel("预设")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EqPresets.Group.entries.forEach { g ->
            FilterChip(
                selected = group == g,
                onClick = { group = g },
                label = { Text(g.label) },
            )
        }
    }
    Text(
        group.hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        EqPresets.group(group).forEach { p ->
            FilterChip(
                selected = state.presetId == p.id,
                onClick = { AudioFx.setPreset(p.id) },
                label = { Text(p.name) },
            )
        }
    }

    // 选中项说明卡：预设名 + 一句话。自定义时也要有卡，否则这一块会「空着」，看着像坏了。
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                current?.name ?: "自定义",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                current?.desc ?: "手动拖过频段滑块。再选一个预设可回到内置曲线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// ================================================================ ② 频段与曲线

/** 频段：曲线预览在上（调之前先看一眼形状），频段滑块在下 */
@Composable
private fun CurveSection(state: AudioFx.EqState) {
    SectionLabel("频段")
    Text(
        "拖动任一频段即离开预设，进入自定义",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    EqCurvePreview(
        levels = state.bandLevels,
        minMb = state.bandMinMb,
        maxMb = state.bandMaxMb,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )

    state.bandLevels.forEachIndexed { band, level ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.centerFreqs.getOrElse(band) { "" },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(46.dp),
            )
            Slider(
                value = level.toFloat(),
                onValueChange = { AudioFx.setBandLevel(band, it.toInt()) },
                valueRange = state.bandMinMb.toFloat()..state.bandMaxMb.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatGainDb(level),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(38.dp),
            )
        }
    }
}

/**
 * 曲线预览：一根折线 + 零点虚线 + 折点圆点。
 *
 * 高度用 [EqPresets.previewHeights] 归一化到**设备量程**上，因此「形状可信、
 * 绝对高度不代表真实增益」——真实数值看频段滑块右侧的 dB 标签。
 * 这条曲线不是装饰：用户在拖滑块之前，先要看得出自己调出来的是个什么形状。
 */
@Composable
private fun EqCurvePreview(
    levels: IntArray,
    minMb: Int,
    maxMb: Int,
    modifier: Modifier = Modifier,
) {
    val heights = EqPresets.previewHeights(levels, minMb, maxMb)
    val zeroFraction = EqPresets.previewHeights(intArrayOf(0), minMb, maxMb).firstOrNull() ?: 0.5f
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier.height(76.dp)) {
        if (heights.isEmpty()) return@Canvas
        val zeroY = size.height * (1f - zeroFraction)
        // 零点虚线：没有它，一条全平曲线会被看成「贴着底」而不是「在中线上」
        drawLine(
            color = grid,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        val step = if (heights.size > 1) size.width / (heights.size - 1) else 0f
        val points = heights.mapIndexed { i, h -> Offset(i * step, size.height * (1f - h)) }

        // 折线与零点线之间填一层淡色，形状更容易一眼读出来
        drawPath(
            path = Path().apply {
                moveTo(points.first().x, zeroY)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, zeroY)
                close()
            },
            color = line.copy(alpha = 0.16f),
        )
        for (i in 0 until points.size - 1) {
            drawLine(
                color = line,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        points.forEach { drawCircle(color = line, radius = 3.dp.toPx(), center = it) }
    }
}

// ================================================================ ③ 增强音效

/** 低音增强 / 环绕声 / 响度增强 / 混响 */
@Composable
private fun EnhancementSection(state: AudioFx.EqState, reverbPreset: ReverbPresets.Preset?) {
    SectionLabel("增强音效")
    FxSlider(
        title = "低音增强",
        valueText = if (state.bassStrength > 0) "${state.bassStrength / 10}%" else "关闭",
        value = state.bassStrength.toFloat(),
        range = 0f..state.bassMax.toFloat(),
        onValueChange = { AudioFx.setBassStrength(it.toInt()) },
    )
    FxSlider(
        title = "环绕声",
        valueText = if (state.virtualStrength > 0) "${state.virtualStrength / 10}%" else "关闭",
        value = state.virtualStrength.toFloat(),
        range = 0f..1000f,
        onValueChange = { AudioFx.setVirtualStrength(it.toInt()) },
    )
    FxSlider(
        title = "响度增强",
        valueText = if (state.loudnessGain > 0) "+${state.loudnessGain} mB" else "关闭",
        value = state.loudnessGain.toFloat(),
        range = 0f..state.loudnessMax.toFloat(),
        onValueChange = { AudioFx.setLoudnessGain(it.toInt()) },
        hint = "把音量偏小的录音拉到统一响度，过大易削波，建议不超过 +600 mB。",
    )

    // ---------------- 混响 ----------------
    // 档位由平台效果器给死、没有连续量，所以用一排片而不是滑块（与均衡器预设同一套交互）
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Text("混响", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            (reverbPreset ?: ReverbPresets.OFF).name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!state.reverbAvailable) {
        Text(
            "本机不支持混响效果，选择会保留但不会生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            ReverbPresets.ALL.forEach { p ->
                FilterChip(
                    // 空串 = 从未选过，界面按「关闭」显示，与落盘的默认值一致
                    selected = (reverbPreset ?: ReverbPresets.OFF) == p,
                    onClick = { AudioFx.setReverbPreset(p.id) },
                    label = { Text(p.name) },
                )
            }
        }
        Text(
            (reverbPreset ?: ReverbPresets.OFF).desc,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 一行连续量：标题 + 右侧当前值 + 滑块（+ 可选备注）。
 *
 * 右边的值必须画出来 —— 原先滑块下面没有任何数字，「拖到哪儿算大」只能靠猜。
 */
@Composable
private fun FxSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    hint: String? = null,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 增益 mB → 带符号的 dB（`0.0` / `+2.4` / `-3.0`），与效果图 ⑲ 的数值口径一致 */
private fun formatGainDb(levelMb: Int): String =
    if (levelMb == 0) "0.0" else "%+.1f".format(levelMb / 100.0)
