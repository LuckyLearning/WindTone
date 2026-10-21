@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luckylearning.windtone.ui

import com.luckylearning.windtone.state.PlaylistInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 歌单相关的弹层与弹窗：加入歌单、新建歌单、歌单详情菜单。
 *
 * 从 Sheets.kt 按「页面」拆出（v1.1.37）。
 */

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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
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
