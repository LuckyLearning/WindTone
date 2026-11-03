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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.state.FolderItem
import com.luckylearning.windtone.ui.theme.glassContainerColor

/**
 * 扫描范围选择（底部弹层 + 顶栏小芯片）。
 *
 * 自 Sheets.kt 按「页面」拆出（v1.1.37）。[ScanScopeChip] 被 LibraryHeader 调用，保持 public。
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
    AppSheet(onDismissRequest = onDismiss, containerColor = glassContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.drawer_scan_scope),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.ssc_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionLabel(stringResource(R.string.ssc_section_full))
            OptionRow(
                icon = AppIcons.MusicNote,
                title = stringResource(R.string.lib_scan_full),
                subtitle = stringResource(R.string.ssc_full_subtitle),
                selected = current == ScanScope.FULL,
                onClick = onFullScan,
            )

            SectionLabel(stringResource(R.string.ssc_section_folders))
            OptionRow(
                icon = AppIcons.Folder,
                title = if (activeCount > 0) {
                    stringResource(R.string.ssc_use_imported)
                } else {
                    stringResource(R.string.ssc_pick_folder)
                },
                subtitle = if (activeCount > 0) {
                    stringResource(R.string.ssc_active_folders, activeCount)
                } else if (folderCount > 0) {
                    stringResource(R.string.ssc_all_excluded)
                } else {
                    stringResource(R.string.ssc_pick_via_picker)
                },
                selected = current == ScanScope.FOLDERS && activeCount > 0,
                onClick = { if (activeCount > 0) onUseFolders() else onPickFolder() },
            )
            OptionRow(
                icon = AppIcons.Folder,
                title = stringResource(R.string.ssc_import_more),
                subtitle = stringResource(R.string.ssc_import_more_sub),
                onClick = onPickFolder,
            )

            if (folderCount > 0) {
                SectionLabel(stringResource(R.string.ssc_section_imported))
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
                            val status = if (item.excluded) {
                                stringResource(R.string.ssc_excluded)
                            } else {
                                pluralStringResource(
                                    R.plurals.lib_song_count,
                                    item.songCount,
                                    item.songCount,
                                )
                            }
                            val meta = if (item.fullPath.isNotEmpty()) {
                                stringResource(R.string.ssc_folder_meta, item.fullPath, status)
                            } else {
                                status
                            }
                            Text(
                                meta,
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
                                contentDescription = stringResource(R.string.ssc_cd_remove_folder),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.ssc_exclude_hint),
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
        ScanScope.UNSET -> stringResource(R.string.ssc_chip_unset)
        ScanScope.FULL -> stringResource(R.string.ssc_chip_full)
        ScanScope.FOLDERS -> if (folderCount > 0) {
            stringResource(R.string.ssc_chip_folders_n, folderCount)
        } else {
            stringResource(R.string.ssc_chip_folders)
        }
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
