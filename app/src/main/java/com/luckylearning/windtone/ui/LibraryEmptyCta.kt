package com.luckylearning.windtone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.ScanScope

/**
 * 曲库空态主按钮（#13）的主行动种类。
 * 对齐真实扫描 / 授权入口，不造假入口。
 */
enum class LibraryEmptyPrimaryAction {
    /** 指定目录模式 → 添加 / 更换文件夹 */
    IMPORT_FOLDER,
    /** 全盘（或未指定但已授权）→ 扫描音乐 */
    FULL_SCAN,
    /** 全盘但缺读权限 → 授权并扫描 */
    GRANT_AND_SCAN,
}

/**
 * 根据扫描范围与授权态决定空库主 CTA。
 * - FOLDERS → 添加文件夹
 * - 未授权 → 授权并扫描
 * - 其余（FULL / UNSET 且已授权）→ 扫描音乐
 */
fun resolveLibraryEmptyCta(scanScope: ScanScope, granted: Boolean): LibraryEmptyPrimaryAction =
    when {
        scanScope == ScanScope.FOLDERS -> LibraryEmptyPrimaryAction.IMPORT_FOLDER
        !granted -> LibraryEmptyPrimaryAction.GRANT_AND_SCAN
        else -> LibraryEmptyPrimaryAction.FULL_SCAN
    }

/**
 * 空库统一主 CTA：歌曲 / 分组 / 歌单共用。
 * 有曲时调用方不应渲染本组件。
 */
@Composable
fun LibraryEmptyHint(
    scanScope: ScanScope,
    granted: Boolean,
    onImportFolder: () -> Unit,
    onFullScan: () -> Unit,
    onOpenScanScope: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderScope = scanScope == ScanScope.FOLDERS
    val action = resolveLibraryEmptyCta(scanScope, granted)
    val title = if (folderScope) {
        stringResource(R.string.lib_lists_empty_folder_title)
    } else {
        stringResource(R.string.lib_lists_empty_full_title)
    }
    val subtitle = when (action) {
        LibraryEmptyPrimaryAction.IMPORT_FOLDER ->
            stringResource(R.string.lib_lists_empty_folder_body)
        LibraryEmptyPrimaryAction.GRANT_AND_SCAN ->
            stringResource(R.string.lib_lists_empty_full_need_perm_body)
        LibraryEmptyPrimaryAction.FULL_SCAN ->
            stringResource(R.string.lib_lists_empty_full_body)
    }
    val actionLabel = when (action) {
        LibraryEmptyPrimaryAction.IMPORT_FOLDER ->
            stringResource(R.string.lib_lists_action_add_folder)
        LibraryEmptyPrimaryAction.GRANT_AND_SCAN ->
            stringResource(R.string.lib_lists_action_grant_scan)
        LibraryEmptyPrimaryAction.FULL_SCAN ->
            stringResource(R.string.lib_lists_action_scan_music)
    }
    val onAction: () -> Unit = when (action) {
        LibraryEmptyPrimaryAction.IMPORT_FOLDER -> onImportFolder
        LibraryEmptyPrimaryAction.GRANT_AND_SCAN,
        LibraryEmptyPrimaryAction.FULL_SCAN -> onFullScan
    }
    EmptyHint(
        title = title,
        subtitle = subtitle,
        actionLabel = actionLabel,
        onAction = onAction,
        secondaryActionLabel = stringResource(R.string.lib_lists_action_scan_scope),
        onSecondaryAction = onOpenScanScope,
        modifier = modifier,
    )
}
