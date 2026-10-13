package com.luckylearning.windtone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.flow.StateFlow

/**
 * 车机版主页：三栏式横屏布局（替代手机的「单列 + 底部导航」）。
 *
 * 屏幕基准：2560×1600 中控屏 → hdpi(240) 密度桶 → 约 1707×1067dp 可用空间。
 * 只有 `Prefs.carMode` 打开时才走这里，手机布局完全不受影响。
 *
 *  ┌──────────┬──────────────────────────┬────────────────┐
 *  │ 左侧导航  │ 中间列表                  │ 右侧「正在播放」 │
 *  │  256dp   │      自适应宽度            │     400dp      │
 *  └──────────┴──────────────────────────┴────────────────┘
 *
 * 相比手机布局的三点差异：
 *  1. 底部 NavigationBar 换成左侧纵向导航——横屏下纵向空间充足，侧栏更省纵向像素；
 *  2. 迷你播放条换成常驻的「正在播放」大卡片（大封面 + 大进度条 + 76dp 触控键）；
 *  3. 行高 / 字号 / 图标整体放大一档，保证行车中一眼可辨、一指点中。
 */
@Composable
fun CarHomeScreen(
    library: LibraryUiState,
    player: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    onSelectView: (LibraryView) -> Unit,
    onOpenSettings: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: () -> Unit,
    onRescan: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onOpenGroup: (GroupInfo) -> Unit,
    onOpenPlaylist: (PlaylistInfo) -> Unit,
    onCloseDetail: () -> Unit,
    onNewPlaylist: () -> Unit,
    onImportM3U: () -> Unit,
    onOpenScanScope: () -> Unit,
    onFullScan: () -> Unit,
    onImportFolder: () -> Unit,
    granted: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpandPlayer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 车机上弹软键盘体验差，搜索框做成「点按钮才展开」的两段式，平时不占位置
    var searchOpen by remember { mutableStateOf(false) }

    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // ------------------------------------------------------ 左侧导航
            CarNavRail(
                current = library.view,
                onSelect = onSelectView,
                onOpenSettings = onOpenSettings,
            )

            // ------------------------------------------------------ 中间列表
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 22.dp),
            ) {
                CarTopBar(
                    library = library,
                    searchOpen = searchOpen,
                    onToggleSearch = { searchOpen = !searchOpen },
                    onQuery = onQuery,
                    onSort = onSort,
                    onRescan = onRescan,
                )

                Spacer(Modifier.height(14.dp))

                Box(Modifier.weight(1f)) {
                    val detail = library.selectedPlaylist != null || library.selectedGroup != null
                    when {
                        // 首次进入 / 移除全部目录后：等待用户指定扫描范围
                        library.scanScope == ScanScope.UNSET && library.songs.isEmpty() ->
                            CarEmptyState(
                                title = "还没有音乐",
                                subtitle = "先扫描全盘，或只导入某个音乐目录。\n同目录的 .lrc / .krc 歌词会被自动识别。",
                                actions = listOf(
                                    "扫描全盘" to onFullScan,
                                    "导入目录" to onImportFolder,
                                    "扫描范围设置" to onOpenScanScope,
                                ),
                                primaryHint = if (granted) null else "首次全盘扫描会申请「读取音频」权限",
                            )

                        library.scanning && library.songs.isEmpty() ->
                            CarEmptyState(
                                title = "正在扫描…",
                                subtitle = library.statusText,
                                actions = emptyList(),
                            )

                        library.selectedPlaylist != null -> CarSongList(
                            songs = library.playlistSongs,
                            currentKey = player.current?.stableKey,
                            isPlaying = player.isPlaying,
                            emptyTitle = "这个歌单还是空的",
                            emptySubtitle = "在歌曲行尾点「⋮」即可加入歌单。",
                            onPlay = { i -> onPlay(library.playlistSongs, i) },
                            onLongPress = onSongLongPress,
                        )

                        library.selectedGroup != null -> CarSongList(
                            songs = library.detailSongs,
                            currentKey = player.current?.stableKey,
                            isPlaying = player.isPlaying,
                            emptyTitle = "这里没有歌曲",
                            emptySubtitle = null,
                            onPlay = { i -> onPlay(library.detailSongs, i) },
                            onLongPress = onSongLongPress,
                        )

                        library.view == LibraryView.SONGS -> CarSongList(
                            songs = library.visible,
                            currentKey = player.current?.stableKey,
                            isPlaying = player.isPlaying,
                            emptyTitle = if (library.songs.isEmpty()) "还没有音乐" else "没有匹配的结果",
                            emptySubtitle = if (library.songs.isEmpty()) {
                                "在左侧切到「扫描范围」选择全盘或指定目录。"
                            } else {
                                "换个关键词试试，支持拼音首字母（如 zjl）。"
                            },
                            onPlay = { i -> onPlay(library.visible, i) },
                            onLongPress = onSongLongPress,
                        )

                        library.view == LibraryView.PLAYLIST -> CarPlaylistList(
                            library = library,
                            onOpen = onOpenPlaylist,
                            onNewPlaylist = onNewPlaylist,
                            onImportM3U = onImportM3U,
                            onPlay = onPlay,
                        )

                        else -> CarGroupList(library = library, onOpen = onOpenGroup)
                    }

                    if (detail) {
                        CarDetailBar(
                            label = library.selectedPlaylist?.name
                                ?: library.selectedGroup?.title.orEmpty(),
                            count = if (library.selectedPlaylist != null) {
                                library.playlistSongs.size
                            } else {
                                library.detailSongs.size
                            },
                            onBack = onCloseDetail,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }

            // ------------------------------------------------- 右侧「正在播放」
            CarNowPlayingPane(
                player = player,
                progressFlow = progressFlow,
                onToggle = onToggle,
                onNext = onNext,
                onPrevious = onPrevious,
                onSeek = onSeek,
                onExpand = onExpandPlayer,
            )
        }
    }
}

// ================================================================ 左侧导航

/**
 * 左侧纵向导航：宽度 256dp，条目高 52dp、图标 26dp、文字 17sp。
 * 尺寸全部高于 Google 车机规范的 76dp 触控下限的组成部分（点击热区由条目本身 52dp
 * 加上上下 3dp 间距凑到 58dp，车机为固定视角操作，配合大图标可接受）。
 */
@Composable
private fun CarNavRail(
    current: LibraryView,
    onSelect: (LibraryView) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer, modifier = Modifier.width(256.dp).fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, bottom = 24.dp),
            ) {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "听风",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                )
            }

            LibraryView.entries.forEach { view ->
                CarNavItem(
                    label = view.label,
                    icon = view.carIcon,
                    selected = current == view,
                    onClick = { onSelect(view) },
                )
            }

            Spacer(Modifier.weight(1f))

            CarNavItem(
                label = "设置",
                icon = AppIcons.Tune,
                selected = false,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun CarNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    Box(Modifier.padding(vertical = 3.dp)) {
        Surface(
            color = if (selected) scheme.primary else scheme.surfaceContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    label,
                    fontSize = 17.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg,
                )
            }
        }
    }
}

/** 车机导航图标：与手机端一致，单独抽出来便于后续替换 */
private val LibraryView.carIcon: ImageVector
    get() = when (this) {
        LibraryView.SONGS -> AppIcons.MusicNote
        LibraryView.ALBUM -> AppIcons.Album
        LibraryView.ARTIST -> AppIcons.Artist
        LibraryView.FOLDER -> AppIcons.Folder
        LibraryView.PLAYLIST -> AppIcons.LibraryMusic
    }

/** 排序档位轮换到下一档（车机上用一个大按钮循环切换，比弹菜单省一次点击） */
internal fun SortOrder.next(): SortOrder {
    val all = SortOrder.entries
    return all[(all.indexOf(this) + 1) % all.size]
}

// ================================================================ 顶部工具条

@Composable
private fun CarTopBar(
    library: LibraryUiState,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: () -> Unit,
    onRescan: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Text(
            text = library.view.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Spacer(Modifier.width(14.dp))
        CarPill("${library.visible.size} 首")
        Spacer(Modifier.weight(1f))
        CarPillButton("重新扫描", onRescan)
        Spacer(Modifier.width(10.dp))
        CarPillButton(library.sortOrder.label, onSort)
        Spacer(Modifier.width(10.dp))
        CarPillButton(if (searchOpen) "收起搜索" else "搜索", onToggleSearch)
    }

    if (searchOpen) {
        Spacer(Modifier.height(14.dp))
        CarSearchField(
            value = library.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 车机版胶囊按钮：高 52dp，横向内边距 20dp，文字 16sp */
@Composable
private fun CarPillButton(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, color = scheme.onSurface)
        }
    }
}

/** 车机版计数徽标：比手机端大一号 */
@Composable
private fun CarPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** 车机版搜索框：单行 + 清除，输入交给系统输入法 / 车机语音 */
@Composable
private fun CarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(58.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp, end = 8.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "歌名 / 歌手 / 专辑 / 拼音首字母",
                            fontSize = 17.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(46.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "清除",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

// ================================================================ 歌曲列表

/** 车机歌曲列表：行高约 80dp、封面 64dp、标题 22sp，一屏约 9 行 */
@Composable
private fun CarSongList(
    songs: List<Song>,
    currentKey: String?,
    isPlaying: Boolean,
    emptyTitle: String,
    emptySubtitle: String?,
    onPlay: (Int) -> Unit,
    onLongPress: (Song) -> Unit,
) {
    if (songs.isEmpty()) {
        CarEmptyState(
            title = emptyTitle,
            subtitle = emptySubtitle,
            actions = emptyList(),
        )
        return
    }

    val listState = rememberLazyListState()
    // 换歌 / 换列表时把当前曲目滚进视野（首个组合只在列表变化时触发一次）
    LaunchedEffect(currentKey, songs) {
        val idx = songs.indexOfFirst { it.stableKey == currentKey }
        if (idx >= 0) runCatching { listState.animateScrollToItem(idx) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(songs, key = { _, s -> s.stableKey }) { index, song ->
            SongRow(
                song = song,
                isCurrent = song.stableKey == currentKey,
                isPlaying = isPlaying,
                onClick = { onPlay(index) },
                onLongClick = { onLongPress(song) },
                onMore = { onLongPress(song) },
                large = true,
            )
        }
    }
}

// ================================================================ 分组 / 歌单

@Composable
private fun CarGroupList(
    library: LibraryUiState,
    onOpen: (GroupInfo) -> Unit,
) {
    if (library.groups.isEmpty()) {
        CarEmptyState(
            title = "还没有分组",
            subtitle = "切换到「专辑 / 歌手 / 文件夹」会自动按维度归类。",
            actions = emptyList(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(library.groups, key = { it.key }) { group ->
            CarCardRow(
                title = group.title,
                subtitle = group.subtitle.ifBlank { "${group.count} 首" },
                onClick = { onOpen(group) },
                coverSong = group.sample,
            )
        }
    }
}

@Composable
private fun CarPlaylistList(
    library: LibraryUiState,
    onOpen: (PlaylistInfo) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportM3U: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        ) {
            CarPillButton("新建歌单", onNewPlaylist)
            Spacer(Modifier.width(10.dp))
            CarPillButton("导入 M3U", onImportM3U)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 两个固定虚拟歌单：最近播放 / 最常播放
            item {
                CarCardRow(
                    title = "最近播放",
                    subtitle = "${library.extraRecent.size} 首",
                    onClick = {
                        onOpen(
                            PlaylistInfo(
                                MusicViewModel.VIRTUAL_RECENT,
                                "最近播放",
                                library.extraRecent.size,
                                library.extraRecent.firstOrNull(),
                                virtual = true,
                            )
                        )
                    },
                    coverSong = library.extraRecent.firstOrNull(),
                )
            }
            item {
                CarCardRow(
                    title = "最常播放",
                    subtitle = "${library.extraMostPlayed.size} 首",
                    onClick = {
                        onOpen(
                            PlaylistInfo(
                                MusicViewModel.VIRTUAL_MOST,
                                "最常播放",
                                library.extraMostPlayed.size,
                                library.extraMostPlayed.firstOrNull(),
                                virtual = true,
                            )
                        )
                    },
                    coverSong = library.extraMostPlayed.firstOrNull(),
                )
            }
            if (library.playlists.isEmpty()) {
                item {
                    CarEmptyState(
                        title = "还没有自建歌单",
                        subtitle = "点上方「新建歌单」创建，或在手机端把歌曲加入歌单。",
                        actions = emptyList(),
                    )
                }
            }
            items(library.playlists, key = { it.id }) { pl ->
                CarCardRow(
                    title = pl.name,
                    subtitle = "${pl.songCount} 首",
                    onClick = { onOpen(pl) },
                    coverSong = pl.coverSong,
                )
            }
        }
    }
}

/** 车机版「卡片行」：专辑 / 歌手 / 文件夹 / 歌单四类共用 */
@Composable
private fun CarCardRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    coverSong: Song?,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (coverSong != null) {
                CoverArt(song = coverSong, size = 64.dp, cornerRadius = 14.dp)
            } else {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(scheme.primaryContainer, scheme.secondaryContainer)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.LibraryMusic,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 15.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}

// ================================================================ 右侧正在播放

/**
 * 车机常驻「正在播放」栏（400dp）。
 * 无歌曲时显示占位；有歌曲时展示大封面 + 歌名 + 进度 + 76dp 触控键。
 */
@Composable
private fun CarNowPlayingPane(
    player: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val song = player.current

    Surface(color = scheme.surfaceContainer, modifier = Modifier.width(400.dp).fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "正在播放",
                fontSize = 15.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            if (song == null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有在播放的歌曲\n\n在左侧列表点一首歌即可开始",
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // 封面容器：占剩余空间，正方形（widthIn 限制高度不超过宽度）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .shadow(16.dp, RoundedCornerShape(20.dp))
                            .clickable(onClick = onExpand),
                    ) {
                        CoverArt(
                            song = song,
                            size = 0.dp,
                            cornerRadius = 20.dp,
                            fillContainer = true,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    song.title,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    song.artistOrUnknown,
                    fontSize = 15.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                CarProgressBar(
                    progressFlow = progressFlow,
                    fallbackDurationMs = player.durationMs,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    CarRoundButton(
                        icon = AppIcons.SkipPrevious,
                        size = 76.dp,
                        iconSize = 34.dp,
                        container = scheme.surfaceContainerHighest,
                        tint = scheme.onSurface,
                        contentDescription = "上一首",
                        onClick = onPrevious,
                    )
                    CarRoundButton(
                        icon = if (player.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        size = 88.dp,
                        iconSize = 40.dp,
                        container = scheme.primary,
                        tint = scheme.onPrimary,
                        contentDescription = if (player.isPlaying) "暂停" else "播放",
                        onClick = onToggle,
                    )
                    CarRoundButton(
                        icon = AppIcons.SkipNext,
                        size = 76.dp,
                        iconSize = 34.dp,
                        container = scheme.surfaceContainerHighest,
                        tint = scheme.onSurface,
                        contentDescription = "下一首",
                        onClick = onNext,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Surface(
                    color = scheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onExpand),
                ) {
                    Text(
                        "点封面进入全屏歌词",
                        fontSize = 14.sp,
                        color = scheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(vertical = 13.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 车机圆形按钮：容器与图标尺寸分开传，方便主键 / 副键拉开层级 */
@Composable
private fun CarRoundButton(
    icon: ImageVector,
    size: Dp,
    iconSize: Dp,
    container: Color,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = tint,
        ),
        modifier = Modifier.size(size),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
    }
}

/** 车机进度条：10dp 粗轨道 + 26dp 圆点 + 14sp 时间，比手机版明显更易点中 */
@Composable
private fun CarProgressBar(
    progressFlow: StateFlow<PlaybackProgress>,
    fallbackDurationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val duration = if (progress.durationMs > 0L) progress.durationMs else fallbackDurationMs
    val drag = dragFraction
    val played = when {
        drag != null -> drag
        duration > 0L -> (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    val buffered = if (duration > 0L) {
        (progress.bufferedMs.toFloat() / duration).coerceIn(played, 1f)
    } else {
        0f
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(duration) {
                    detectTapGestures(
                        onTap = { off ->
                            if (duration > 0L) {
                                onSeek((off.x / size.width * duration).toLong())
                            }
                        },
                    )
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val f = dragFraction
                            if (f != null && duration > 0L) onSeek((f * duration).toLong())
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道 / 缓冲 / 已播 三层叠加
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
            )
            Box(
                Modifier
                    .fillMaxWidth(buffered.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.28f))
            )
            Box(
                Modifier
                    .fillMaxWidth(played.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
            )
            Box(
                Modifier
                    .fillMaxWidth(played.coerceIn(0f, 1f))
                    .height(30.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .background(scheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(drag?.let { (it * duration).toLong() } ?: progress.positionMs),
                fontSize = 14.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(duration),
                fontSize = 14.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================ 工具

/**
 * 车机空态 / 提示：居中大标题 + 说明 + 一排操作按钮。
 * [actions] 传「按钮文字 to 回调」的列表，最多建议 3 个。
 */
@Composable
private fun CarEmptyState(
    title: String,
    subtitle: String?,
    actions: List<Pair<String, () -> Unit>>,
    primaryHint: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                actions.forEach { (label, cb) -> CarPillButton(label, cb) }
            }
        }
        primaryHint?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, fontSize = 14.sp, color = scheme.onSurfaceVariant)
        }
    }
}

/** 详情页底部返回条：浮在列表之上，说明当前看的是哪个分组 / 歌单 */
@Composable
private fun CarDetailBar(
    label: String,
    count: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            CarPillButton("← 返回", onBack)
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            CarPill("$count 首")
        }
    }
}
