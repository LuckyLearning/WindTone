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

    SectionTitle("在线歌单")
    Text(
        text = "支持 m3u / m3u8 / pls 直链。播放走系统播放内核，不需要下载；" +
            "在线条目没有本地文件，因此不参与扫描、收藏与播放统计。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    ActionRow(
        title = "添加在线歌单",
        subtitle = "填播放列表地址（m3u / m3u8 / pls），也可以直接填一个 http(s) 音频直链",
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
            text = "还没有添加过在线歌单。\n" +
                "提示：SomaFM（https://somafm.com/groovesalad.m3u）、Radio Paradise、" +
                "各类自建电台的 m3u 都能直接用；HLS（.m3u8）需要整条流作为一项播放。",
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
                                append(if (p.isStream) "HLS 直播流" else "${p.entries.size} 条")
                                if (p.note.isNotBlank()) append(" · ").append(p.note)
                                append(" · 更新于 ").append(formatTime(p.updatedAt))
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
                                error = "这份列表里没有可播放的条目"
                            } else {
                                onPlay(songs, 0)
                            }
                        },
                    ) { Text(if (p.isStream) "播放" else "播放全部") }
                    TextButton(
                        enabled = busy.isBlank(),
                        onClick = {
                            busy = "正在刷新「${p.name}」…"
                            scope.launch {
                                repo.refresh(p.id)
                                    .onSuccess { reload() }
                                    .onFailure { error = it.message ?: "刷新失败" }
                                busy = ""
                            }
                        },
                    ) { Text("刷新") }
                    TextButton(onClick = { renaming = p }) { Text("重命名") }
                    Spacer(Modifier.weight(1f))
                    if (!p.isStream && p.entries.isNotEmpty()) {
                        TextButton(onClick = { expanded = if (isExpanded) null else p.id }) {
                            Text(if (isExpanded) "收起" else "查看条目")
                        }
                    }
                    TextButton(onClick = { deleting = p }) { Text("删除") }
                }
                if (isExpanded) {
                    val songs = remember(p) { repo.toSongs(p) }
                    Text(
                        text = if (p.entries.size > PREVIEW_LIMIT) {
                            "仅列出前 $PREVIEW_LIMIT 条（共 ${p.entries.size} 条），播放不受影响"
                        } else {
                            "点任意一条从那里开始播放"
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
                            TextButton(onClick = { onPlay(songs, index) }) { Text("播放") }
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
                busy = "正在读取播放列表…"
                scope.launch {
                    repo.add(name, url)
                        .onSuccess { reload() }
                        .onFailure { error = it.message ?: "添加失败" }
                    busy = ""
                }
            },
        )
    }

    renaming?.let { target ->
        TextInputDialog(
            title = "重命名在线歌单",
            label = "名称",
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
            title = { Text("删除「${target.name}」？") },
            text = { Text("只从本机移除这条地址与已解析的条目，不影响在线内容本身。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.remove(target.id)
                    deleting = null
                    reload()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("没能完成") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("好") } },
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
        title = { Text("添加在线歌单") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("播放列表地址") },
                    placeholder = { Text("https://example.com/list.m3u") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持 m3u / m3u8 / pls；也接受单个 http(s) 音频直链。" +
                        "添加时会先读取一次，读不到内容不会保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onConfirm(name, url) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** 展开预览的条数上限：父容器已是可滚动 Column，这里不能再长 */
private const val PREVIEW_LIMIT = 60
