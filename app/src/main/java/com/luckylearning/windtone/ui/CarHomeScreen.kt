package com.luckylearning.windtone.ui
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.VirtualPlaylist

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 车机版主页：**两栏**横屏布局（替代手机的「单列 + 底部导航」）。
 *
 * 屏幕基准：2560×1600 中控屏 → hdpi(240) 密度桶 → 约 1707×1067dp 可用空间。
 * 只有 `Prefs.carMode` 打开时才走这里，手机布局完全不受影响。
 *
 *  ┌──────────┬───────────────────────────────────────────────┐
 *  │          │                                               │
 *  │ 左侧导航  │              内容列表（占满剩余宽度）             │
 *  │  256dp   │                                               │
 *  │          ├───────────────────────────────────────────────┤
 *  │          │  常驻播放条：封面 · 歌名/歌手 · ⏮ ⏯ ⏭ · 进度    │
 *  └──────────┴───────────────────────────────────────────────┘
 *
 * 相比手机布局的三点差异：
 *  1. 底部 NavigationBar 换成左侧纵向导航——横屏下纵向空间充足，侧栏更省纵向像素；
 *  2. 迷你播放条换成**底部横贯全宽的大播放条**（大封面 + 76dp 触控键 + 可拖进度条），
 *     点条上任意位置进入独立的**全屏播放页**；
 *  3. 行高 / 字号 / 图标整体放大一档，保证行车中一眼可辨、一指点中。
 *
 * 为什么不做第三栏：原设计右侧挂一个 320~400dp 的「正在播放」大卡片，1707dp 屏上
 * 中间列表只剩约 1000dp，宽屏优势发挥不出来，曲名长一点就被挤成省略号。
 * 改成两栏后列表拿到全部剩余宽度，播放控制下沉到底部横条——横条是车机里
 * 最顺手的位置（离副驾与中控旋钮都近），也不占用列表的横向空间。
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
    /** 当前歌是否已收藏（底部播放条红心态） */
    isFavorite: Boolean = false,
    /** 切换收藏（底部播放条红心） */
    onToggleFavorite: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    // 车机上弹软键盘体验差，搜索框做成「点按钮才展开」的两段式，平时不占位置
    var searchOpen by remember { mutableStateOf(false) }

    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // 车机是横屏：状态栏在顶部、导航手势条在底部（部分车机在侧边），
                // 不处理 insets 会让左侧导航第一项与底部播放条被系统条压住。
                // 与手机端 MainScreen 的处理保持一致。
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // -------------------------------------------------- 左侧导航
                CarNavRail(
                    current = library.view,
                    onSelect = onSelectView,
                    onOpenSettings = onOpenSettings,
                )

                // -------------------------------------------------- 内容列表
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
                        // 详情态时底部会浮出返回条，列表底部要预留出它的高度，
                        // 否则最后 2~3 首会被盖住点不到。
                        val bottomInset = if (detail) 96.dp else 18.dp
                        // 一级列表的滚动位置托管（与手机端 MainScreen 同一套做法）：按视图各存
                        // 一份，切视图或进详情再返回时列表回到原处。必须声明在 when 之外 ——
                        // 进详情时下面这层会离开组合，holder 若被 forget，位置就一起丢了。
                        val listStateHolder = rememberSaveableStateHolder()
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
                                bottomInset = bottomInset,
                            )

                            library.selectedGroup != null -> CarSongList(
                                songs = library.detailSongs,
                                currentKey = player.current?.stableKey,
                                isPlaying = player.isPlaying,
                                emptyTitle = "这里没有歌曲",
                                emptySubtitle = null,
                                onPlay = { i -> onPlay(library.detailSongs, i) },
                                onLongPress = onSongLongPress,
                                bottomInset = bottomInset,
                            )

                            // 一级列表：以 view 为 key，每个视图各记一份滚动位置。
                            // 详情列表（歌单 / 分组）留在这层之外，每次进入都从顶部开始。
                            else -> listStateHolder.SaveableStateProvider(library.view.name) {
                                when (library.view) {
                                    LibraryView.SONGS -> CarSongList(
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
                                        bottomInset = bottomInset,
                                    )

                                    LibraryView.PLAYLIST -> CarPlaylistList(
                                        library = library,
                                        onOpen = onOpenPlaylist,
                                        onNewPlaylist = onNewPlaylist,
                                        onImportM3U = onImportM3U,
                                        onPlay = onPlay,
                                        bottomInset = bottomInset,
                                    )

                                    else -> CarGroupList(
                                        library = library,
                                        onOpen = onOpenGroup,
                                        bottomInset = bottomInset,
                                    )
                                }
                            }
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
            }

            // ------------------------------------------------------- 底部播放条
            // 两栏布局里唯一的播放控制入口：横贯全宽，点任意处进独立全屏播放页。
            CarNowPlayingBar(
                player = player,
                progressFlow = progressFlow,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
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
 * 左侧纵向导航：宽度 256dp，条目高 **64dp**、图标 26dp、文字 17sp。
 * 高度对齐 Google 车机规范的 76dp 触控下限思路——64dp 条目 + 上下各 4dp 间距 = 72dp 热区，
 * 与右侧「正在播放」栏的 76dp 按键保持同一标准（原先 56dp 偏小，行车中容易点偏）。
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
    // 选中态不用 onPrimary：跟随封面取色时 onPrimary 由 seed 派生，
    // 遇到亮色 seed 会变成接近背景的浅色，导航文字会糊掉。
    // 这里改用 primaryContainer / onPrimaryContainer —— 两者在浅色与深色方案里
    // 都是一深一浅成对定义，对比度有保证（M3 容器色的设计意图正是如此）。
    val container = if (selected) scheme.primaryContainer else scheme.surfaceContainer
    val fg = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val shape = MaterialTheme.shapes.medium
    Box(Modifier.padding(vertical = 4.dp)) {
        Surface(
            color = container,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
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
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .height(52.dp)
            .clip(MaterialTheme.shapes.medium)
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
        shape = MaterialTheme.shapes.medium,
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
    bottomInset: Dp = 18.dp,
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
        contentPadding = PaddingValues(bottom = bottomInset),
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
    bottomInset: Dp = 18.dp,
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
        contentPadding = PaddingValues(bottom = bottomInset),
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
    bottomInset: Dp = 18.dp,
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
            contentPadding = PaddingValues(bottom = bottomInset),
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
                                VirtualPlaylist.RECENT,
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
                                VirtualPlaylist.MOST_PLAYED,
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
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
                        .clip(MaterialTheme.shapes.medium)
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
 * 车机底部常驻播放条（横贯全宽）。
 *
 * 两栏布局下这里是唯一的播放控制入口：
 *  - 点条上任意位置（封面 / 空白区 / 歌名区）= 进独立全屏播放页；
 *  - 左侧三键「⏮ ⏯ ⏭」直接操作，主播放键 68dp，两侧 56dp，均满足车机触控下限；
 *  - 中部是**可直接拖动**的进度条（[CarProgressBar] 自带「已播 / 总时长」），
 *    拖动时不会误触发进全屏——进度条自己消费了拖动事件；
 *  - 最右一个显式的「全屏播放」按钮，避免用户不知道「点条上能进全屏」。
 *
 * 为什么不做成第三栏：1707dp 屏上右侧栏 400dp 会吃掉列表近 40% 的宽度，
 * 长曲名被挤成省略号；横条方案把控制下沉，列表拿到全部剩余宽度。
 */
@Composable
private fun CarNowPlayingBar(
    player: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val song = player.current

    Surface(
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---------------------------------------------------------- 封面
            Box(
                Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.surfaceContainerHighest)
                    .clickable(onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) {
                if (song != null) {
                    CoverArt(song = song, size = 64.dp, cornerRadius = 10.dp)
                } else {
                    Icon(
                        imageVector = AppIcons.MusicNote,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // -------------------------------------------------- 歌名 / 歌手
            Column(
                Modifier
                    .width(240.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onExpand),
            ) {
                Text(
                    text = song?.title ?: "还没有在播放的歌曲",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = song?.artistOrUnknown ?: "在左侧列表点一首歌即可开始",
                    fontSize = 14.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(22.dp))

            // ------------------------------------------- 收藏（⏮ 左侧）
            // 车机驾驶场景里点屏幕右缘费劲，收藏放在控制键簇最左边顺手的位置；
            // 已收藏用 primary 实心，未收藏用描边（与播放页歌名行红心同款规则）。
            CarRoundButton(
                icon = if (isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                size = 56.dp,
                iconSize = 28.dp,
                container = scheme.surfaceContainerHighest,
                tint = if (isFavorite) scheme.primary else scheme.onSurface,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                onClick = onToggleFavorite,
            )
            Spacer(Modifier.width(12.dp))

            // ------------------------------------------------------ 控制三键
            CarRoundButton(
                icon = AppIcons.SkipPrevious,
                size = 56.dp,
                iconSize = 28.dp,
                container = scheme.surfaceContainerHighest,
                tint = scheme.onSurface,
                contentDescription = "上一首",
                onClick = onPrevious,
            )
            Spacer(Modifier.width(12.dp))
            CarRoundButton(
                icon = if (player.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                size = 68.dp,
                iconSize = 34.dp,
                container = scheme.primary,
                tint = scheme.onPrimary,
                contentDescription = if (player.isPlaying) "暂停" else "播放",
                onClick = onToggle,
            )
            Spacer(Modifier.width(12.dp))
            CarRoundButton(
                icon = AppIcons.SkipNext,
                size = 56.dp,
                iconSize = 28.dp,
                container = scheme.surfaceContainerHighest,
                tint = scheme.onSurface,
                contentDescription = "下一首",
                onClick = onNext,
            )

            Spacer(Modifier.width(22.dp))

            // ------------------------------------------- 进度 + 时间（自带）
            CarProgressBar(
                progressFlow = progressFlow,
                fallbackDurationMs = player.durationMs,
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(22.dp))

            // 进全屏的显式入口
            CarRoundButton(
                icon = AppIcons.Lyrics,
                size = 56.dp,
                iconSize = 28.dp,
                container = scheme.primary.copy(alpha = 0.14f),
                tint = scheme.primary,
                contentDescription = "全屏播放",
                onClick = onExpand,
            )
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
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(MaterialTheme.shapes.medium),
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
