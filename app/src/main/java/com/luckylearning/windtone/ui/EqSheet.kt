@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.luckylearning.windtone.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.EqPresets
import com.luckylearning.windtone.playback.EqTone
import com.luckylearning.windtone.playback.ReverbPresets
import com.luckylearning.windtone.playback.SoundSchemes
import com.luckylearning.windtone.playback.ReplayGainMode
import com.luckylearning.windtone.playback.StereoBalance
import com.luckylearning.windtone.playback.StereoBalanceFx
import com.luckylearning.windtone.playback.softEq.SoftEqPath
import com.luckylearning.windtone.playback.TrackVolume
import com.luckylearning.windtone.playback.UserEqPresets
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 「均衡器与音效」弹层（效果图 ⑲）。
 *
 * 2026-10-17 从 `Sheets.kt` 拆出并重排：原先是「一组横向滚动的预设片 + 一长条滑块」，
 * 预设只能横向翻着找、四组可调项挤在一起看不出层次。现在按**决策顺序**分四段：
 *  ① 方案——一键应用一组完整听感（[SoundSchemes]：曲线 + 低音 + 环绕 + 响度 + 混响打包），
 *     选的是「想要什么感觉」，不想调参数的用户到这一层就能走（v1.1.52）；
 *  ② 预设——均衡器曲线（[EqPresets]）和用户自己存的「我的」（[UserEqPresets]）；
 *  ③ 频段——先把当前曲线画出来（折线预览），再给频段滑块；
 *  ④ 增强音效——顶部低音 / 高音快捷键（车机大触控），下面才是低音 / 环绕 / 响度 / 混响；
 *  ⑤ 播放过渡——淡入淡出、曲间音量平衡与 ReplayGain 曲目/专辑模式（[TrackVolume]），
 *     不依赖效果器可用性，所以放在 `available` 判断之外（v1.1.52；N6 模式切换）。
 *
 * 另有「按住对比原声」按钮：按住期间全部效果器临时旁路（[AudioFx.setBypassed]），
 * 供 A/B 对比 —— 音效好不好，当下听一耳朵比看曲线直观得多。
 *
 * 四处口径沿用既有约定，不要改：
 *  - 预设曲线全部内置（不用平台预设——各 ROM 名字不一致且无法本地化）；
 *  - 手动拖频段滑块 / 任一增强滑块 = 离开预设与方案，进入「自定义」（id 清空）；
 *  - 标题行的 ↺ 只在偏离出厂状态时出现，点它 = 关掉均衡器 + 频段全平 + 全部增强归零
 *    + 方案与进车快照一并回厂；
 *  - 播放过渡（淡入淡出 / 音量平衡 / ReplayGain 模式）不受「设备不支持」影响（走的是播放器音量，不是音频效果器）。
 */
@Composable
fun EqSheet(onClose: () -> Unit) {
    val state by AudioFx.state.collectAsState()
    val trackVolume by TrackVolume.state.collectAsState()
    val stereoBalance by StereoBalanceFx.state.collectAsState()
    val softEq by SoftEqPath.state.collectAsState()
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

    AppSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
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
                Text(stringResource(R.string.po_eq_effects), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                    stringResource(R.string.eq_global_effects_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!state.available) {
                // 2026-10-18 真机反馈：车机上明明在放歌，这里却弹「播放器尚未启动」。
                // 原因是只有 available=false 一个信号，界面只能猜。现在按原因分开说
                // （文案见 [eqUnavailableRes]，单测锁住中英文）。
                Text(
                    stringResource(eqUnavailableRes(state)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                if (state.unavailableDetail.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.eq_unavailable_detail, state.unavailableDetail),
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
            // 车机上效果器建不出来时这两项照常能用，所以放在 available 判断之外。
            // 设备拒绝建效果器时把它们框出来，避免用户只看见「都建不起来」就关掉。
            // E4: soft-EQ candidate / confirm (Prefs.softEqUserConfirm via SoftEqPath).
            // Only when gate says candidate or soft-active — never pretend hard EQ still works.
            if (SoftEqCopy.shouldShowConfirmEntry(softEq.phase)) {
                SoftEqPathSection(softEq)
            }
            if (state.unavailable == AudioFx.Unavailable.DEVICE_REJECTED) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            stringResource(R.string.eq_still_available_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        PlaybackTransitionSection(trackVolume, stereoBalance)
                    }
                }
            } else {
                PlaybackTransitionSection(trackVolume, stereoBalance)
            }
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
    SectionLabel(stringResource(R.string.eq_section_scheme))
    Text(
        stringResource(R.string.eq_scheme_hint),
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
                label = {
                    Text(
                        if (s.forCar) s.name + stringResource(R.string.eq_scheme_car_suffix)
                        else s.name,
                    )
                },
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
                current?.name ?: stringResource(R.string.eq_scheme_none),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                current?.desc ?: stringResource(R.string.eq_scheme_none_desc),
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
        Text(
            if (pressed) stringResource(R.string.eq_ab_bypassed)
            else stringResource(R.string.eq_ab_compare),
        )
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
private fun PlaybackTransitionSection(
    tv: TrackVolume.State,
    balance: StereoBalanceFx.State,
) {
    SectionLabel(stringResource(R.string.eq_section_transition))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.eq_fade_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.eq_fade_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = tv.fadeEnabled, onCheckedChange = TrackVolume::setFadeEnabled)
    }
    if (tv.fadeEnabled) {
        FxSlider(
            title = stringResource(R.string.eq_fade_duration),
            valueText = stringResource(
                R.string.eq_fade_seconds,
                "%.1f".format(tv.fadeDurationMs / 1000f),
            ),
            value = tv.fadeDurationMs.toFloat(),
            range = 500f..6000f,
            onValueChange = { TrackVolume.setFadeDurationMs(it.toInt()) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.eq_volume_balance_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.eq_volume_balance_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = tv.loudnessBalance, onCheckedChange = TrackVolume::setLoudnessBalance)
    }
    if (tv.loudnessBalance) {
        Text(
            stringResource(R.string.eq_replaygain_mode_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            stringResource(R.string.eq_replaygain_mode_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            FilterChip(
                selected = tv.replayGainMode == ReplayGainMode.TRACK,
                onClick = { TrackVolume.setReplayGainMode(ReplayGainMode.TRACK) },
                label = { Text(stringResource(R.string.eq_replaygain_mode_track)) },
            )
            FilterChip(
                selected = tv.replayGainMode == ReplayGainMode.ALBUM,
                onClick = { TrackVolume.setReplayGainMode(ReplayGainMode.ALBUM) },
                label = { Text(stringResource(R.string.eq_replaygain_mode_album)) },
            )
        }
    }
    // #9 stereo balance
    FxSlider(
        title = stringResource(R.string.eq_stereo_balance_title),
        valueText = StereoBalance.valueText(balance.balance),
        value = balance.balance.toFloat(),
        range = StereoBalance.MIN.toFloat()..StereoBalance.MAX.toFloat(),
        onValueChange = { StereoBalanceFx.setBalance(it.toInt()) },
        hint = stringResource(R.string.eq_stereo_balance_hint),
    )
}

// ================================================================ ② 预设

/**
 * 预设选择：分组 tab + **自动换行**的预设片 + 选中项说明卡。
 *
 * 为什么从横向滚动换成换行网：一组 8 个片，横向滚动时最后两个永远在屏幕外，
 * 「有没有我想要的」这件事本身就得靠滑一下才知道；换行之后整组一屏看全。
 */
private enum class PresetTab { FEEL, GENRE, MINE }

private sealed class PresetEditor {
    data object Create : PresetEditor()
    data class Rename(val id: String, val initial: String) : PresetEditor()
    data class ConfirmDelete(val id: String, val name: String) : PresetEditor()
    data class ConfirmImport(val raw: String, val count: Int) : PresetEditor()
}

@Composable
private fun PresetSection(state: AudioFx.EqState) {
    val builtin = EqPresets.byId(state.presetId)
    val user = state.userPresets.firstOrNull { it.id == state.presetId }
    // 只决定打开时落在哪一组。之后拖滑块会清空 presetId，不能因此把 tab 拽走。
    var tab by remember {
        mutableStateOf(
            when {
                UserEqPresets.isUserId(state.presetId) -> PresetTab.MINE
                builtin?.group == EqPresets.Group.GENRE -> PresetTab.GENRE
                else -> PresetTab.FEEL
            },
        )
    }
    var editor by remember { mutableStateOf<PresetEditor?>(null) }
    var libraryFull by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = AudioFx.exportUserPresetsJson()
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        val msg = if (ok) {
            context.getString(R.string.eq_user_preset_export_ok, state.userPresets.size)
        } else {
            context.getString(R.string.eq_user_preset_export_fail)
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (raw == null) {
            Toast.makeText(context, context.getString(R.string.eq_user_preset_import_read_fail), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val parsed = UserEqPresets.fromExportJson(raw)
        if (parsed == null) {
            Toast.makeText(context, context.getString(R.string.eq_user_preset_import_bad), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        editor = PresetEditor.ConfirmImport(raw = raw, count = parsed.size)
    }

    SectionLabel(stringResource(R.string.eq_section_preset))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = tab == PresetTab.FEEL,
            onClick = { tab = PresetTab.FEEL },
            label = { Text(EqPresets.Group.FEEL.label) },
        )
        FilterChip(
            selected = tab == PresetTab.GENRE,
            onClick = { tab = PresetTab.GENRE },
            label = { Text(EqPresets.Group.GENRE.label) },
        )
        FilterChip(
            selected = tab == PresetTab.MINE,
            onClick = { tab = PresetTab.MINE },
            label = { Text(stringResource(R.string.eq_user_presets)) },
        )
    }
    Text(
        when (tab) {
            PresetTab.FEEL -> EqPresets.Group.FEEL.hint
            PresetTab.GENRE -> EqPresets.Group.GENRE.hint
            PresetTab.MINE -> stringResource(R.string.eq_user_preset_hint)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )

    if (tab == PresetTab.MINE) {
        if (state.userPresets.isEmpty()) {
            Text(
                stringResource(R.string.eq_user_preset_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                state.userPresets.forEach { p ->
                    FilterChip(
                        selected = state.presetId == p.id,
                        onClick = { AudioFx.applyUserPreset(p.id) },
                        label = { Text(p.name) },
                    )
                }
            }
        }
        if (libraryFull) {
            Text(
                stringResource(R.string.eq_user_preset_full),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            TextButton(
                onClick = {
                    if (state.userPresets.size >= UserEqPresets.MAX_PRESETS) {
                        libraryFull = true
                    } else {
                        libraryFull = false
                        editor = PresetEditor.Create
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.eq_user_preset_save)) }
            if (user != null) {
                TextButton(
                    onClick = { editor = PresetEditor.Rename(user.id, user.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.eq_user_preset_rename)) }
                TextButton(
                    onClick = { AudioFx.updateUserPreset(user.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.eq_user_preset_update)) }
                TextButton(
                    onClick = { editor = PresetEditor.ConfirmDelete(user.id, user.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.eq_user_preset_delete)) }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            TextButton(
                onClick = {
                    if (state.userPresets.isEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.eq_user_preset_export_empty),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        exportLauncher.launch("windtone-eq-presets.json")
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.eq_user_preset_export)) }
            TextButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.eq_user_preset_import)) }
        }
    } else {
        val group = if (tab == PresetTab.GENRE) EqPresets.Group.GENRE else EqPresets.Group.FEEL
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
                user?.name ?: builtin?.name ?: stringResource(R.string.eq_preset_custom),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                when {
                    user != null -> stringResource(R.string.eq_user_preset_applied)
                    builtin != null -> builtin.desc
                    else -> stringResource(R.string.eq_user_preset_custom_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    when (val current = editor) {
        null -> Unit
        is PresetEditor.ConfirmImport -> AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(stringResource(R.string.eq_user_preset_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.eq_user_preset_import_body,
                        current.count,
                        state.userPresets.size,
                        UserEqPresets.MAX_PRESETS,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val outcome = AudioFx.importUserPresetsFromJson(
                        current.raw,
                        UserEqPresets.ImportMode.MERGE,
                    )
                    editor = null
                    tab = PresetTab.MINE
                    val msg = if (outcome == null) {
                        context.getString(R.string.eq_user_preset_import_bad)
                    } else {
                        context.getString(
                            R.string.eq_user_preset_import_done,
                            outcome.added,
                            outcome.updated,
                            outcome.skipped,
                        )
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.eq_user_preset_import_merge)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val outcome = AudioFx.importUserPresetsFromJson(
                            current.raw,
                            UserEqPresets.ImportMode.REPLACE,
                        )
                        editor = null
                        tab = PresetTab.MINE
                        val msg = if (outcome == null) {
                            context.getString(R.string.eq_user_preset_import_bad)
                        } else {
                            context.getString(
                                R.string.eq_user_preset_import_done_replace,
                                outcome.added,
                                outcome.skipped,
                            )
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.eq_user_preset_import_replace)) }
                    TextButton(onClick = { editor = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
        is PresetEditor.ConfirmDelete -> AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(stringResource(R.string.eq_user_preset_delete)) },
            text = { Text(stringResource(R.string.eq_user_preset_delete_body, current.name)) },
            confirmButton = {
                TextButton(onClick = {
                    AudioFx.deleteUserPreset(current.id)
                    editor = null
                }) { Text(stringResource(R.string.eq_user_preset_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { editor = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
        is PresetEditor.Create, is PresetEditor.Rename -> PresetNameDialog(
            initial = if (current is PresetEditor.Rename) current.initial else "",
            onDismiss = { editor = null },
            onConfirm = { name ->
                val ok = when (current) {
                    is PresetEditor.Create -> AudioFx.saveUserPreset(name) != null
                    is PresetEditor.Rename -> AudioFx.renameUserPreset(current.id, name)
                    else -> false
                }
                if (ok) {
                    tab = PresetTab.MINE
                    editor = null
                }
            },
        )
    }
}

@Composable
private fun PresetNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_user_preset_name)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(UserEqPresets.MAX_NAME_LENGTH) },
                singleLine = true,
                label = { Text(stringResource(R.string.eq_user_preset_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = UserEqPresets.sanitizeName(name) != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ================================================================ ③ 频段与曲线

/** 频段：曲线预览在上（调之前先看一眼形状），频段滑块在下 */
@Composable
private fun CurveSection(state: AudioFx.EqState) {
    SectionLabel(stringResource(R.string.eq_section_bands))
    Text(
        stringResource(R.string.eq_bands_hint),
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
    SectionLabel(stringResource(R.string.eq_section_enhance))
    TonePads(state)
    val offLabel = stringResource(R.string.eq_off)
    FxSlider(
        title = stringResource(R.string.eq_bass_boost),
        valueText = if (state.bassStrength > 0) {
            stringResource(R.string.eq_percent, state.bassStrength / 10)
        } else {
            offLabel
        },
        value = state.bassStrength.toFloat(),
        range = 0f..state.bassMax.toFloat(),
        onValueChange = { AudioFx.setBassStrength(it.toInt()) },
    )
    FxSlider(
        title = stringResource(R.string.eq_virtualizer),
        valueText = if (state.virtualStrength > 0) {
            stringResource(R.string.eq_percent, state.virtualStrength / 10)
        } else {
            offLabel
        },
        value = state.virtualStrength.toFloat(),
        range = 0f..1000f,
        onValueChange = { AudioFx.setVirtualStrength(it.toInt()) },
    )
    FxSlider(
        title = stringResource(R.string.eq_loudness_enhance),
        valueText = if (state.loudnessGain > 0) {
            stringResource(R.string.eq_loudness_mb, state.loudnessGain)
        } else {
            offLabel
        },
        value = state.loudnessGain.toFloat(),
        range = 0f..state.loudnessMax.toFloat(),
        onValueChange = { AudioFx.setLoudnessGain(it.toInt()) },
        hint = stringResource(R.string.eq_loudness_hint),
    )

    // ---------------- 混响 ----------------
    // 档位由平台效果器给死、没有连续量，所以用一排片而不是滑块（与均衡器预设同一套交互）
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.eq_reverb), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            (reverbPreset ?: ReverbPresets.OFF).name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!state.reverbAvailable) {
        Text(
            stringResource(R.string.eq_reverb_unavailable),
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
 * 低音 / 高音快捷键。触控目标做大，给车机用。
 *
 * 低音和下面的「低音增强」滑块写同一个 [AudioFx.EqState.bassStrength]。
 * 高音把 4 kHz 及以上的频段收成搁架，频段滑块和曲线预览跟着变。
 */
@Composable
private fun TonePads(state: AudioFx.EqState) {
    val treble = EqTone.readTrebleMb(state.bandLevels, state.bandCenterHz)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        val down = stringResource(R.string.eq_tone_down)
        val up = stringResource(R.string.eq_tone_up)
        TonePad(
            title = stringResource(R.string.eq_tone_bass),
            valueText = if (state.bassStrength > 0) {
                stringResource(R.string.eq_percent, state.bassStrength / 10)
            } else {
                stringResource(R.string.eq_off)
            },
            downDescription = down,
            upDescription = up,
            onDown = {
                AudioFx.setBassStrength(EqTone.stepBass(state.bassStrength, -EqTone.BASS_STEP, state.bassMax))
            },
            onUp = {
                AudioFx.setBassStrength(EqTone.stepBass(state.bassStrength, EqTone.BASS_STEP, state.bassMax))
            },
            modifier = Modifier.weight(1f),
        )
        TonePad(
            title = stringResource(R.string.eq_tone_treble),
            valueText = formatGainDb(treble),
            downDescription = down,
            upDescription = up,
            onDown = {
                AudioFx.setTrebleGainMb(
                    EqTone.stepTreble(treble, -EqTone.TREBLE_STEP_MB, state.bandMinMb, state.bandMaxMb),
                )
            },
            onUp = {
                AudioFx.setTrebleGainMb(
                    EqTone.stepTreble(treble, EqTone.TREBLE_STEP_MB, state.bandMinMb, state.bandMaxMb),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(R.string.eq_tone_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TonePad(
    title: String,
    valueText: String,
    downDescription: String,
    upDescription: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onDown,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .semantics { contentDescription = "$title $downDescription" },
            ) { Text("−", fontSize = 28.sp) }
            FilledTonalButton(
                onClick = onUp,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .semantics { contentDescription = "$title $upDescription" },
            ) { Text("+", fontSize = 28.sp) }
        }
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


@Composable
private fun SoftEqPathSection(soft: SoftEqPath.State) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.soft_eq_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(SoftEqCopy.statusRes(soft.phase)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.soft_eq_confirm_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.soft_eq_confirm_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Switch(
                    checked = soft.userConfirmSoft,
                    onCheckedChange = SoftEqPath::setUserConfirmSoft,
                )
            }
        }
    }
}

/**
 * 音效不可用时该用哪句文案（v1.1.22，车机不可用态在此基础上点明淡入淡出和音量平衡）。
 *
 * 返回资源 id 而不是写死中文：中英文都在 `values` / `values-zh`，单测按语言把句子锁住。
 * 设备拒绝和「还没会话」必须是两句，不能把正在放歌的车机说成「尚未启动」。
 */
internal fun eqUnavailableRes(state: AudioFx.EqState): Int =
    when (state.unavailable) {
        AudioFx.Unavailable.DEVICE_REJECTED -> R.string.eq_unavailable_device
        else -> R.string.eq_unavailable_pending
    }
