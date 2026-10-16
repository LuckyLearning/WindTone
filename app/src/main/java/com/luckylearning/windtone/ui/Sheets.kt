@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luckylearning.windtone.ui
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.QueueEntry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.playback.Pitch
import com.luckylearning.windtone.playback.SleepTimer
import com.luckylearning.windtone.state.FolderItem
import kotlin.math.roundToInt

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
    ModalBottomSheet(onDismissRequest = onClose) {
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
            LazyColumn(Modifier.height(420.dp)) {
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
    ModalBottomSheet(onDismissRequest = onClose) {
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

// ================================================================ 歌曲长按操作

@Composable
fun SongActionsSheet(
    song: Song,
    isFavorite: Boolean,
    canDelete: Boolean = false,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEdit: () -> Unit,
    onViewAlbum: () -> Unit,
    onViewArtist: () -> Unit,
    onAudioInfo: () -> Unit,
    onDelete: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                song.artistOrUnknown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            SheetAction(if (isFavorite) "取消收藏" else "收藏") { onToggleFavorite(); onDismiss() }
            SheetAction("下一首播放") { onPlayNext(); onDismiss() }
            SheetAction("加入歌单…") { onAddToPlaylist(); onDismiss() }
            SheetAction("查看专辑") { onViewAlbum(); onDismiss() }
            SheetAction("查看歌手") { onViewArtist(); onDismiss() }
            SheetAction("编辑歌曲信息…") { onEdit(); onDismiss() }
            SheetAction("音频信息") { onAudioInfo(); onDismiss() }
            if (canDelete) {
                SheetAction("删除歌曲文件…", color = MaterialTheme.colorScheme.error) { onDelete() }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ================================================================ 音频技术信息

@Composable
fun SongInfoSheet(
    song: Song,
    info: AudioInspector.Info?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "音频信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                song.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在读取…", style = MaterialTheme.typography.bodySmall)
                }

                info == null -> Text(
                    "无法读取该文件的音频信息，可能是不受支持的容器格式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    InfoRow("容器格式", info.container)
                    InfoRow("编码", info.codec)
                    InfoRow("采样率", info.sampleRateText)
                    InfoRow("声道", info.channelsText)
                    InfoRow("位深", info.bitsText)
                    InfoRow("码率", info.bitrateText)
                    InfoRow("时长", info.durationText)
                    InfoRow("文件大小", info.sizeText)
                    info.mime?.let { InfoRow("MIME", it) }
                    InfoRow("路径", songDisplayPath(song))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(78.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 「路径」行的人类可读形式。
 *
 * `song.uri` 原样打印会是百分号编码的 content URI（如
 * `content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F38度6%2F38度6%20-%20黑龙.flac`），
 * 用户看到的就是一堆乱码。这里按来源还原：
 *  - 有真实绝对路径（深度扫描 / Android 10 以下媒体库）→ 直接用；
 *  - SAF 文档 URI → 取 `document/` 里的文档 ID（Android 的 Uri 解析已解一次码），
 *    `primary:` 前缀映射为内置存储根 `/storage/emulated/0/`；
 *  - 其余兜底：媒体库相对目录 + 文件名，再不行才回退原始 URI。
 */
fun songDisplayPath(song: Song): String {
    song.path?.takeIf { it.isNotBlank() }?.let { return it }
    val uri = song.uri
    val raw = uri.toString()
    return when {
        uri.scheme == "content" && raw.contains("/document/") -> {
            // lastPathSegment 由 android.net.Uri 解析，已经是解码后的文档 ID，
            // 形如 `primary:Music/38度6/38度6 - 黑龙.flac`
            val doc = uri.lastPathSegment
                ?: runCatching { android.net.Uri.decode(raw.substringAfterLast("/document/")) }
                    .getOrDefault(raw)
            when {
                doc.startsWith("primary:") -> "/storage/emulated/0/" + doc.removePrefix("primary:")
                // 非 primary 存储根（home: 等）没有统一的挂载点，只展示冒号后的相对路径
                doc.contains(':') -> doc.substringAfter(':', doc)
                else -> doc
            }
        }
        song.folderPath != null && song.displayName.isNotBlank() ->
            song.folderPath.trimEnd('/') + "/" + song.displayName
        else -> raw
    }
}

@Composable
private fun SheetAction(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

// ================================================================ 选择歌单

@Composable
fun AddToPlaylistSheet(
    playlists: List<PlaylistInfo>,
    /**
     * 单曲加入时的来源。多选批量加入时传 null —— 同时传 song 和 count
     * 会出现「把『晴天』加入歌单」这类只说了一半的标题。
     */
    song: Song?,
    /** 多选批量加入的歌曲数（[song] 为 null 时用它生成标题） */
    count: Int = 0,
    onDismiss: () -> Unit,
    onPick: (PlaylistInfo) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                song?.let { "把「${it.title}」加入歌单" } ?: "把所选的 $count 首加入歌单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (creating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("新歌单名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (name.isNotBlank()) {
                                onCreateNew(name.trim())
                                onDismiss()
                            }
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("创建") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { creating = true }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("新建歌单…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                }
                LazyColumn(Modifier.height(300.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(p); onDismiss() }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        ) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.songCount} 首", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ================================================================ 歌曲信息编辑

@Composable
fun EditSongDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, album: String) -> Unit,
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artistOrUnknown) }
    var album by remember { mutableStateOf(song.albumOrUnknown) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑歌曲信息") },
        text = {
            Column {
                Text(
                    "仅在本应用内覆盖显示，不会修改音频文件本身。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("歌手") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("专辑") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, artist, album); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ================================================================ 新建歌单

@Composable
fun NewPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("歌单名称") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) { onCreate(name.trim()); onDismiss() } }, enabled = name.isNotBlank()) {
                Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ================================================================ 歌单详情菜单

@Composable
fun PlaylistActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDedupe: () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("重命名…") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(
            text = { Text("移除重复歌曲") },
            onClick = { onDismiss(); onDedupe() },
        )
        DropdownMenuItem(text = { Text("导出为 M3U…") }, onClick = { onDismiss(); onExport() })
        DropdownMenuItem(text = { Text("删除歌单") }, onClick = { onDismiss(); onDelete() })
    }
}

// ================================================================ 扫描范围选择

/**
 * 扫描范围选择抽屉。
 *
 * 应用默认不扫描全盘，用户在这里明确决定：扫全盘，还是只看指定目录。
 * 目录行上除了「移除」，还多了一个「排除」开关——它只阻止以后扫描这棵树，
 * **不会**把已经导入的歌清掉（与移除目录的区别见 `LibraryUiState.folderUris` 注释）。
 */
@Composable
fun ScanScopeSheet(
    current: ScanScope,
    folders: List<FolderItem>,
    onFullScan: () -> Unit,
    onPickFolder: () -> Unit,
    onUseFolders: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onToggleExcluded: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val activeCount = folders.count { !it.excluded }
    val folderCount = folders.size
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("扫描范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "默认不会扫描整个手机，由你指定去哪里找音乐。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionLabel("扫描全盘")
            OptionRow(
                icon = AppIcons.MusicNote,
                title = "扫描全盘音乐",
                subtitle = "读取系统媒体库；已授予「所有文件访问权限」时会连同同目录歌词一起扫",
                selected = current == ScanScope.FULL,
                onClick = onFullScan,
            )

            SectionLabel("指定目录")
            OptionRow(
                icon = AppIcons.Folder,
                title = if (activeCount > 0) "只看已导入的目录" else "选择音乐目录…",
                subtitle = if (activeCount > 0) "当前参与扫描 $activeCount 个目录"
                else if (folderCount > 0) "所有目录都被排除了，先取消排除或导入新目录"
                else "通过系统文件选择器指定一个或多个音乐文件夹",
                selected = current == ScanScope.FOLDERS && activeCount > 0,
                onClick = { if (activeCount > 0) onUseFolders() else onPickFolder() },
            )
            OptionRow(
                icon = AppIcons.Folder,
                title = "导入更多目录…",
                subtitle = "新目录会追加进列表",
                onClick = onPickFolder,
            )

            if (folderCount > 0) {
                SectionLabel("已导入的目录")
                folders.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            // 主标题只显示最后一个文件夹名字；父路径降为次要小字，
                            // 避免两个同名目录（如 Music/华语 与 Download/华语）分不清
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (item.excluded) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                buildString {
                                    if (item.fullPath.isNotEmpty()) append(item.fullPath).append(" · ")
                                    append(if (item.excluded) "已排除，不再扫描" else "${item.songCount} 首")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = item.excluded,
                            onCheckedChange = { onToggleExcluded(item.treeUri, it) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        IconButton(onClick = { onRemoveFolder(item.treeUri) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "移除该目录",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "「排除」只停止以后扫描该目录，已导入的歌曲仍留在曲库里；" +
                        "「✕ 移除」则会把目录和已导入的歌曲一起摘掉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 扫描范围胶囊标签 */
@Composable
fun ScanScopeChip(
    scope: ScanScope,
    folderCount: Int,
    onClick: () -> Unit,
) {
    val label = when (scope) {
        ScanScope.UNSET -> "未选择扫描范围"
        ScanScope.FULL -> "全盘扫描"
        ScanScope.FOLDERS -> if (folderCount > 0) "指定目录 $folderCount 个" else "指定目录"
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(
                AppIcons.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
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
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
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
            SectionLabel("速度与变调")
            SpeedControl(speed = speed, onSpeed = onSpeed)
            PitchControl(semitones = pitchSemitones, onPitch = onPitch)

            SectionLabel("歌词")
            // 悬浮歌词放在「歌词」分组第一位：它是这首歌「往哪儿显示」最常用的一个选择，
            // 比导出歌词高频得多。右侧 ✓ 即"已开启"，与 OptionRow 的规范一致（不带箭头）。
            OptionRow(
                icon = AppIcons.Lyrics,
                title = "桌面悬浮歌词",
                subtitle = when {
                    lyricOverlay -> "已开启 · 退到桌面 / 导航页后显示在屏幕最上层"
                    !canDrawOverlay -> "需要系统「显示悬浮窗」授权，点击后自动跳转"
                    else -> "在桌面最上层显示当前歌词行（App 内自动隐藏）"
                },
                selected = lyricOverlay,
                onClick = onToggleLyricOverlay,
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
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "当前范围",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

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
