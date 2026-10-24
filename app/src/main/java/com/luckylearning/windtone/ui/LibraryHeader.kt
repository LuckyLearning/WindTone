package com.luckylearning.windtone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.LibraryView

/**
 * 曲库页的**顶栏与空态**（原来长在 `MainScreen.kt` 里，见 `LibraryLists.kt` 的说明）。
 */

/** 抽屉导航用的视图图标（v1.1.3 起底部五视图导航已改为侧边抽屉） */
internal val LibraryView.icon: ImageVector
    get() = when (this) {
        LibraryView.SONGS -> AppIcons.MusicNote
        LibraryView.ALBUM -> AppIcons.Album
        LibraryView.ARTIST -> AppIcons.Artist
        LibraryView.FOLDER -> AppIcons.Folder
        LibraryView.PLAYLIST -> AppIcons.LibraryMusic
    }

/**
 * 各浏览视图**默认的**排序方式。
 *
 * 用在两处：首次进入某视图、以及用户点「↺ 恢复默认」时。
 * 默认值与视图的直觉对齐 —— 歌曲 / 文件夹按名称，专辑按专辑名，歌手按歌手名，
 * 歌单按 M3U 里写死的顺序（用户自己拖出来的那个顺序）。
 */
internal val LibraryView.defaultSort: SortOrder
    get() = when (this) {
        LibraryView.SONGS -> SortOrder.TITLE
        LibraryView.ALBUM -> SortOrder.ALBUM
        LibraryView.ARTIST -> SortOrder.ARTIST
        LibraryView.FOLDER -> SortOrder.TITLE
        LibraryView.PLAYLIST -> SortOrder.PLAYLIST_ORDER
    }

/**
 * 该视图**能用**的排序项（v1.1.4 排序下沉：「功能按实际情况来」）。
 *
 * 每个视图只列在自己身上讲得通的项 —— 专辑列表给「按时长」没有意义，
 * 歌单列表给「按专辑」也没有意义。列全了只会让菜单变长、还容易选错。
 */
internal val LibraryView.sortOptions: List<SortOrder>
    get() = when (this) {
        LibraryView.SONGS -> listOf(
            SortOrder.TITLE,
            SortOrder.ARTIST,
            SortOrder.ALBUM,
            SortOrder.DURATION,
            SortOrder.DATE_ADDED,
            SortOrder.FILE_NAME,
        )
        LibraryView.ALBUM -> listOf(SortOrder.ALBUM, SortOrder.ARTIST, SortOrder.DATE_ADDED)
        LibraryView.ARTIST -> listOf(SortOrder.ARTIST, SortOrder.DATE_ADDED)
        LibraryView.FOLDER -> listOf(SortOrder.TITLE, SortOrder.FOLDER_PATH, SortOrder.DATE_ADDED)
        LibraryView.PLAYLIST -> listOf(SortOrder.PLAYLIST_ORDER, SortOrder.TITLE, SortOrder.DATE_ADDED)
    }

/* 排序项在某个视图下的显示名见 `UiText.kt` 的 `SortOrder.displayName(view)`（i18n：
   枚举不持有界面文案）。 */

@Composable
internal fun LibraryHeader(
    library: LibraryUiState,
    granted: Boolean,
    /** 打开左侧抽屉（v1.1.3 起取代底部五视图导航，见 `LibraryDrawer.kt`） */
    onOpenDrawer: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onRequestPermission: () -> Unit,
    onImportFolder: () -> Unit,
    onRescan: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onDeepScan: () -> Unit,
    onImportM3U: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onOpenScanScope: () -> Unit,
    onFullScan: () -> Unit,
    onOpenReport: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 汉堡按钮：抽屉的唯一入口，放在标题之前（效果图 ①C 的 `.tb-row` 顺序
            // = 汉堡 → 库标题 → 首数胶囊 → 弹性空隙 → 搜索 / 排序 / 更多）。
            // 它出现在**所有**曲库状态下（首次进入、扫描中、搜索态都渲染本组件），
            // 所以「扫描中也能拉开抽屉切视图」是天然成立的。
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = AppIcons.Menu,
                    contentDescription = stringResource(R.string.cd_open_drawer),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.lib_title_local_music),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (library.songs.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                CountPill(
                    pluralStringResource(
                        R.plurals.lib_song_count,
                        library.songs.size,
                        library.songs.size,
                    )
                )
            }
            Spacer(Modifier.weight(1f))

            IconButton(onClick = { searchMode = !searchMode }) {
                Icon(
                    imageVector = if (searchMode) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = stringResource(
                        if (searchMode) R.string.cd_close_search else R.string.cd_search
                    ),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(
                        imageVector = AppIcons.Sort,
                        contentDescription = stringResource(R.string.cd_sort),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                // 排序菜单只列**当前视图**讲得通的项，且选中的排序只作用于当前视图
                // （v1.1.4 排序下沉：歌曲选按标题、专辑选按专辑名，互不影响）。
                // 文案也跟着视图变，如文件夹视图下 TITLE 显示成「按文件夹名」。
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    library.view.sortOptions.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = order.displayName(library.view),
                                    fontWeight = if (order == library.sortOrder) FontWeight.Bold
                                    else FontWeight.Normal,
                                    color = if (order == library.sortOrder) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { onSort(order); sortMenu = false },
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    // 这里只放「就地动作」（扫描 / 导入 / 重匹配 / 收听报告），**不放跳转项**：
                    // 「设置」只有左侧抽屉一个入口，「检查更新」只在设置 → 更新里 ——
                    // 2026-10-17 收的口子（原先本菜单与抽屉各挂一份，同一个目的地三处入口）。
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
                        onClick = { moreMenu = false; onOpenScanScope() },
                    )
                    if (library.scanScope != ScanScope.UNSET) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lib_rescan_current)) },
                            onClick = { moreMenu = false; onRescan() },
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
                            onClick = { moreMenu = false; onFullScan() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_import_folder)) },
                        onClick = { moreMenu = false; onImportFolder() },
                    )
                    if (library.scanScope != ScanScope.FULL && !granted) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lib_grant_audio_permission)) },
                            onClick = { moreMenu = false; onRequestPermission() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_rematch_lyrics)) },
                        onClick = { moreMenu = false; onRefreshLyrics() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_import_m3u)) },
                        onClick = { moreMenu = false; onImportM3U() },
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
                        onClick = { moreMenu = false; onDeepScan() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lib_listening_report)) },
                        onClick = { moreMenu = false; onOpenReport() },
                    )
                }
            }
        }

        if (searchMode) {
            OutlinedTextField(
                value = library.query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(library.query) }),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (library.query.isBlank() && library.searchHistory.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.lib_search_history),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        library.searchHistory.forEach { h ->
                            AssistChip(
                                onClick = {
                                    onQuery(h)
                                    onSubmitSearch(h)
                                },
                                label = { Text(h, fontSize = 12.sp) },
                            )
                        }
                    }
                    TextButton(onClick = onClearSearchHistory) {
                        Text(stringResource(R.string.common_clear), fontSize = 12.sp)
                    }
                }
            }
        }

        // stringResource / pluralStringResource 都是 @Composable，不能放进 buildString 的
        // 非组合 lambda 里，先在此处求值再拼接。
        val totalText = pluralStringResource(
            R.plurals.lib_stat_total,
            library.songs.size,
            library.songs.size,
        )
        val lyricsText = if (library.lyricsCount > 0) {
            pluralStringResource(R.plurals.lib_stat_lyrics, library.lyricsCount, library.lyricsCount)
        } else ""
        val skippedText = if (library.skippedUnsupported > 0) {
            pluralStringResource(
                R.plurals.lib_stat_skipped,
                library.skippedUnsupported,
                library.skippedUnsupported,
            )
        } else ""
        val stat = buildString {
            append(totalText)
            if (lyricsText.isNotEmpty()) append(" · ").append(lyricsText)
            if (skippedText.isNotEmpty()) append(" · ").append(skippedText)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        ) {
            ScanScopeChip(
                scope = library.scanScope,
                folderCount = library.folderCount,
                onClick = onOpenScanScope,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stat,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ================================================================ 首次进入：选择扫描范围

/**
 * 首次进入的空态。
 *
 * 应用**默认不扫描整个手机**，由用户明确选择：
 *  - 扫描全盘（读系统媒体库，可配合「所有文件访问权限」做深度扫描）
 *  - 指定目录（用系统文件选择器挑文件夹）
 */
@Composable
internal fun ScanSetupView(
    scanning: Boolean,
    statusText: String,
    onFullScan: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.primaryContainer,
            modifier = Modifier.size(84.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.lib_setup_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.lib_setup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onFullScan,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(AppIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lib_scan_full))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.lib_scan_full_desc),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onPickFolder,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lib_pick_folder))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.lib_pick_folder_desc),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
        )

        if (scanning) {
            // ifBlank 的 lambda 不是组合上下文，stringResource 必须先求值。
            val scanningLabel = stringResource(R.string.lib_scanning)
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText.ifBlank { scanningLabel },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ================================================================ 扫描中

@Composable
internal fun ScanningState(status: String) {
    Column(Modifier.fillMaxSize()) {
        // 同上：ifBlank 的 lambda 不是组合上下文。
        val scanningLabel = stringResource(R.string.lib_scanning_library)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = status.ifBlank { scanningLabel },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LibrarySkeleton()
    }
}
