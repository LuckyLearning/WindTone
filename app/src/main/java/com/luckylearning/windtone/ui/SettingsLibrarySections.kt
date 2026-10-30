package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.buildLibraryStats
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.PlayStats

/**
 * 「曲库与存储」页的四个分区（2026-10-30）。
 *
 * **写法约定（用户 2026-10-30 明确要求）：功能代码独立，页面只做组合。**
 * 每个分区是一个自包含的 composable —— 自己的标题、自己的行、自己的弹窗状态，
 * 外部只给「数据 + 回调」。页面（`SettingsLibraryPage`）里只留一串按顺序的调用，
 * 所以：
 * - **加一个功能** = 在这里加一个函数 + 在页面里加一行调用，不动别的分区；
 * - **调顺序** = 在页面里挪一行，不用在几百行里找边界；
 * - **改一个分区** = 只读这一个函数，不必先读懂整页。
 *
 * 反面教材就是这次改造前的样子：一整页把「统计 / 过滤 / 维护 / 危险操作」串成
 * 三百多行，中间夹着五个弹窗，改哪一段都得从头看到尾。
 */

// ================================================================ ① 统计（两个入口并排）

/**
 * 统计分区：两个入口**并排**（用户口径「按钮放一起，查看放 2 个页面」）。
 *
 * 为什么不合成一页：两者看的不是一回事 —— 播放统计是「我听了多久」，
 * 曲库统计是「我的库有多大」。合并成一页要么信息过载，要么砍掉一半。
 * 但入口必须挨着：这两个问题总是一起被想起。
 */
@Composable
internal fun LibraryStatsSection(
    songs: List<Song>,
    playStats: PlayStats,
    onOpenPlaybackStats: () -> Unit,
    onOpenLibraryStats: () -> Unit,
) {
    // 摘要由纯函数算，且只跟着曲库变 —— 每次重组都重算一遍在几百首的量级上不痛，
    // 但 `remember(songs)` 让这一区在滚动 / 弹层开关时完全不碰统计。
    val stats = remember(songs) { buildLibraryStats(songs) }

    SectionTitle(stringResource(R.string.stats))
    ActionRow(
        title = stringResource(R.string.playback_stats),
        subtitle = stringResource(R.string.songs_played) + " " +
            stringResource(R.string.songs, playStats.listenedSongs) + " · " +
            stringResource(R.string.plays, playStats.totalPlays),
        onClick = onOpenPlaybackStats,
        trailing = formatListened(playStats.totalListenedMs),
        chevron = true,
    )
    ActionRow(
        title = stringResource(R.string.library_stats),
        subtitle = stringResource(R.string.track_counts_and_size_per),
        onClick = onOpenLibraryStats,
        trailing = if (stats.totalSongs > 0) {
            stringResource(R.string.songs_2, stats.totalSongs, formatByteSize(stats.totalBytes))
        } else {
            "—"
        },
        chevron = true,
    )
}

// ================================================================ ② 本地曲库（过滤）

/**
 * 本地曲库分区：短音频过滤。
 *
 * 原「格式与容量」行已升级成上面「统计」里的「曲库统计」入口 —— 那一行名不符实
 * （叫设置项、点开是数据页），这次一并修掉。这里只剩真正的一次性配置。
 */
@Composable
internal fun LocalLibrarySection(
    minSongDurationSec: Int,
    onMinSongDurationSec: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.local_library))
    ActionRow(
        title = if (minSongDurationSec > 0) {
            stringResource(R.string.short_audio_filter_s, minSongDurationSec)
        } else {
            stringResource(R.string.short_audio_filter_off)
        },
        subtitle = stringResource(R.string.audio_with_a_known_duration),
        onClick = { showDialog = true },
        chevron = true,
        onReset = resetAction(minSongDurationSec != SettingsDefaults.MIN_DURATION_SEC) {
            onMinSongDurationSec(SettingsDefaults.MIN_DURATION_SEC)
        },
    )

    if (showDialog) {
        var input by remember {
            mutableStateOf(if (minSongDurationSec > 0) minSongDurationSec.toString() else "")
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.short_audio_filter)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.audio_shorter_than_this_many),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.minimum_duration_s)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMinSongDurationSec(input.toIntOrNull()?.coerceIn(0, 3600) ?: 0)
                        showDialog = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

// ================================================================ ③ 数据与存储（占用 + 维护）

/**
 * 数据与存储分区：实时占用 StatsCard + 四条维护动作（清理失效 / 恢复移除 / 查重 / 清封面缓存）。
 *
 * 这四条原本挂在「关于与诊断」里 —— 它们全是曲库的「管」，与版本号同页说不通。
 * 进入即刷新一次自检，因为用户点进这一区就是为了看「现在占多少」。
 */
@Composable
internal fun DataStorageSection(
    maintenance: DataMaintenance.Report?,
    onRefreshMaintenance: () -> Unit,
    onCleanupMissingSongs: () -> Unit,
    onRestoreExcluded: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onClearCoverCache: () -> Unit,
) {
    var showCleanupConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onRefreshMaintenance() }

    SectionTitle(stringResource(R.string.data_storage))
    StatCard(
        rows = listOf(
            stringResource(R.string.library_songs) to if (maintenance == null) "—" else stringResource(R.string.songs, maintenance.songs),
            stringResource(R.string.not_accessible) to if (maintenance == null) "—" else stringResource(R.string.songs, maintenance.missing),
            stringResource(R.string.removed_from_library) to if (maintenance == null) "—" else stringResource(R.string.songs, maintenance.excluded),
            stringResource(R.string.imported_folders) to if (maintenance == null) "—" else stringResource(R.string.text, maintenance.folders),
            stringResource(R.string.view_playlists) to if (maintenance == null) "—" else stringResource(R.string.text, maintenance.playlists),
            stringResource(R.string.cover_cache) to (maintenance?.coverSizeText ?: "—"),
            stringResource(R.string.database_size) to (maintenance?.dbSizeText ?: "—"),
        )
    )
    ActionRow(
        title = stringResource(R.string.clean_up_missing_songs),
        subtitle = if ((maintenance?.missing ?: 0) > 0) {
            stringResource(R.string.songs_are_no_longer_on, maintenance!!.missing)
        } else {
            stringResource(R.string.songs_whose_files_are_missing)
        },
        onClick = { showCleanupConfirm = true },
    )
    // 「移出曲库」是这个应用里唯一一个"歌还在、但被用户主动藏起来"的状态：
    // 不给恢复入口的话，用户误点一次就再也找不回来（重扫也不会收）。
    ActionRow(
        title = stringResource(R.string.restore_songs_removed_from_library),
        subtitle = if ((maintenance?.excluded ?: 0) > 0) {
            stringResource(R.string.songs_were_removed_from_the, maintenance!!.excluded)
        } else {
            stringResource(R.string.no_songs_removed_from_the)
        },
        onClick = onRestoreExcluded,
    )
    ActionRow(
        title = stringResource(R.string.duplicate_cleanup),
        subtitle = stringResource(R.string.finds_duplicates_by_title_artist),
        onClick = onOpenDuplicates,
        chevron = true,
    )
    ActionRow(
        title = stringResource(R.string.clear_cover_cache),
        subtitle = stringResource(R.string.only_deletes_regenerable_cover_images),
        onClick = onClearCoverCache,
    )

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            title = { Text(stringResource(R.string.clean_up_missing_songs_2)) },
            text = {
                Text(
                    stringResource(R.string.removes_records_of_songs_whose),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCleanupMissingSongs()
                        showCleanupConfirm = false
                    },
                ) { Text(stringResource(R.string.clean_up)) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

// ================================================================ ④ 危险操作（不可撤销）

/**
 * 危险操作分区：三个不可撤销的清空，**固定在页尾**。
 *
 * 与上面可逆的项物理隔开 —— 三个 TextButton 挨在一起放在页面最底部，
 * 手滑点到的概率远低于把它们混在维护行里。每个都走二次确认。
 */
@Composable
internal fun LibraryDangerZoneSection(
    onClearStats: () -> Unit,
    onResetUserData: () -> Unit,
    onResetLibrary: () -> Unit,
) {
    var showClearStatsConfirm by remember { mutableStateOf(false) }
    var showResetDataConfirm by remember { mutableStateOf(false) }
    var showResetLibraryConfirm by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.danger_zone))
    TextButton(onClick = { showClearStatsConfirm = true }) {
        Text(stringResource(R.string.clear_playback_stats_keeps_favorites))
    }
    TextButton(onClick = { showResetDataConfirm = true }) {
        Text(stringResource(R.string.clear_all_user_data_keeps))
    }
    TextButton(onClick = { showResetLibraryConfirm = true }) {
        Text(stringResource(R.string.clear_library_and_imported_folders))
    }

    // 以下三处都会丢数据且不可撤销，统一走二次确认
    if (showClearStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStatsConfirm = false },
            title = { Text(stringResource(R.string.clear_playback_stats)) },
            text = {
                Text(
                    stringResource(R.string.clears_play_counts_total_listening),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearStats()
                        showClearStatsConfirm = false
                    },
                ) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearStatsConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showResetDataConfirm) {
        AlertDialog(
            onDismissRequest = { showResetDataConfirm = false },
            title = { Text(stringResource(R.string.clear_all_user_data)) },
            text = {
                Text(
                    stringResource(R.string.clears_favorites_playback_stats_per),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetUserData()
                        showResetDataConfirm = false
                    },
                ) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDataConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 清空曲库：与「清空全部用户数据」互为镜像 —— 一个清数据留曲库，一个清曲库留数据。
    // 因为「留数据」这一半很容易被误解成"什么都没删"，文案必须写清双方各自保住了什么。
    if (showResetLibraryConfirm) {
        AlertDialog(
            onDismissRequest = { showResetLibraryConfirm = false },
            title = { Text(stringResource(R.string.clear_library_and_imported_folders_2)) },
            text = {
                Text(
                    stringResource(R.string.removes_every_song_record_and),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetLibrary()
                        showResetLibraryConfirm = false
                    },
                ) { Text(stringResource(R.string.clear_library)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetLibraryConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
