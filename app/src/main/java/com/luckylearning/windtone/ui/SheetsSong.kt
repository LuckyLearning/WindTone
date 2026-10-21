@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luckylearning.windtone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 单曲相关的底部弹层与弹窗：长按操作、音频技术信息、编辑歌曲信息。
 *
 * 从 Sheets.kt 按「页面」拆出（v1.1.37）。[songDisplayPath] 只被本文件使用，故为 private。
 */

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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
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
