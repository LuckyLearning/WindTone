package com.luckylearning.windtone.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.state.LibraryUiState

/**
 * 曲库头部「⋮ 工具」的**唯一实现**（UI v2 · 阶段四）。
 *
 * 重构前这段菜单内联在 [LibraryHeader] 里，车机头部 `CarLibraryHeader` 只有
 * 「标题 + 曲数 + 搜索」，⋮ 里的 9 个就地动作（扫描范围 / 重扫 / 全盘扫描 /
 * 导入目录 / 权限 / 重匹配歌词 / 导入 M3U / 深度扫描 / 收听报告）在车机上
 * **整块消失**——典型的功能按端裁剪。
 *
 * 现在抽成同一个 Composable + 同一份 [LibraryToolsActions]：手机挂在顶栏 ⋮，
 * 车机挂在自己头部右侧的 ⋮，两端看到的是同一批动作、同一份文案。
 * 差异只剩「按钮在哪、多大」，由各呈现态自己决定。
 */
internal data class LibraryToolsActions(
    val onOpenScanScope: () -> Unit,
    val onRescan: () -> Unit,
    val onFullScan: () -> Unit,
    val onImportFolder: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onRefreshLyrics: () -> Unit,
    val onImportM3U: () -> Unit,
    val onDeepScan: () -> Unit,
    val onOpenReport: () -> Unit,
)

@Composable
internal fun LibraryToolsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    library: LibraryUiState,
    granted: Boolean,
    actions: LibraryToolsActions,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier) {
        // 只放「就地动作」，不放跳转项：「设置」只有左侧导航一个入口、
        // 「检查更新」只在设置 → 更新里（2026-10-17 收的口子）。
        DropdownMenuItem(
            text = {
                Text(
                    when (library.scanScope) {
                        ScanScope.UNSET -> stringResource(R.string.lib_scan_scope_unset)
                        ScanScope.FULL -> stringResource(R.string.lib_scan_scope_full)
                        ScanScope.FOLDERS -> stringResource(
                            R.string.lib_scan_scope_folders,
                            library.folderCount,
                        )
                    }
                )
            },
            onClick = { onDismiss(); actions.onOpenScanScope() },
        )
        if (library.scanScope != ScanScope.UNSET) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_rescan_current)) },
                onClick = { onDismiss(); actions.onRescan() },
            )
        }
        if (library.scanScope != ScanScope.FULL) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (granted) R.string.lib_scan_full_deep
                            else R.string.lib_scan_full_grant
                        )
                    )
                },
                onClick = { onDismiss(); actions.onFullScan() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lib_import_folder)) },
            onClick = { onDismiss(); actions.onImportFolder() },
        )
        if (library.scanScope != ScanScope.FULL && !granted) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lib_grant_audio_permission)) },
                onClick = { onDismiss(); actions.onRequestPermission() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lib_rematch_lyrics)) },
            onClick = { onDismiss(); actions.onRefreshLyrics() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lib_import_m3u)) },
            onClick = { onDismiss(); actions.onImportM3U() },
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (library.deepScan) R.string.lib_deep_scan_on
                        else R.string.lib_deep_scan_off
                    )
                )
            },
            onClick = { onDismiss(); actions.onDeepScan() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lib_listening_report)) },
            onClick = { onDismiss(); actions.onOpenReport() },
        )
    }
}
