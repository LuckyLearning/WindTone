package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.OnlinePlaylist
import com.luckylearning.windtone.data.online.OnlinePlaylists
import com.luckylearning.windtone.data.online.PlaylistKind
import com.luckylearning.windtone.ui.theme.windShadow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.luckylearning.windtone.R

/**
 * 在线歌单（M3U / M3U8 / PLS）—— v1.1.37。
 *
 * 这一页刻意只做四件事：添加地址、刷新、播放、删除。在线源的**来源本身**不进入曲库：
 * 远程条目没有本地文件，歌词 / 封面 / 收藏 / 统计这些围绕 `dbId` 的能力对它们都不成立
 * （详见 `docs/架构评审-联网播放可行性.md`）。把它们混进曲库会让「扫描」「清理失效歌曲」
 * 这些本地语义立刻自相矛盾，所以这里走**独立列表 + 独立队列**。
 *
 * 界面是普通 Column 而不是 LazyColumn：这一页挂在设置页那个带 `verticalScroll` 的父容器里，
 * 嵌套可滚动列表会抢滚动事件。条目预览因此封顶（[PREVIEW_LIMIT]），播放不受影响。
 */
@Composable
internal fun OnlinePlaylistsPage(
    onPlay: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { OnlinePlaylists(context) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(repo.all()) }
    var busy by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<OnlinePlaylist?>(null) }
    var deleting by remember { mutableStateOf<OnlinePlaylist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        items = repo.all()
    }

    SectionTitle(stringResource(R.string.online_section_title))
    Text(
        text = stringResource(R.string.online_section_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    ActionRow(
        title = stringResource(R.string.online_add_title),
        subtitle = stringResource(R.string.online_add_subtitle),
        onClick = { showAdd = true },
        chevron = true,
    )

    if (busy.isNotBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(busy, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.online_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }

    items.forEach { p ->
        val isExpanded = expanded == p.id
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .windShadow(level = 1, shape = RoundedCornerShape(12.dp)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(if (p.isStream) stringResource(R.string.online_hls_live) else stringResource(R.string.online_entry_count, p.entries.size))
                                if (p.note.isNotBlank()) append(" · ").append(p.note)
                                append(stringResource(R.string.online_updated_at)).append(formatTime(p.updatedAt))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            p.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val songs = repo.toSongs(p)
                            if (songs.isEmpty()) {
                                error = context.getString(R.string.online_no_entries)
                            } else {
                                onPlay(songs, 0)
                            }
                        },
                    ) { Text(if (p.isStream) stringResource(R.string.online_play) else stringResource(R.string.play_all)) }
                    TextButton(
                        enabled = busy.isBlank(),
                        onClick = {
                            busy = context.getString(R.string.online_refreshing, p.name)
                            scope.launch {
                                repo.refresh(p.id)
                                    .onSuccess { reload() }
                                    .onFailure { error = it.message ?: context.getString(R.string.online_refresh_failed) }
                                busy = ""
                            }
                        },
                    ) { Text(stringResource(R.string.refresh)) }
                    TextButton(onClick = { renaming = p }) { Text(stringResource(R.string.rename)) }
                    Spacer(Modifier.weight(1f))
                    if (!p.isStream && p.entries.isNotEmpty()) {
                        TextButton(onClick = { expanded = if (isExpanded) null else p.id }) {
                            Text(if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.online_view_entries))
                        }
                    }
                    TextButton(onClick = { deleting = p }) { Text(stringResource(R.string.delete)) }
                }
                if (isExpanded) {
                    val songs = remember(p) { repo.toSongs(p) }
                    Text(
                        text = if (p.entries.size > PREVIEW_LIMIT) {
                            stringResource(R.string.online_preview_capped, PREVIEW_LIMIT, p.entries.size)
                        } else {
                            stringResource(R.string.online_tap_to_start)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    p.entries.take(PREVIEW_LIMIT).forEachIndexed { index, entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(34.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                entry.artist?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            TextButton(onClick = { onPlay(songs, index) }) { Text(stringResource(R.string.online_play)) }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        OnlinePlaylistAddDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, url ->
                showAdd = false
                busy = context.getString(R.string.online_reading)
                scope.launch {
                    repo.add(name, url)
                        .onSuccess { reload() }
                        .onFailure { error = it.message ?: context.getString(R.string.online_add_failed) }
                    busy = ""
                }
            },
        )
    }

    renaming?.let { target ->
        TextInputDialog(
            title = stringResource(R.string.online_rename_title),
            label = stringResource(R.string.online_name_label),
            initial = target.name,
            onDismiss = { renaming = null },
            onConfirm = { value ->
                repo.rename(target.id, value)
                renaming = null
                reload()
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.online_delete_title, target.name)) },
            text = { Text(stringResource(R.string.online_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    repo.remove(target.id)
                    deleting = null
                    reload()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.online_error_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/**
 * 添加弹层。
 *
 * 两个字段分开而不是「贴一个地址就完事」：在线电台的清单里通常没有像样的标题
 * （HLS 甚至一条都没有），名称必须让用户自己起，否则列表里会出现一串 `example.com`。
 */
@Composable
private fun OnlinePlaylistAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.online_url_label)) },
                    placeholder = { Text("https://example.com/list.m3u") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.online_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.online_add_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onConfirm(name, url) },
            ) { Text(stringResource(R.string.online_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 通用单行输入弹层（重命名用；设置页此前没有可复用的实现） */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** 展开预览的条数上限：父容器已是可滚动 Column，这里不能再长 */
private const val PREVIEW_LIMIT = 60
