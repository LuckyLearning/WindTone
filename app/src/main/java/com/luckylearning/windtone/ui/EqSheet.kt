@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.luckylearning.windtone.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.luckylearning.windtone.playback.SoundSchemes
import com.luckylearning.windtone.playback.TrackVolume
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 「均衡器与音效」弹层（效果图 ⑲）。
 *
 * 2026-10-17 从 `Sheets.kt` 拆出并重排：原先是「一组横向滚动的预设片 + 一长条滑块」，
 * 预设只能横向翻着找、四组可调项挤在一起看不出层次。现在按**决策顺序**分四段：
 *  ① 方案——一键应用一组完整听感（[SoundSchemes]：曲线 + 低音 + 环绕 + 响度 + 混响打包），
 *     选的是「想要什么感觉」，不想调参数的用户到这一层就能走（v1.1.52）；
 *  ② 预设——均衡器曲线（[EqPresets]），方案之上的单维选择；
 *  ③ 频段——先把当前曲线画出来（折线预览），再给频段滑块；
 *  ④ 增强音效——低音 / 环绕 / 响度 / 混响，每项一行「标题 + 右侧当前值」；
 *  ⑤ 播放过渡——淡入淡出与曲间音量平衡（[TrackVolume]），不依赖效果器可用性，
 *     所以放在 `available` 判断之外（v1.1.52）。
 *
 * 另有「按住对比原声」按钮：按住期间全部效果器临时旁路（[AudioFx.setBypassed]），
 * 供 A/B 对比 —— 音效好不好，当下听一耳朵比看曲线直观得多。
 *
 * 四处口径沿用既有约定，不要改：
 *  - 预设曲线全部内置（不用平台预设——各 ROM 名字不一致且无法本地化）；
 *  - 手动拖频段滑块 / 任一增强滑块 = 离开预设与方案，进入「自定义」（id 清空）；
 *  - 标题行的 ↺ 只在偏离出厂状态时出现，点它 = 关掉均衡器 + 频段全平 + 全部增强归零
 *    + 方案与进车快照一并回厂；
 *  - 播放过渡两项不受「设备不支持」影响（走的是播放器音量，不是音频效果器）。
 */
@Composable
fun EqSheet(onClose: () -> Unit) {
    val state by AudioFx.state.collectAsState()
    val trackVolume by TrackVolume.state.collectAsState()
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

    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容比屏幕长时整页滚（预设网 + 曲线 + 频段 + 四项增强，一屏放不下）。
                // 上限从固定 560dp 改为屏高 70%（2026-10-22 用户要求，与播放设置同一口径）
                .heightIn(max = sheetMaxHeight())
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

            // 绕道系统输出时必须说清楚作用范围（v1.1.23）：车机上媒体会话常没有
            // 自己的效果链，我们退到 audioSession 0 才让它能用 —— 但那样连导航
            // 提示音也会被一起调。
            if (state.available && state.globalEffects) {
                Text(
                    "本机的播放会话不支持音效，已改挂到系统音频输出：" +
                        "这些调节对所有声音生效（包括导航提示音与铃音）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!state.available) {
                // 2026-10-18 真机反馈：车机上明明在放歌，这里却弹「播放器尚未启动」。
                // 原因是只有 available=false 一个信号，界面只能猜。现在按原因分开说
                // （文案见 [eqUnavailableCopy]，纯函数、有单测锁住）。
                Text(
                    eqUnavailableCopy(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                if (state.unavailableDetail.isNotBlank()) {
                    Text(
                        text = "诊断：${state.unavailableDetail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
            } else {
                SchemeSection(state)
                PresetSection(state)
                CurveSection(state)
                EnhancementSection(state, reverbPreset)
                if (state.enabled) AbCompareButton()
            }
            // 淡入淡出 / 音量平衡走的是播放器音量，与效果器可用性无关：
            // 车机上效果器建不出来时这两项照常能用，所以放在 available 判断之外
            PlaybackTransitionSection(trackVolume)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ================================================================ ① 方案

/**
 * 音效方案：一键应用一组完整听感（[SoundSchemes]）。
 *
 * 形态与预设网一致（自动换行 + 选中说明卡），但它排在预设**之前** ——
 * 决策顺序是「先选感觉，再选曲线，最后才谈参数」。
 * 车载方案带「车载」标但手机模式同样可见可选：一辆车一个听感是常态，
 * 但没必要为它做模式判断把界面搞复杂。
 */
@Composable
private fun SchemeSection(state: AudioFx.EqState) {
    SectionLabel("方案")
    Text(
        "一键应用完整听感；微调下方任一参数即离开方案",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        SoundSchemes.ALL.forEach { s ->
            FilterChip(
                selected = state.schemeId == s.id,
                onClick = { AudioFx.setScheme(s.id) },
                label = { Text(if (s.forCar) "${s.name}·车载" else s.name) },
            )
        }
    }
    val current = SoundSchemes.byId(state.schemeId)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                current?.name ?: "未使用方案",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                current?.desc ?: "从上面选一个，一键换一组完整听感；下面各段仍可单独细调。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 按住期间全部效果器旁路，松手恢复 —— 音效好不好，当场对比才知道 */
@Composable
private fun AbCompareButton() {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { AudioFx.setBypassed(pressed) }
    Button(
        onClick = { /* 按住即生效，点击无动作 */ },
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        Text(if (pressed) "已旁路音效（原始声音）" else "按住对比原声")
    }
}

// ================================================================ ⑤ 播放过渡

/**
 * 淡入淡出 + 曲间音量平衡（[TrackVolume]）。
 *
 * 这两项走的是**播放器音量**而不是音频效果器：车机上效果器建不出来时
 * 照常可用，所以渲染在 `available` 判断之外。响度平衡是衰减制（只压
 * 响度大的歌），分析在后台做、按曲目缓存，首次播放某首时还没结果属预期。
 */
@Composable
private fun PlaybackTransitionSection(tv: TrackVolume.State) {
    SectionLabel("播放过渡")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("淡入淡出", style = MaterialTheme.typography.bodyMedium)
            Text(
                "自动切歌时旧曲结尾淡出、新曲开头淡入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = tv.fadeEnabled, onCheckedChange = TrackVolume::setFadeEnabled)
    }
    if (tv.fadeEnabled) {
        FxSlider(
            title = "时长",
            valueText = "%.1f 秒".format(tv.fadeDurationMs / 1000f),
            value = tv.fadeDurationMs.toFloat(),
            range = 500f..6000f,
            onValueChange = { TrackVolume.setFadeDurationMs(it.toInt()) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("音量平衡", style = MaterialTheme.typography.bodyMedium)
            Text(
                "自动压低响度过大的歌，切歌不再忽大忽小；首次播放某首时后台分析，稍后生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = tv.loudnessBalance, onCheckedChange = TrackVolume::setLoudnessBalance)
    }
}

// ================================================================ ② 预设

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

// ================================================================ ③ 频段与曲线

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

// ================================================================ ④ 增强音效

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

/**
 * 音效不可用时给用户看的那句话（v1.1.22）。
 *
 * 抽成纯函数是为了能上单测：这句话此前把「设备不给效果器」也说成
 * 「播放器尚未启动」，在真车机上误导性极强 —— 用户明明在放歌，
 * 却被指去点播放键。两种原因必须说成两件事。
 */
internal fun eqUnavailableCopy(state: AudioFx.EqState): String =
    when (state.unavailable) {
        AudioFx.Unavailable.DEVICE_REJECTED ->
            "这台设备的音频系统没有向应用开放音效接口（车机上很常见）。\n" +
                "均衡器 / 低音 / 混响都建不起来，请改用车辆自带的音效设置。播放本身不受影响。"
        else ->
            "播放器还没准备好音频会话。\n开始播放任意一首歌曲后即可调节。"
    }
