@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.data.OverlayPresetParams
import com.luckylearning.windtone.data.OverlayPresetTracker
import com.luckylearning.windtone.data.OVERLAY_FACTORY_PARAMS
import com.luckylearning.windtone.data.currentOverlayPreset
import com.luckylearning.windtone.data.overlayPresetLabel
import com.luckylearning.windtone.state.QueueEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.playback.Pitch
import com.luckylearning.windtone.playback.SleepTimer
import com.luckylearning.windtone.ui.theme.glassContainerColor
import kotlin.math.roundToInt

/**
 * 播放相关的底部弹层：播放队列、睡眠定时、播放设置（速度 / 变调 / 歌词缩放与偏移）。
 *
 * 从 Sheets.kt 按「页面」拆出（v1.1.37）：原文件 1015 行混着四类互不相干的面板，
 * 改一处要整份读。拆分后按域分四个文件，本文件只留播放域。
 */

// ================================================================ 播放队列

@Composable
fun QueueSheet(
    queue: List<QueueEntry>,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveAsPlaylist: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(AppIcons.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "播放队列 · ${queue.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // 队列是"临时排序"，用户攒出一份满意的队列后往往想存下来长期用
                TextButton(
                    onClick = onSaveAsPlaylist,
                    enabled = queue.isNotEmpty(),
                ) { Text("存为歌单") }
            }
            Spacer(Modifier.height(8.dp))
            // 固定 420dp 在矮屏（平板横屏 / 车机）会连标题一起越过屏高 70%，
            // 改为「上限 = 屏高 60%」，队列短时按内容收缩。
            LazyColumn(Modifier.heightIn(max = sheetMaxHeight(0.60f))) {
                itemsIndexed(queue, key = { _, e -> "${e.index}-${e.mediaId}" }) { _, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onJump(entry.index) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (entry.isCurrent) "▶" else "${entry.index + 1}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (entry.isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                entry.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onMove(entry.index, entry.index - 1) }, enabled = entry.index > 0) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp, contentDescription = "上移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onMove(entry.index, entry.index + 1) }, enabled = entry.index < queue.size - 1) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown, contentDescription = "下移",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemove(entry.index) }) {
                            Icon(
                                Icons.Filled.Close, contentDescription = "移除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ================================================================ 睡眠定时

@Composable
fun SleepSheet(
    onClose: () -> Unit,
    onSet: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onEndOfAlbum: () -> Unit,
    onCancel: () -> Unit,
) {
    val remaining by SleepTimer.remainingMs.collectAsState()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("睡眠定时", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (remaining > 0) {
                val mins = remaining / 60000
                val secs = (remaining % 60000) / 1000
                Text(
                    "剩余 %d:%02d 后暂停".format(mins, secs),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            listOf(15, 30, 60, 90).forEach { m ->
                Text(
                    "$m 分钟后暂停",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSet(m) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                "播完当前曲暂停",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onEndOfSong() }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            // 三期规划里承诺过三种模式，此前只落地了两种（倒计时 / 播完当前曲）。
            // 这一档适合「整张专辑听完」的场景：专辑里的曲目在队列里连排，等最后
            // 一首放完才停；没有专辑信息时按「播完当前曲」处理（见 PlaybackEngine）。
            Text(
                "播完当前专辑暂停",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onEndOfAlbum() }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (remaining > 0) {
                Text(
                    "取消定时",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onCancel() }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ================================================================ 播放设置（分组抽屉）

/**
 * 播放页的「更多」入口。
 *
 * 原先是一个塞了 13 个条目的下拉菜单，找一项要扫一整个列表。
 * 这里按「音效 / 睡眠 / 速度 / 歌词」分组，需要连续调节的项目直接内嵌滑块。
 */
@Composable
fun PlayerOptionsSheet(
    speed: Float,
    /** 变调：半音（-12..+12，0 = 原调） */
    pitchSemitones: Int,
    lyricScale: Float,
    lyricOffsetMs: Int,
    sleepRemainingMs: Long,
    /** 车机：场景芯片用更大触控目标 */
    carMode: Boolean = false,
    onSpeed: (Float) -> Unit,
    onPitch: (Int) -> Unit,
    onLyricScale: (Float) -> Unit,
    onLyricOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenSleep: () -> Unit,
    onExportLyrics: () -> Unit,
    onAudioInfo: () -> Unit,
    /** 悬浮歌词开关（右侧 ✓ 表示已开启） */
    lyricOverlay: Boolean,
    /** 是否已拿到「显示悬浮窗」授权；未授权时点击会去系统授权页 */
    canDrawOverlay: Boolean,
    onToggleLyricOverlay: () -> Unit,
    /**
     * 当前悬浮歌词「样式预设」展示名（v1.1.78，反推匹配得到：
     * 预设名 / 「默认预设」/「自定义」）。空串时退回「默认预设」。
     */
    overlayPresetLabel: String = "",
    /** 打开「悬浮窗样式」速选面板（FR-02） */
    onOpenOverlayStyle: () -> Unit = {},
    /** C2 A-B 循环：当前 A/B（毫秒，null=未设） */
    abLoopAMs: Long? = null,
    abLoopBMs: Long? = null,
    abLoopFromLyric: Boolean = false,
    onMarkAbLoopA: () -> Unit = {},
    onMarkAbLoopB: () -> Unit = {},
    onClearAbLoop: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    // 2026-10-30：速度与变调默认收起。与设置页同一条理由 —— 两块滑块「一碰就改值」，
    // 而这一层的入口是「播放设置」，多数人来这里调的是音效 / 睡眠定时，不是变速。
    var showSpeedPitch by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
        Column(
            Modifier
                .fillMaxWidth()
                // 2026-10-22 用户要求：最高屏高 70%，内容比这长就内部滚动 ——
                // 平板横屏上这层会被顶到底部控制键底下，最后一项「导出歌词」看不见。
                .heightIn(max = sheetMaxHeight())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("播放设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            SectionLabel("当前歌曲")
            OptionRow(
                icon = AppIcons.Info,
                title = "音频信息",
                subtitle = "格式 / 编码 / 采样率 / 文件路径",
                onClick = onAudioInfo,
            )

            SectionLabel("音效")
            OptionRow(
                icon = AppIcons.Tune,
                title = "均衡器与音效",
                subtitle = "预设 / 频段曲线 / 低音增强 / 环绕声 / 响度增强 / 混响",
                onClick = onOpenEq,
            )

            SectionLabel(stringResource(R.string.scene_section))
            SceneChipsRow(large = carMode, showHint = true)
            Spacer(Modifier.height(8.dp))

            SectionLabel("睡眠定时")
            OptionRow(
                icon = AppIcons.Bedtime,
                title = "定时停止播放",
                subtitle = if (sleepRemainingMs > 0) {
                    "剩余 %d:%02d".format(sleepRemainingMs / 60000, (sleepRemainingMs % 60000) / 1000)
                } else {
                    "未开启"
                },
                onClick = onOpenSleep,
            )

            // v1.1.6：变调并入本组。两者在播放器里是同一个 `PlaybackParameters`，
            // 界面上分开摆反而会让人以为它们互不影响（实际会互相覆盖，见 PlaybackEngine）。
            CollapsibleSectionTitle(
                title = stringResource(R.string.speed_and_pitch),
                summary = stringResource(
                    R.string.speed_and_pitch_summary,
                    "%.2f".format(speed),
                    Pitch.labelOf(pitchSemitones),
                ),
                expanded = showSpeedPitch,
                onToggle = { showSpeedPitch = !showSpeedPitch },
                compact = true,
            )
            if (showSpeedPitch) {
                SpeedControl(speed = speed, onSpeed = onSpeed)
                PitchControl(semitones = pitchSemitones, onPitch = onPitch)
            }

            SectionLabel("A-B 循环")
            Text(
                text = when {
                    abLoopFromLyric && abLoopAMs != null && abLoopBMs != null ->
                        "逐句循环中 %s–%s".format(
                            formatAbClock(abLoopAMs),
                            formatAbClock(abLoopBMs),
                        )
                    abLoopAMs != null && abLoopBMs != null ->
                        "区间 %s → %s（到 B 回 A；与变速兼容）".format(
                            formatAbClock(abLoopAMs),
                            formatAbClock(abLoopBMs),
                        )
                    abLoopAMs != null ->
                        "已设 A %s，再设 B 开始循环".format(formatAbClock(abLoopAMs))
                    else -> "练唱：先设 A，再设 B；切歌自动清除"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onMarkAbLoopA, modifier = Modifier.weight(1f)) {
                    Text(if (abLoopAMs != null) "重设 A" else "设 A")
                }
                OutlinedButton(onClick = onMarkAbLoopB, modifier = Modifier.weight(1f)) {
                    Text(if (abLoopBMs != null) "重设 B" else "设 B")
                }
                if (abLoopAMs != null || abLoopBMs != null) {
                    TextButton(onClick = onClearAbLoop) {
                        Text(if (abLoopFromLyric) "退出逐句" else "清除")
                    }
                }
            }

            SectionLabel("歌词")
            // 悬浮歌词放在「歌词」分组第一位：它是这首歌「往哪儿显示」最常用的一个选择，
            // 比导出歌词高频得多。右侧 ✓ 即"已开启"，与 OptionRow 的规范一致（不带箭头）。
            OptionRow(
                icon = AppIcons.Lyrics,
                title = "桌面悬浮歌词",
                subtitle = when {
                    lyricOverlay -> "已开启 · 当前：${overlayPresetLabel.ifBlank { "默认预设" }}"
                    !canDrawOverlay -> "需要系统「显示悬浮窗」授权，点击后自动跳转"
                    else -> "在桌面最上层显示当前歌词行（App 内自动隐藏）"
                },
                selected = lyricOverlay,
                onClick = onToggleLyricOverlay,
            )
            // v1.1.78 歌词菜单升级（FR-01，D2 拍板落点=本弹层）：开关副标题报当前预设名，
            // 换样式走下一行的速选面板——播放中完成「开关 / 换样式」不用退出播放页。
            OptionRow(
                icon = AppIcons.Palette,
                title = "悬浮窗样式",
                subtitle = "${overlayPresetLabel.ifBlank { "默认预设" }} · 内置外观方案一键套用",
                onClick = onOpenOverlayStyle,
            )
            LyricScaleControl(scale = lyricScale, onScale = onLyricScale)
            LyricOffsetControl(
                offsetMs = lyricOffsetMs,
                onOffset = onLyricOffset,
                onReset = onResetOffset,
            )
            OptionRow(
                icon = AppIcons.Lyrics,
                title = "导出歌词为 LRC",
                subtitle = "保存到本地文件",
                onClick = onExportLyrics,
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * 弹层里的小节标题（`labelMedium` + primary 色）。
 *
 * `internal` 而不是 `private`：均衡器弹层拆到 `EqSheet.kt` 之后也要用它
 * （同包可见，不必 import）—— 与 [ResetButton] 落在 Common.kt 是同一条理由，
 * 别在第二个文件里再复制一份。
 */

/** 速度：滑块 + 常用档位快捷片；拖动过程只更新本地值，松手才落盘 */
@Composable
private fun SpeedControl(speed: Float, onSpeed: (Float) -> Unit) {
    var local by remember(speed) { mutableFloatStateOf(speed) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("速度", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "%.2fx".format(local),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onSpeed(local) },
            valueRange = 0.5f..2.0f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                FilterChip(
                    selected = kotlin.math.abs(sp - speed) < 0.01f,
                    onClick = { onSpeed(sp) },
                    label = { Text("${sp}x", fontSize = 12.sp) },
                )
            }
        }
    }
}

/**
 * 变调：滑块 + 常用档位快捷片，单位是**半音**而不是倍率。
 *
 * 为什么不用倍率：`0.89x` 没人看得懂，而「降两个半音」是唱歌的人一看就明白的。
 * 换算在 `playback/Pitch` 里，界面只认半音。
 *
 * 拖动过程只改本地值、松手才落盘 —— 与速度一致：这个参数会触发跨进程调用，
 * 逐帧写回会在拖动时打满播放服务的消息队列。
 */

@Composable
private fun PitchControl(semitones: Int, onPitch: (Int) -> Unit) {
    var local by remember(semitones) { mutableFloatStateOf(semitones.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("变调", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                Pitch.labelOf(local.roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onPitch(local.roundToInt()) },
            valueRange = Pitch.MIN_SEMITONES.toFloat()..Pitch.MAX_SEMITONES.toFloat(),
            // 每个半音一档：24 段之间 23 个刻度，滑块自动吸附到整半音
            steps = Pitch.MAX_SEMITONES - Pitch.MIN_SEMITONES - 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-12, -6, 0, 6, 12).forEach { n ->
                FilterChip(
                    selected = n == semitones,
                    onClick = { onPitch(n) },
                    label = {
                        Text(
                            when {
                                n == 0 -> "原调"
                                n > 0 -> "+$n"
                                else -> "$n"
                            },
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LyricScaleControl(scale: Float, onScale: (Float) -> Unit) {
    var local by remember(scale) { mutableFloatStateOf(scale) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("歌词字号", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "%.0f%%".format(local * 100),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onScale(local) },
            valueRange = 0.7f..1.6f,
        )
    }
}

/** 歌词时间轴偏移：逐曲记忆，负值表示歌词延后 */

@Composable
private fun LyricOffsetControl(offsetMs: Int, onOffset: (Int) -> Unit, onReset: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("歌词时间轴偏移", style = MaterialTheme.typography.bodyLarge)
            Text(
                "当前 ${offsetMs}ms（仅对本曲生效）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onOffset(-500) }) { Text("-0.5s") }
        TextButton(onClick = { onOffset(500) }) { Text("+0.5s") }
        TextButton(onClick = onReset, enabled = offsetMs != 0) { Text("重置") }
    }
}

// ================================================================ 悬浮窗样式速选面板（v1.1.78）

/**
 * 「悬浮窗样式」速选面板（FR-02 / FR-03，设计文档 §6.1 / §7）。
 *
 * 车机三套（carOnly）只在车载模式出现（D3）；点任意预设**立即批量写盘并生效**
 * （FR-03，服务每帧读 Prefs，无需重启）。面板内不做二次确认——样式可以随时
 * 一键换回，误触成本为零。
 *
 * 差异参数卡：chips 下方常驻一张卡，展示选中预设的一句话定位 + 关键参数摘要
 * （[OverlayPreset.summary]），让用户在点下去之前就知道这一套长什么样。
 *
 * 「已修改 / 恢复预设原值」（FR-04）：反推匹配失败（自定义）且本会话套用过某套
 * 预设时出现；恢复 = 把那套预设的快照原样重写一遍（R2）。
 */
@Composable
fun OverlayStyleSheet(
    carMode: Boolean,
    /** 调用方的当前悬浮歌词参数（用于反推当前预设与「已修改」判定） */
    currentParams: OverlayPresetParams,
    /** 套用预设（批量写盘 + 生效） */
    onApply: (OverlayPreset) -> Unit,
    /** 「更多设置…」：跳完整设置页（设置 → 歌词 → 桌面悬浮歌词） */
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = currentOverlayPreset(currentParams)
    // 差异参数卡高亮哪套：优先当前命中，其次本会话最近套用（改乱了想看原貌时用）
    val highlighted = current
        ?: OverlayPresetTracker.lastAppliedId?.let { OverlayPreset.byId(it) }
        ?: OverlayPreset.byId(OverlayPreset.DEFAULT_ID)!!
    val modified = current == null &&
        currentParams != OVERLAY_FACTORY_PARAMS &&
        OverlayPresetTracker.lastAppliedId != null
    // chips 选中态跟手：点下去即套用（FR-03），本地立刻高亮，不等状态回流
    var selectedId by remember { mutableStateOf(highlighted.id) }
    if (OverlayPreset.byId(selectedId) == null) selectedId = highlighted.id

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text("悬浮窗样式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "点按即套用，悬浮窗立即换样式；可到设置页微调细节",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            val pool = if (carMode) OverlayPreset.ALL else OverlayPreset.PHONE
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pool.forEach { preset ->
                    FilterChip(
                        selected = preset.id == selectedId,
                        onClick = {
                            selectedId = preset.id
                            onApply(preset)
                        },
                        label = { Text(preset.displayName, fontSize = 13.sp) },
                    )
                }
            }
            // 差异参数卡：当前高亮预设的定位 + 关键参数，点之前心里有数
            val shown = OverlayPreset.byId(selectedId) ?: highlighted
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(12.dp),
            ) {
                Text(shown.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    shown.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    shown.summary(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // FR-04：参数被改乱（自定义）且本会话套用过 → 给一键还原
            if (modified) {
                OverlayPresetTracker.lastAppliedId?.let { OverlayPreset.byId(it) }?.let { last ->
                    Text(
                        "参数已被手动修改。恢复「${last.displayName}」原样",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedId = last.id
                                onApply(last)
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
            Text(
                "更多设置…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onDismiss()
                        onOpenSettings()
                    }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}


private fun formatAbClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
