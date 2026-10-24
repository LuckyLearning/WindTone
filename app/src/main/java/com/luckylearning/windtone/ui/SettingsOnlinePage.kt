package com.luckylearning.windtone.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
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
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunDrive
import com.luckylearning.windtone.data.aliyun.AliyunPlaybackWindow
import com.luckylearning.windtone.data.aliyun.AliyunTrackRecord
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.buildListeningReport
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
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

    SectionTitle("来源")
    ActionRow(
        title = "在线歌单（M3U）",
        subtitle = "添加 m3u / m3u8 / pls 直链并播放",
        onClick = onOpenPlaylists,
        chevron = true,
    )

    SectionTitle("播放缓存")
    Text(
        "已用 ${formatByteSize(used)}",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    LinearProgressIndicator(
        progress = { if (limit <= 0L) 0f else (used.toFloat() / limit).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
    ActionRow(
        title = "缓存上限",
        subtitle = "同一首再次播放时直接读已下载的部分。调小上限会立刻删掉最久没听的歌。",
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
        title = "清空缓存",
        subtitle = "清空后，听过的歌需要重新下载",
        onClick = {
            val ok = OnlineMediaCache.clear(context)
            used = OnlineMediaCache.sizeBytes(context)
            Toast.makeText(context, if (ok) "已清除在线缓存" else "当前没有可清除的缓存", Toast.LENGTH_SHORT).show()
        },
    )

    SectionTitle("播放记录")
    ActionRow(
        title = "收听报告",
        subtitle = "阿里云盘歌曲的收听时长、时段和榜单（云盘页已在抽屉一级入口，入口不再挂这里）",
        onClick = onOpenReport,
        chevron = true,
    )

    if (pickingLimit) {
        AlertDialog(
            onDismissRequest = { pickingLimit = false },
            title = { Text("缓存上限") },
            text = {
                Column {
                    Text(
                        "调小上限会立刻删掉最久没听的歌。默认 512MB。",
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
                                        step == OnlineMediaCache.MAX_BYTES -> "默认"
                                        step < OnlineMediaCache.MAX_BYTES -> "占用更少存储"
                                        else -> "占用更多存储"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
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
                TextButton(onClick = { pickingLimit = false }) { Text("关闭") }
            },
        )
    }
}

/**
 * 在线曲目的收听报告。版式与本地 [ListeningReportScreen] 相同，
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
                    Toast.makeText(context, "没有取到可播放的地址", Toast.LENGTH_SHORT).show()
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
            title = song.title.ifBlank { "未命名" },
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
    SectionTitle("分类顺序")
    Text(
        "上移、下移调整设置主页的分类卡。子页里面的开关不参加排序。",
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
                }) { Text("上移") }
                TextButton(enabled = index < pages.lastIndex, onClick = {
                    val ids = pages.map { it.name }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index + 1, moved)
                    onChange(ids)
                }) { Text("下移") }
            }
        }
    }
}

/** 缓存档位的短标签。容量统计用的「512.0 MB」放不进弹窗选项。 */
private fun cacheStepLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)}GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)}MB"
    else -> formatByteSize(bytes)
}
