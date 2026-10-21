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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.state.FolderItem
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 扫描范围选择（底部弹层 + 顶栏小芯片）。
 *
 * 从 Sheets.kt 按「页面」拆出（v1.1.37）。[ScanScopeChip] 被 LibraryHeader 调用，保持 public。
 */

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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
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
