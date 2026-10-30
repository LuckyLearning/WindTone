package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import android.content.Context
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.ThemeMode
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
                title = stringResource(R.string.appearance),
                subtitle = stringResource(R.string.theme_colors_car_mode),
                icon = AppIcons.Palette,
                onClick = { onNavigate(SettingsPage.APPEARANCE) },
            )
            SettingsPage.PLAYBACK -> CategoryRow(
                title = stringResource(R.string.playback),
                subtitle = stringResource(R.string.speed_x_pitch_skip_silence, "%.2f".format(player.speed), Pitch.labelOf(player.pitchSemitones)),
                icon = Icons.Filled.PlayArrow,
                onClick = { onNavigate(SettingsPage.PLAYBACK) },
            )
            SettingsPage.LYRICS -> CategoryRow(
                title = stringResource(R.string.lyrics),
                subtitle = stringResource(R.string.text_size_x_bilingual_lyrics, "%.1f".format(player.lyricScale)),
                icon = AppIcons.LyricsBubble,
                onClick = { onNavigate(SettingsPage.LYRICS) },
            )
            // 2026-10-30：音乐库 → 曲库与存储（维护动作从关于页搬回），摘要跟着换
            SettingsPage.LIBRARY -> CategoryRow(
                title = stringResource(R.string.library_and_storage),
                subtitle = if (minSongDurationSec > 0) {
                    stringResource(R.string.stats_cleanup_filters_under, minSongDurationSec)
                } else {
                    stringResource(R.string.stats_cleanup_short_audio)
                },
                icon = AppIcons.LibraryMusic,
                onClick = { onNavigate(SettingsPage.LIBRARY) },
            )
            // v1.1.80：更新从独立分类卡降为「关于与诊断」里的入口行
            // 2026-10-30：关于与诊断 → 关于与更新（统计与维护已搬去曲库，诊断降为子页）
            SettingsPage.ABOUT -> CategoryRow(
                title = stringResource(R.string.about_updates),
                subtitle = stringResource(R.string.version_check_for_updates_privacy),
                icon = AppIcons.Info,
                onClick = { onNavigate(SettingsPage.ABOUT) },
            )
            // v1.1.80：实验室功能 —— 实验性入口先放这里，确认稳定后再移出去
            SettingsPage.LAB -> CategoryRow(
                title = stringResource(R.string.set_lab_title),
                subtitle = stringResource(R.string.online_playback_reorder_menu_experimental),
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
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    dynamicTheme: Boolean,
    onDynamicTheme: (Boolean) -> Unit,
    onCarMode: (Boolean) -> Unit,
) {
    // 车载模式的长说明从副标题搬进弹窗，副标题只留一行摘要
    var showCarModeInfo by remember { mutableStateOf(false) }


    // ------------------------------------------------ 主题
    SectionTitle(stringResource(R.string.theme))

    // 明暗模式（v1.2.4）：跟随系统 / 浅色 / 深色。
    // 顺序与 `ThemeMode` 枚举一致，选中态直接用 ordinal 比 —— 中间不要插排序逻辑，
    // 否则「点浅色却选中深色」这种错位只能靠肉眼发现。
    val modes = ThemeMode.entries
    ChoiceRow(
        title = stringResource(R.string.theme_mode),
        subtitle = stringResource(R.string.theme_mode_desc),
        options = modes.map { it.displayName() },
        selectedIndex = modes.indexOf(themeMode),
        onSelect = { onThemeMode(modes[it]) },
        onReset = resetAction(themeMode != SettingsDefaults.THEME_MODE) {
            onThemeMode(SettingsDefaults.THEME_MODE)
        },
    )

    SwitchRow(
        title = stringResource(R.string.theme_follows_cover_colors),
        subtitle = stringResource(R.string.extracts_the_dominant_color_from),
        checked = dynamicTheme,
        onCheckedChange = onDynamicTheme,
        onReset = resetAction(dynamicTheme != SettingsDefaults.DYNAMIC_THEME) {
            onDynamicTheme(SettingsDefaults.DYNAMIC_THEME)
        },
    )

    // ------------------------------------------------ 车载
    SectionTitle(stringResource(R.string.car))
    // 副标题原文 180 字，一条设置项就占掉大半屏；详细说明移到标题旁的「?」弹窗
    SwitchRow(
        title = stringResource(R.string.car_mode),
        subtitle = stringResource(R.string.opens_on_the_player_large),
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
            title = { Text(stringResource(R.string.car_mode)) },
            text = {
                Text(
                    stringResource(R.string.opens_the_player_directly_and),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { showCarModeInfo = false }) { Text(stringResource(R.string.got_it)) }
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
    onGaplessPlayback: (Boolean) -> Unit = {},
    onAutoResume: (Boolean) -> Unit,
    /** 开机后台起播（v1.1.24） */
    onAutoPlayOnBoot: (Boolean) -> Unit,
) {
    var showEq by remember { mutableStateOf(false) }


    // ------------------------------------------------ 速度与变调
    // 两者在播放器里是同一个 `PlaybackParameters`（见 PlaybackEngine
    // 的 applyPlaybackParams），界面上也放在同一组里。
    //
    // 2026-10-30 用户要求「默认不要展开，用的很少，一不小心就点到了」：
    // 两个滑块原本常驻在页面最上方，滚动时手指一蹭就改值，而改完不会立刻听出区别
    // （要等下首歌/下次播放才明显）。改成标题行折叠：摘要显示「1.00x · 原调」，
    // 点开才出现滑块。收起不持久化 —— 每次进页面都是收起的，正是要压掉误触面。
    var showSpeedPitch by remember { mutableStateOf(false) }
    CollapsibleSectionTitle(
        title = stringResource(R.string.speed_and_pitch),
        summary = stringResource(
            R.string.speed_and_pitch_summary,
            "%.2f".format(player.speed),
            Pitch.labelOf(player.pitchSemitones),
        ),
        expanded = showSpeedPitch,
        onToggle = { showSpeedPitch = !showSpeedPitch },
    )
    if (showSpeedPitch) {
        SliderRow(
            title = stringResource(R.string.default_speed_x, "%.2f".format(player.speed)),
            value = player.speed,
            range = 0.75f..2.0f,
            onValueChange = onSpeed,
            onReset = resetAction(abs(player.speed - SettingsDefaults.SPEED) > 0.001f) {
                onSpeed(SettingsDefaults.SPEED)
            },
        )
        SliderRow(
            title = stringResource(R.string.pitch, Pitch.labelOf(player.pitchSemitones)),
            subtitle = stringResource(R.string.pitch_changes_do_not_affect),
            value = player.pitchSemitones.toFloat(),
            range = Pitch.MIN_SEMITONES.toFloat()..Pitch.MAX_SEMITONES.toFloat(),
            onValueChange = { onPitch(it.roundToInt()) },
            onReset = resetAction(!Pitch.isDefault(player.pitchSemitones)) {
                onPitch(SettingsDefaults.PITCH_SEMITONES)
            },
        )
    }

    // ------------------------------------------------ 播放行为
    SectionTitle(stringResource(R.string.playback_behavior))
    // 放在「跳过静音」前面：只退过一次界面就回来的人找的是这一条，
    // 而它此前只能靠「恢复队列但停在暂停态」隐式体现，界面上没有名字。
    SwitchRow(
        title = stringResource(R.string.resume_last_playback_on_launch),
        subtitle = stringResource(R.string.continues_from_the_last_position),
        checked = player.autoResume,
        onCheckedChange = onAutoResume,
        onReset = resetAction(player.autoResume != SettingsDefaults.AUTO_RESUME) {
            onAutoResume(SettingsDefaults.AUTO_RESUME)
        },
    )
    // 开机自启（v1.1.24）：车机上「通电就播」但不弹界面。
    // 放在这里而不是「外观 → 车载」，因为它是播放行为，跟车载模式是否打开无关。
    SwitchRow(
        title = stringResource(R.string.play_in_background_after_boot),
        subtitle = if (player.autoPlayOnBoot) {
            stringResource(R.string.after_boot_restores_the_last)
        } else {
            stringResource(R.string.when_off_boot_does_nothing)
        },
        checked = player.autoPlayOnBoot,
        onCheckedChange = onAutoPlayOnBoot,
        onReset = resetAction(player.autoPlayOnBoot != SettingsDefaults.AUTO_PLAY_ON_BOOT) {
            onAutoPlayOnBoot(SettingsDefaults.AUTO_PLAY_ON_BOOT)
        },
    )
    SwitchRow(
        title = stringResource(R.string.skip_silence),
        subtitle = stringResource(R.string.skips_leading_silence_in_recordings),
        checked = player.skipSilence,
        onCheckedChange = onSkipSilence,
        onReset = resetAction(player.skipSilence != SettingsDefaults.SKIP_SILENCE) {
            onSkipSilence(SettingsDefaults.SKIP_SILENCE)
        },
    )
    SwitchRow(
        title = "无缝播放",
        subtitle = if (player.gaplessPlayback) {
            "已开：单播放器连续队列；分轨 FLAC/专辑靠 encoder delay/padding 无断点（C1）"
        } else {
            "已关（仅偏好声明；内核仍是单 ExoPlayer，不插入静音）"
        },
        checked = player.gaplessPlayback,
        onCheckedChange = onGaplessPlayback,
        onReset = resetAction(player.gaplessPlayback != SettingsDefaults.GAPLESS_PLAYBACK) {
            onGaplessPlayback(SettingsDefaults.GAPLESS_PLAYBACK)
        },
    )
    ActionRow(
        title = stringResource(R.string.equalizer_and_effects),
        // 「（需先开始播放）」太绝对：有些车机即使正在播放也建不出效果器，
        // 那句话会把人引去反复点播放（2026-10-18 真机反馈）。改成说清依赖什么。
        subtitle = stringResource(R.string.scheme_presets_band_curve_bass),
        onClick = { showEq = true },
        chevron = true,
    )


    if (showEq) EqSheet(onClose = { showEq = false })
}



// ================================================================ 子页：曲库与存储

/**
 * 「曲库与存储」（2026-10-30，由「音乐库」改名并扩容）。
 *
 * 改名的理由：原来这一页只有两行（格式与存储 / 短音频过滤），撑不起一张分类卡；
 * 而**同属曲库的维护动作**（清理失效 / 恢复移除 / 查重 / 清封面缓存）却挂在
 * 「关于与诊断」里 —— 曲库的「管」被劈成两半。
 *
 * ⚠️ **本函数只做组合**：每个分区是 `SettingsLibrarySections.kt` 里一个自包含的
 * composable（自带标题、行与弹窗），这里只按顺序调用。所以
 * **加功能 = 加一个 Section + 这里加一行**，**调顺序 = 这里挪一行**，
 * 不用在几百行里找边界 —— 用户 2026-10-30 明确要求的口径。
 */
@Composable
internal fun SettingsLibraryPage(
    minSongDurationSec: Int,
    onMinSongDurationSec: (Int) -> Unit,
    onRestoreExcluded: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenStats: () -> Unit,
    library: LibraryUiState,
    maintenance: DataMaintenance.Report?,
    onRefreshMaintenance: () -> Unit,
    onCleanupMissingSongs: () -> Unit,
    onClearCoverCache: () -> Unit,
    onResetUserData: () -> Unit,
    onResetLibrary: () -> Unit,
    onClearStats: () -> Unit,
) {
    // 分区顺序表（自上而下）：
    //   ① 统计 —— 播放统计 / 曲库统计，两个入口并排
    //   ② 本地曲库 —— 短音频过滤
    //   ③ 数据与存储 —— 实时占用 + 四条维护动作
    //   ④ 危险操作 —— 三个不可撤销的清空，固定页尾
    LibraryStatsSection(
        songs = library.songs,
        playStats = library.playStats,
        onOpenPlaybackStats = onOpenReport,
        onOpenLibraryStats = onOpenStats,
    )
    LocalLibrarySection(
        minSongDurationSec = minSongDurationSec,
        onMinSongDurationSec = onMinSongDurationSec,
    )
    DataStorageSection(
        maintenance = maintenance,
        onRefreshMaintenance = onRefreshMaintenance,
        onCleanupMissingSongs = onCleanupMissingSongs,
        onRestoreExcluded = onRestoreExcluded,
        onOpenDuplicates = onOpenDuplicates,
        onClearCoverCache = onClearCoverCache,
    )
    LibraryDangerZoneSection(
        onClearStats = onClearStats,
        onResetUserData = onResetUserData,
        onResetLibrary = onResetLibrary,
    )
}
