package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.SourceMode
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunDrive
import com.luckylearning.windtone.data.aliyun.AliyunPlaybackWindow
import com.luckylearning.windtone.data.aliyun.AliyunTrackRecord
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.buildListeningReport
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.Quality
import com.luckylearning.windtone.playback.OnlineMediaCache
import com.luckylearning.windtone.state.ReportUiState
import com.luckylearning.windtone.state.TopSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsOnlinePage(
    onOpenPlaylists: () -> Unit,
    onOpenReport: () -> Unit,
) {
    val context = LocalContext.current
    var limit by remember { mutableLongStateOf(OnlineMediaCache.limitBytes(context)) }
    var used by remember { mutableLongStateOf(OnlineMediaCache.sizeBytes(context)) }
    var pickingLimit by remember { mutableStateOf(false) }
    val prefs = remember { Prefs(context) }
    var prefetch by remember { mutableStateOf(prefs.onlineCachePrefetch) }
    var parallel by remember { mutableStateOf(prefs.onlineParallelDownload) }

    // 音源模式：一处状态驱动抽屉菜单与内容页。默认 LOCAL（SettingsDefaults.SOURCE_MODE）。
    SectionTitle(stringResource(R.string.source_mode_title))
    var mode by remember { mutableStateOf(prefs.sourceMode) }
    var modeExpanded by remember { mutableStateOf(false) }
    PickerField(
        label = stringResource(R.string.source_mode_subtitle),
        value = stringResource(mode.labelRes()),
        expanded = modeExpanded,
        onExpandChange = { modeExpanded = it },
        options = com.luckylearning.windtone.data.SourceMode.entries,
        optionLabel = { stringResource(it.labelRes()) },
        onSelect = {
            mode = it
            prefs.sourceMode = it
            modeExpanded = false
        },
    )
    if (mode != com.luckylearning.windtone.data.SourceMode.LOCAL) {
        TextButton(onClick = {
            mode = com.luckylearning.windtone.data.SourceMode.LOCAL
            prefs.sourceMode = com.luckylearning.windtone.data.SourceMode.LOCAL
        }) { Text(stringResource(R.string.default_label)) }
    }

    // 在线音质：只决定取址的**起点档位**，取不到仍会逐级下降，所以选高档位不会「播不了」。
    // 只有洛雪音源才走脚本换直链、才有档位可挑；本地与云盘显示这一项只会让人以为有作用。
    // remember 必须在条件外：条件里调 remember 会在模式切换时打乱组合顺序。
    var quality by remember { mutableStateOf(prefs.onlineQuality) }
    var qualityExpanded by remember { mutableStateOf(false) }
    if (mode == com.luckylearning.windtone.data.SourceMode.LX) {
        SectionTitle(stringResource(R.string.online_quality_title))
        PickerField(
            label = stringResource(R.string.online_quality_subtitle),
            value = qualityLabel(quality),
            expanded = qualityExpanded,
            onExpandChange = { qualityExpanded = it },
            options = listOf("") + Quality.BY_QUALITY.map { it.id },
            optionLabel = { qualityLabel(it) },
            onSelect = {
                quality = it
                prefs.onlineQuality = it
                qualityExpanded = false
            },
        )
        if (quality != SettingsDefaults.ONLINE_QUALITY) {
            TextButton(onClick = {
                quality = SettingsDefaults.ONLINE_QUALITY
                prefs.onlineQuality = SettingsDefaults.ONLINE_QUALITY
            }) { Text(stringResource(R.string.default_label)) }
        }
    }

    SectionTitle(stringResource(R.string.sources))
    ActionRow(
        title = stringResource(R.string.online_playlists_m3u),
        subtitle = stringResource(R.string.add_m3u_m3u8_or_pls),
        onClick = onOpenPlaylists,
        chevron = true,
    )

    SectionTitle(stringResource(R.string.playback_cache))
    Text(
        stringResource(R.string.used, formatByteSize(used)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    LinearProgressIndicator(
        progress = { if (limit <= 0L) 0f else (used.toFloat() / limit).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
    ActionRow(
        title = stringResource(R.string.cache_limit),
        subtitle = stringResource(R.string.replays_read_the_downloaded_part),
        trailing = cacheStepLabel(limit),
        chevron = true,
        onReset = if (limit != OnlineMediaCache.MAX_BYTES) {
            {
                OnlineMediaCache.setLimitBytes(context, OnlineMediaCache.MAX_BYTES)
                limit = OnlineMediaCache.limitBytes(context)
                used = OnlineMediaCache.sizeBytes(context)
            }
        } else {
            null
        },
        onClick = { pickingLimit = true },
    )
    ActionRow(
        title = stringResource(R.string.clear_cache),
        subtitle = stringResource(R.string.cleared_played_songs_will_be),
        onClick = {
            val ok = OnlineMediaCache.clear(context)
            used = OnlineMediaCache.sizeBytes(context)
            Toast.makeText(context, if (ok) AppStrings.s(R.string.online_cache_cleared) else AppStrings.s(R.string.nothing_to_clear), Toast.LENGTH_SHORT).show()
        },
    )
    SwitchRow(
        title = stringResource(R.string.online_cache_prefetch),
        subtitle = stringResource(R.string.online_cache_prefetch_sub),
        checked = prefetch,
        onCheckedChange = {
            prefetch = it
            prefs.onlineCachePrefetch = it
        },
        onReset = resetAction(prefetch != SettingsDefaults.ONLINE_CACHE_PREFETCH) {
            prefetch = SettingsDefaults.ONLINE_CACHE_PREFETCH
            prefs.onlineCachePrefetch = SettingsDefaults.ONLINE_CACHE_PREFETCH
        },
    )
    SwitchRow(
        title = stringResource(R.string.online_parallel_download),
        subtitle = stringResource(R.string.online_parallel_download_sub),
        checked = parallel,
        onCheckedChange = {
            parallel = it
            prefs.onlineParallelDownload = it
        },
        onReset = resetAction(parallel != SettingsDefaults.ONLINE_PARALLEL_DOWNLOAD) {
            parallel = SettingsDefaults.ONLINE_PARALLEL_DOWNLOAD
            prefs.onlineParallelDownload = SettingsDefaults.ONLINE_PARALLEL_DOWNLOAD
        },
    )

    SectionTitle(stringResource(R.string.playback_history))
    ActionRow(
        title = stringResource(R.string.lib_listening_report),
        subtitle = stringResource(R.string.listening_time_time_of_day),
        onClick = onOpenReport,
        chevron = true,
    )

    if (pickingLimit) {
        AlertDialog(
            onDismissRequest = { pickingLimit = false },
            title = { Text(stringResource(R.string.cache_limit)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.lowering_the_limit_immediately_drops),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OnlineMediaCache.LIMIT_STEPS.forEach { step ->
                        val selected = step == limit
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    OnlineMediaCache.setLimitBytes(context, step)
                                    limit = OnlineMediaCache.limitBytes(context)
                                    used = OnlineMediaCache.sizeBytes(context)
                                    pickingLimit = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cacheStepLabel(step),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    when {
                                        step == OnlineMediaCache.MAX_BYTES -> stringResource(R.string.default_label)
                                        step < OnlineMediaCache.MAX_BYTES -> stringResource(R.string.uses_less_storage)
                                        else -> stringResource(R.string.uses_more_storage)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
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
                TextButton(onClick = { pickingLimit = false }) { Text(stringResource(R.string.off)) }
            },
        )
    }
}

/**
 * 在线曲目的收听报告（i18n 第 18 批）。版式与本地 [ListeningReportScreen] 相同，
 * 标题/空态用 `olr_*`，其余分区复用 `lr_*`；
 * 小时桶和榜单都来自 [AliyunTrackStore]，不读曲库的 `listen_log`。
 */
@Composable
internal fun OnlineListeningReport(
    onClose: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(30) }
    var state by remember { mutableStateOf<ReportUiState?>(null) }

    LaunchedEffect(days) {
        val span = days
        val now = System.currentTimeMillis()
        state = withContext(Dispatchers.IO) {
            val store = AliyunTrackStore(context)
            val records = store.records()
            ReportUiState(
                days = span,
                report = buildListeningReport(store.listenBuckets(), now, span),
                topPlayed = records.top(byPlay = true),
                topListened = records.top(byPlay = false),
            )
        }
    }

    ListeningReportScreen(
        state = state,
        onClose = onClose,
        onSetDays = { days = it },
        title = stringResource(R.string.olr_title),
        emptyPlaysHint = stringResource(R.string.olr_empty_plays),
        emptyListenHint = stringResource(R.string.olr_empty_listen),
        onPlayTop = { songs ->
            if (songs.isEmpty()) return@ListeningReportScreen
            scope.launch {
                val drive = AliyunDrive(AliyunAuth(context))
                val filled = withContext(Dispatchers.IO) { drive.fillWindow(songs, 0) }
                val playAt = AliyunPlaybackWindow.firstReady(
                    filled.map { AliyunPlaybackWindow.isPending(it.uri.scheme) },
                    0,
                )
                if (playAt == null) {
                    Toast.makeText(context, AppStrings.s(R.string.no_playable_url_was_returned), Toast.LENGTH_SHORT).show()
                } else {
                    onPlay(filled, playAt)
                }
            }
        },
    )
}

private fun List<AliyunTrackRecord>.top(byPlay: Boolean): List<TopSong> {
    val ranked = if (byPlay) {
        filter { it.playCount > 0 }.sortedByDescending { it.playCount }
    } else {
        filter { it.playedMs > 0 }.sortedByDescending { it.playedMs }
    }
    return ranked.take(10).map { record ->
        val song = AliyunDrive(tokenProvider = { null }).pendingSong(record)
        TopSong(
            songId = song.id,
            stableKey = song.stableKey,
            title = song.title.ifBlank { AppStrings.s(R.string.untitled) },
            artist = song.artistOrUnknown,
            playCount = record.playCount,
            listenedMs = record.playedMs,
            cover = song,
        )
    }
}

@Composable
internal fun SettingsMenuOrderPage(
    order: List<String>,
    onChange: (List<String>) -> Unit,
) {
    SectionTitle(stringResource(R.string.category_order))
    Text(
        stringResource(R.string.move_cards_on_the_settings),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val pages = SettingsMenuOrder.pages(order)
    Column(Modifier.fillMaxWidth()) {
        pages.forEachIndexed { index, page ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(page.title, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TextButton(enabled = index > 0, onClick = {
                    val ids = pages.map { it.name }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index - 1, moved)
                    onChange(ids)
                }) { Text(stringResource(R.string.move_up)) }
                TextButton(enabled = index < pages.lastIndex, onClick = {
                    val ids = pages.map { it.name }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index + 1, moved)
                    onChange(ids)
                }) { Text(stringResource(R.string.move_down)) }
            }
        }
    }
}

/** 缓存档位的短标签。容量统计用的「512.0 MB」放不进弹窗选项。 */
private fun cacheStepLabel(bytes: Long): String = when {    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)}GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)}MB"
    else -> formatByteSize(bytes)
}

/** 音质标签：空串表示「最高可用」（不限制档位） */
private fun qualityLabel(id: String): String =
    if (id.isBlank()) AppStrings.s(R.string.online_quality_auto)
    else Quality.of(id)?.label ?: id

/**
 * 下拉选择项。
 *
 * **为什么不用 `OutlinedTextField + Modifier.clickable`**：`readOnly` 的 TextField 依然
 * 会自己消费 pointer 事件去拿焦点，外层 `clickable` 收不到点击 —— 真机上表现就是
 * 「点了没任何反应」（v1.2.61 用户实测）。这里改成纯装饰的 Box：
 * 外观与带 label 的输入框一致，但点击由 Box 自己处理，不再和 TextField 抢事件。
 */
@Composable
private fun <T> PickerField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(
                    1.dp,
                    if (expanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(4.dp),
                )
                .clickable { onExpandChange(!expanded) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (expanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandChange(false) },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}
