package com.luckylearning.windtone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.discForGrouping
import com.luckylearning.windtone.data.shouldShowDiscHeaders
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.VirtualPlaylist
import com.luckylearning.windtone.state.virtualPlaylistInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.luckylearning.windtone.R

/**
 * 曲库与歌单的**列表区**（原来长在 `MainScreen.kt` 里）。
 *
 * 拆出来的原因：这些 composable 一共 800 多行，却和「主界面的状态编排、权限申请、
 * 各种弹窗」毫无关系——它们只吃 `LibraryUiState` / `PlayerUiState` 和几个回调。
 * 放在一起会让 MainScreen 涨到 1600 行，加一个多选态都要在几百行里翻找。
 *
 * 迁移原则：**只搬 composable，不搬逻辑**。状态仍由四个 ViewModel 提供，
 * 这里全是无状态的展示组件 + 回调。
 */

// ================================================================ 歌曲列表（含字母索引）

/**
 * 曲库分页渲染的每页条数（P2-1）。
 *
 * 大库里一次把上万首塞进 LazyColumn 并不会全部组合，但列表项数量会影响
 * 滚动测量与「回到当前播放 / 字母索引」的定位成本。首屏只铺 200 首，
 * 滚到底再追加一页；跳远时先把窗口撑到目标下标。
 */
private const val SONG_PAGE_SIZE = 200

@Composable
internal fun SongsList(
    playerVm: PlayerViewModel,
    library: LibraryUiState,
    player: PlayerUiState,
    granted: Boolean,
    onSongLongPress: (Song) -> Unit,
    onEnterSelection: (Song) -> Unit,
    onToggleSelect: (Song) -> Unit,
    onImportFolder: () -> Unit,
    onFullScan: () -> Unit,
    onOpenScanScope: () -> Unit,
    /**
     * 车载模式：行高与字号放大（封面 64dp / 标题 22sp），并**关闭长按多选**。
     *
     * 车机触摸板误触率高，「长按进多选」在车上会变成随机触发；批量操作留给手机。
     * 单曲的「更多」仍然在行尾 ⋮ 上。
     */
    carMode: Boolean = false,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // P2-1：分页窗口。列表内容 / 视图变化时重置到第一页。
    var renderedCount by rememberSaveable(library.view, library.visible.size) {
        mutableIntStateOf(minOf(SONG_PAGE_SIZE, library.visible.size))
    }
    val renderedSongs = remember(library.visible, renderedCount) {
        library.visible.take(renderedCount)
    }

    if (library.visible.isEmpty()) {
        val emptyLibrary = library.songs.isEmpty()
        if (emptyLibrary) {
            LibraryEmptyHint(
                scanScope = library.scanScope,
                granted = granted,
                onImportFolder = onImportFolder,
                onFullScan = onFullScan,
                onOpenScanScope = onOpenScanScope,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            EmptyHint(
                title = stringResource(R.string.lib_lists_empty_search_title),
                subtitle = stringResource(R.string.lib_lists_empty_search_body),
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    // 当前播放曲目在列表中的位置（缓存计算结果，避免每次重组都扫一遍列表）
    val currentIndex = remember(library.visible, player.current?.stableKey) {
        val key = player.current?.stableKey ?: return@remember -1
        library.visible.indexOfFirst { it.stableKey == key }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            itemsIndexed(
                items = renderedSongs,
                key = { _, item -> item.stableKey },
                contentType = { _, _ -> "song" },
            ) { index, song ->
                val selected = library.selecting && song.stableKey in library.selection
                SongRow(
                    song = song,
                    isCurrent = player.current?.stableKey == song.stableKey,
                    isPlaying = player.isPlaying,
                    large = carMode,
                    // 多选态下点行 = 勾选，不再触发播放（误播一整张列表很恼人）
                    onClick = {
                        if (library.selecting) onToggleSelect(song)
                        else playerVm.playQueue(library.visible, index)
                    },
                    // 长按 = **进入多选并选中这一首**（列表类界面的通用手势），
                    // 单曲的"更多"动作仍然在右侧的 ⋯ 里，两条路各司其职。
                    // 注意：多选态的入口只有这一处（外加全选/反选），
                    // 改动前务必确认 `LibraryViewModel.enterSelection` 仍有调用者——
                    // 它一旦没人调，`selecting` 永远是 false，整个批量操作就此"隐身"。
                    // 车载模式不认长按（见 carMode 注释）
                    onLongClick = if (carMode) {
                        null
                    } else {
                        {
                            if (library.selecting) onToggleSelect(song) else onEnterSelection(song)
                        }
                    },
                    onMore = if (library.selecting) null else ({ onSongLongPress(song) }),
                    selected = if (library.selecting) selected else null,
                )
            }

            // P2-1：滚到分页窗口末尾时自动追加一页（footer 被组合即触发）
            if (renderedCount < library.visible.size) {
                item(key = "__song_load_more__") {
                    LaunchedEffect(renderedCount, library.visible.size) {
                        renderedCount = minOf(renderedCount + SONG_PAGE_SIZE, library.visible.size)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.lib_lists_loading_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 字母索引导航条（车载模式不画：9sp 的小字在车上点不中，靠 Tab 与搜索更快）
        val letters = library.letterIndex.keys.toList()
        if (!carMode && letters.size > 4) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
            ) {
                letters.forEach { letter ->
                    Text(
                        text = letter.toString(),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                library.letterIndex[letter]?.let { idx ->
                                    // 目标可能在尚未渲染的后续页：先把窗口撑到它之后
                                    renderedCount = maxOf(
                                        renderedCount,
                                        (idx + SONG_PAGE_SIZE).coerceAtMost(library.visible.size),
                                    )
                                    scope.launch {
                                        yield()
                                        listState.scrollToItem(idx.coerceIn(0, library.visible.size - 1))
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }

        // 正在播放的歌曲滚出屏幕后，一键跳回（多选态下让位给底部操作栏）
        if (currentIndex > 0 && !library.selecting) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .clip(CircleShape)
                    .clickable {
                        scope.launch {
                            if (currentIndex >= renderedCount) {
                                renderedCount = minOf(
                                    currentIndex + SONG_PAGE_SIZE,
                                    library.visible.size,
                                )
                                yield()
                            }
                            listState.animateScrollToItem(currentIndex.firstVisibleSafe())
                        }
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.lib_lists_jump_current),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** 让「回到当前播放」上移一点，避免贴在最顶部 */
internal fun Int.firstVisibleSafe(): Int = (this - 3).coerceAtLeast(0)

// ================================================================ 分组列表（专辑/歌手/文件夹）

@Composable
internal fun GroupListView(
    library: LibraryUiState,
    onOpen: (GroupInfo) -> Unit,
    canRemove: Boolean = false,
    onRemove: (GroupInfo) -> Unit = {},
    /** 车载模式：分组行同样放大（封面 64dp、标题 22sp、触控目标 56dp） */
    carMode: Boolean = false,
    granted: Boolean = true,
    onImportFolder: () -> Unit = {},
    onFullScan: () -> Unit = {},
    onOpenScanScope: () -> Unit = {},
) {
    if (library.groups.isEmpty()) {
        if (library.songs.isEmpty()) {
            LibraryEmptyHint(
                scanScope = library.scanScope,
                granted = granted,
                onImportFolder = onImportFolder,
                onFullScan = onFullScan,
                onOpenScanScope = onOpenScanScope,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            EmptyHint(
                stringResource(R.string.lib_lists_group_empty_title),
                stringResource(R.string.lib_lists_group_empty_body),
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(library.groups, key = { it.key }, contentType = { "group" }) { group ->
            var showMenu by remember { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen(group) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(if (carMode) 14.dp else 10.dp),
                ) {
                    CoverArt(
                        song = group.sample,
                        size = if (carMode) 64.dp else 52.dp,
                        cornerRadius = if (carMode) 14.dp else 12.dp,
                    )
                    Spacer(Modifier.width(if (carMode) 18.dp else 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            group.title,
                            style = if (carMode) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            group.subtitle.ifBlank { pluralStringResource(R.plurals.lib_song_count, group.count, group.count) },
                            style = if (carMode) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CountPill("${group.count}")
                    if (canRemove) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cd_more_actions),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lib_lists_remove_folder)) },
                                    onClick = {
                                        showMenu = false
                                        onRemove(group)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ 分组详情

@Composable
internal fun GroupDetail(
    playerVm: PlayerViewModel,
    library: LibraryUiState,
    player: PlayerUiState,
    onBack: () -> Unit,
    onSongLongPress: (Song) -> Unit,
    /** 车载模式：详情里的歌曲行同样放大（与一级列表一致） */
    carMode: Boolean = false,
) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = library.selectedGroup?.title.orEmpty(),
            subtitle = pluralStringResource(R.plurals.lib_song_count, library.detailSongs.size, library.detailSongs.size),
            onBack = onBack,
            onPlayAll = { playerVm.playQueue(library.detailSongs, 0) },
        )
        val detail = library.detailSongs
        val showDiscs = shouldShowDiscHeaders(detail)
        LazyColumn(
            contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!showDiscs) {
                itemsIndexed(
                    detail,
                    key = { _, s -> s.stableKey },
                    contentType = { _, _ -> "song" },
                ) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = player.current?.stableKey == song.stableKey,
                        isPlaying = player.isPlaying,
                        large = carMode,
                        onClick = { playerVm.playQueue(detail, index) },
                        onLongClick = { onSongLongPress(song) },
                        onMore = { onSongLongPress(song) },
                    )
                }
            } else {
                // 多碟：按碟分区；播放索引仍对完整 detail 列表
                val byDisc = detail.groupBy { it.discForGrouping() }.toSortedMap()
                byDisc.forEach { (disc, songs) ->
                    item(key = "disc-$disc", contentType = "disc-header") {
                        DiscSectionHeader(disc = disc, carMode = carMode)
                    }
                    itemsIndexed(
                        songs,
                        key = { _, s -> s.stableKey },
                        contentType = { _, _ -> "song" },
                    ) { _, song ->
                        val index = detail.indexOfFirst { it.stableKey == song.stableKey }
                            .coerceAtLeast(0)
                        SongRow(
                            song = song,
                            isCurrent = player.current?.stableKey == song.stableKey,
                            isPlaying = player.isPlaying,
                            large = carMode,
                            onClick = { playerVm.playQueue(detail, index) },
                            onLongClick = { onSongLongPress(song) },
                            onMore = { onSongLongPress(song) },
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun DiscSectionHeader(disc: Int, carMode: Boolean) {
    val padV = if (carMode) 10.dp else 6.dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.lib_disc_n, disc),
            style = if (carMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = padV),
        )
    }
}

// ================================================================ 歌单列表

@Composable
internal fun PlaylistListView(
    lib: LibraryViewModel,
    library: LibraryUiState,
    onImportM3U: () -> Unit,
    granted: Boolean = true,
    onImportFolder: () -> Unit = {},
    onFullScan: () -> Unit = {},
    onOpenScanScope: () -> Unit = {},
) {
    // 空库：整页主 CTA，不展示 0 首的虚拟歌单壳子
    if (library.songs.isEmpty()) {
        LibraryEmptyHint(
            scanScope = library.scanScope,
            granted = granted,
            onImportFolder = onImportFolder,
            onFullScan = onFullScan,
            onOpenScanScope = onOpenScanScope,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.pl_my_playlists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImportM3U) { Text(stringResource(R.string.pl_import_m3u)) }
            }
        }
        item {
            PlaylistRowPlay(
                info = virtualPlaylistInfo(VirtualPlaylist.FAVORITES, library),
                onOpen = lib::openPlaylist,
            )
        }
        item {
            PlaylistRowPlay(
                info = virtualPlaylistInfo(VirtualPlaylist.RECENT, library),
                onOpen = lib::openPlaylist,
            )
        }
        item {
            PlaylistRowPlay(
                info = virtualPlaylistInfo(VirtualPlaylist.RECENTLY_ADDED, library),
                onOpen = lib::openPlaylist,
            )
        }
        item {
            PlaylistRowPlay(
                info = virtualPlaylistInfo(VirtualPlaylist.MOST_PLAYED, library),
                onOpen = lib::openPlaylist,
            )
        }
        if (library.playlists.isEmpty()) {
            item {
                EmptyHint(stringResource(R.string.pl_empty_title), stringResource(R.string.pl_empty_body), modifier = Modifier.padding(vertical = 30.dp))
            }
        }
        items(library.playlists, key = { it.id }, contentType = { "playlist" }) { pl ->
            PlaylistRowPlay(info = pl, onOpen = lib::openPlaylist)
        }
    }
}

@Composable
private fun PlaylistRowPlay(
    info: PlaylistInfo,
    onOpen: (PlaylistInfo) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onOpen(info) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            PlaylistCoverArt(songs = info.coverSongs, size = 52.dp, cornerRadius = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.lib_song_count, info.songCount, info.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            CountPill("${info.songCount}")
        }
    }
}

// ================================================================ 歌单详情（可拖动排序）

@Composable
internal fun PlaylistDetail(
    lib: LibraryViewModel,
    playerVm: PlayerViewModel,
    library: LibraryUiState,
    player: PlayerUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val info = library.selectedPlaylist ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(info.name) }

    val menuSlot: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.pl_cd_playlist_actions),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            PlaylistActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onRename = { renameDialog = true },
                onDelete = { lib.deletePlaylist(info.id) },
                onExport = onExport,
                onDedupe = { lib.dedupePlaylist(info.id) },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = info.name,
            subtitle = if (info.virtual) {
                pluralStringResource(R.plurals.lib_song_count, library.playlistSongs.size, library.playlistSongs.size)
            } else {
                stringResource(R.string.pl_detail_reorder_hint, library.playlistSongs.size)
            },
            onBack = onBack,
            onPlayAll = { playerVm.playQueue(library.playlistSongs, 0) },
            playAllEnabled = library.playlistSongs.isEmpty().not(),
            trailing = if (info.virtual) null else menuSlot,
        )

        if (library.playlistSongs.isEmpty()) {
            EmptyHint(
                if (info.virtual) stringResource(R.string.pl_empty_virtual_title) else stringResource(R.string.pl_empty_user_title),
                if (info.virtual) stringResource(R.string.pl_empty_virtual_body)
                else stringResource(R.string.pl_empty_user_body),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (info.virtual) {
            // 虚拟歌单（最近/最常/收藏）由统计与收藏实时生成，顺序不属于用户数据，不提供拖动
            LazyColumn(
                contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    library.playlistSongs,
                    key = { _, s -> s.stableKey },
                    contentType = { _, _ -> "song" },
                ) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = player.current?.stableKey == song.stableKey,
                        isPlaying = player.isPlaying,
                        onClick = { playerVm.playQueue(library.playlistSongs, index) },
                    )
                }
            }
        } else {
            ReorderablePlaylist(
                songs = library.playlistSongs,
                currentKey = player.current?.stableKey,
                isPlaying = player.isPlaying,
                onPlay = { index -> playerVm.playQueue(library.playlistSongs, index) },
                onRemove = { song -> lib.removeFromPlaylist(info.id, song) },
                onCommit = { ordered -> lib.reorderPlaylist(info.id, ordered) },
            )
        }
    }

    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text(stringResource(R.string.pl_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text(stringResource(R.string.pl_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) lib.renamePlaylist(info.id, renameText)
                        renameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text(stringResource(R.string.pl_confirm)) }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * 可拖动排序的歌单列表。
 *
 * 实现要点（长按即拖，松手落盘）：
 *  - 拖动中的行用 `graphicsLayer.translationY` 跟随手指，**不吃 LazyColumn 的布局**，
 *    所以不会引发整列表重排（否则每帧都要重组几十行）；
 *  - 位移超过半行高就与相邻行交换位置（每次只换一格，避免快速拖动时跳过好几行）；
 *  - 交换发生在内存副本上，手指抬起时才把新顺序一次性写库（`reorder`），
 *    拖动过程中不产生任何 IO；
 *  - 行高从 `layoutInfo` 里取当前可见项的真实高度，不写死 dp——字体缩放 / 车机大行高都不会错位。
 */
@Composable
private fun ReorderablePlaylist(
    songs: List<Song>,
    currentKey: String?,
    isPlaying: Boolean,
    onPlay: (Int) -> Unit,
    onRemove: (Song) -> Unit,
    onCommit: (List<Long>) -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // 内存副本：拖动期间界面读它，松手后写库
    val order = remember(songs) { mutableStateListOf<Song>().also { it.addAll(songs) } }
    LaunchedEffect(songs) {
        // 外部数据变了（加歌 / 移除 / 重命名后重查）→ 重建副本
        if (order.map { it.stableKey } != songs.map { it.stableKey }) {
            order.clear()
            order.addAll(songs)
        }
    }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var draggingIndex by remember { mutableIntStateOf(-1) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(order.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                            offset.y.toInt() in info.offset..(info.offset + info.size)
                        } ?: return@detectDragGesturesAfterLongPress
                        draggingIndex = item.index
                        draggingKey = order.getOrNull(item.index)?.stableKey
                        dragOffset = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, drag ->
                        val from = draggingIndex
                        if (from < 0) return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragOffset += drag.y
                        val rowHeight = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == from }?.size
                            ?.toFloat()
                            ?.takeIf { it > 0f }
                            ?: with(density) { 72.dp.toPx() }
                        while (dragOffset > rowHeight / 2f && from < order.size - 1) {
                            order.add(from + 1, order.removeAt(from))
                            draggingIndex = from + 1
                            dragOffset -= rowHeight
                        }
                        while (dragOffset < -rowHeight / 2f && from > 0) {
                            order.add(from - 1, order.removeAt(from))
                            draggingIndex = from - 1
                            dragOffset += rowHeight
                        }
                    },
                    onDragEnd = {
                        if (draggingIndex >= 0) {
                            onCommit(order.mapNotNull { it.dbId.takeIf { id -> id != 0L } })
                        }
                        draggingKey = null
                        draggingIndex = -1
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        draggingKey = null
                        draggingIndex = -1
                        dragOffset = 0f
                    },
                )
            },
    ) {
        itemsIndexed(order, key = { _, s -> s.stableKey }, contentType = { _, _ -> "song" }) { index, song ->
            val dragging = song.stableKey == draggingKey
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (dragging) {
                            translationY = dragOffset
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    },
            ) {
                Box(Modifier.weight(1f)) {
                    SongRow(
                        song = song,
                        isCurrent = currentKey == song.stableKey,
                        isPlaying = isPlaying,
                        onClick = { if (draggingIndex < 0) onPlay(index) },
                        onRemove = { onRemove(song) },
                    )
                }
                Icon(
                    imageVector = AppIcons.DragHandle,
                    contentDescription = stringResource(R.string.cd_drag_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(22.dp),
                )
            }
        }
    }
}

// ================================================================ 详情页共用标题栏

/** 详情页（分组 / 歌单）共用的标题栏 */
@Composable
internal fun DetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    playAllEnabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onPlayAll, enabled = playAllEnabled) { Text(stringResource(R.string.play_all)) }
        trailing?.invoke()
    }
}
