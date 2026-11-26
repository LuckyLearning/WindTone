package com.luckylearning.windtone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.online.BuiltInSearch
import com.luckylearning.windtone.data.online.OnlineSonglists
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 歌单页的 UI 状态。
 *
 * 提到 `OnlinePage` 顶层持有（而不是放在 [SonglistPane] 里面）是为了扛住
 * `AnimatedContent` 的「进详情 → 离开组合 → 返回重建」：普通 `remember` 一离开组合就没了，
 * 回来时 `source/tagId/sheets` 全变回初值，于是重新拉第一页、滚动位置也归零。
 *
 * 这里同时承载「已经加载过哪一档」的标记：
 * - [tagsLoadedFor]：分类列表属于哪个平台；
 * - [loadedKey]：当前 `平台|分类` 的第一页**已经成功取到**。
 * 两者让 `LaunchedEffect` 在返回时重跑也能立刻退出，不重置数据。
 *
 * ⚠️ [loadedKey] 只能在请求成功之后写。1.3.4 之前是在发请求前就写，请求一旦被
 * `AnimatedContent` 切页取消（或等到超时），`loading` 留在 true、`loadedKey` 已经等于
 * 当前档，effect 重跑又被短路 —— 表现就是歌单页永远停在骨架屏，怎么进出都不恢复。
 * 现在失败/取消都不写 `loadedKey`，`loading` 由 `finally` 兜住，回来会重新请求。
 */
internal class SonglistUiState {
    var source by mutableStateOf(OnlineSonglists.SOURCES.first())
    var groups by mutableStateOf<List<OnlineSonglists.TagGroup>>(emptyList())
    var groupIndex by mutableIntStateOf(0)
    var tagId by mutableStateOf("")
    var sheets by mutableStateOf<List<OnlineSonglists.Sheet>>(emptyList())
    var page by mutableIntStateOf(1)
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var loadingMore by mutableStateOf(false)
    /**
     * 下一页超时后先停住自动加载。停在底部时如果立刻再请求，会每 12 秒打一次。
     * 可见项下标变了（用户又滑了一下）再允许。
     */
    var morePaused by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** 这次空态是「请求失败/超时」而不是「这一类真没歌单」，决定要不要给重试按钮 */
    var failed by mutableStateOf(false)
    var tagsLoadedFor by mutableStateOf<String?>(null)
    var loadedKey by mutableStateOf<String?>(null)
    /**
     * 已经为哪个 [loadedKey] 滚回过顶部。
     * 必须和列表数据一起活在页面外面：歌单页离开组合再回来时不能再滚一次，
     * 否则「返回保持位置」会被这次 scrollToItem(0) 清掉。
     */
    var scrolledKey by mutableStateOf<String?>(null)
    /** 点「重试」时 +1，让两个 effect 重跑一遍 */
    var retryTick by mutableIntStateOf(0)

    /** 清掉「已加载」标记并重来一次；分类缓存命中时不会再打网络 */
    fun retry() {
        tagId = ""
        groups = emptyList()
        tagsLoadedFor = null
        loadedKey = null
        scrolledKey = null
        failed = false
        error = null
        retryTick++
    }
}

/**
 * 歌单分类 / 列表的取数入口。默认就是 [OnlineSonglists]；
 * 测试可以换一份不碰网络的实现，用来钉「骨架会不会一直停着」。
 */
/** 歌单网格滚到尾部时的快照。放进 [snapshotFlow]，避免在里面改状态。 */
private data class ScrollTail(
    val last: Int,
    val total: Int,
    val loading: Boolean,
    val loadingMore: Boolean,
    val paused: Boolean,
    val hasMore: Boolean,
)

internal class SonglistGateway(
    val tags: suspend (String) -> List<OnlineSonglists.TagGroup>,
    val list: suspend (String, String, Int) -> OnlineSonglists.SheetPage,
) {
    companion object {
        val Default = SonglistGateway(
            tags = { OnlineSonglists.tags(it) },
            list = { source, tagId, page -> OnlineSonglists.list(source, tagId, page) },
        )
    }
}

/**
 * 「歌单」页（洛雪 `nav_songlist`）：平台 → 分类 → 歌单网格 → 歌单详情。
 *
 * 这里只做**浏览与选歌**，播放交给父级（`OnlinePage.playTracks`）—— 与搜索结果、
 * 榜单走同一条「整组入队」实现，否则三处各写一份，早晚会出现「这个入口能连播、
 * 那个入口只播一首」的不一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SonglistPane(
    state: SonglistUiState,
    gridState: LazyGridState,
    onOpen: (OnlineSonglists.Sheet) -> Unit,
    gateway: SonglistGateway = SonglistGateway.Default,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // 换平台：分类要重取。分类是静态的（OnlineSonglists 内有缓存），换回来不会再打网络。
    // 返回本页时 effect 会重跑一次，靠 tagsLoadedFor 直接退出，不能重新清空列表。
    LaunchedEffect(state.source, state.retryTick) {
        if (state.tagsLoadedFor == state.source) return@LaunchedEffect

        state.loading = true
        state.error = null
        state.failed = false
        // 先把上一家平台的东西清干净。tagId 若留着，下面那个 effect 会拿着
        // 「新平台 + 旧平台的 tag」发一次注定失败的请求，白等十几秒。
        state.tagId = ""
        state.groups = emptyList()
        state.sheets = emptyList()
        state.page = 1
        state.hasMore = false
        state.morePaused = false
        state.loadingMore = false
        state.groupIndex = 0
        state.loadedKey = null

        // 取消（用户切走）会在这里抛 CancellationException，后面几行不会执行 ——
        // 这正是「不记已加载、回来能重来」的保证，别把 tagsLoadedFor 提前写。
        val groups = withTimeoutOrNull(TAGS_TIMEOUT_MS) { gateway.tags(state.source) }
        if (groups == null) {
            // 超时不能记成「已加载」：否则返回本页时 167 行的短路会让这家分类再也不重取
            state.failed = true
            state.error = AppStrings.s(R.string.online_sheet_load_failed)
            state.loading = false
            return@LaunchedEffect
        }
        state.groups = groups
        state.tagsLoadedFor = state.source

        val first = groups.firstOrNull()?.tags?.firstOrNull()?.id
        if (first != null) {
            // 赋值即触发下面那个 effect 去拉第一页，避免这里再写一份请求
            state.tagId = first
            return@LaunchedEffect
        }

        // 该平台没有分类（或分类接口挂了）：不传 tagId 也有一批默认歌单
        try {
            val r = withTimeoutOrNull(LIST_TIMEOUT_MS) { gateway.list(state.source, "", 1) }
            if (r == null || r.error != null) {
                state.failed = true
                state.error = AppStrings.s(R.string.online_sheet_load_failed)
            } else {
                state.sheets = r.items
                state.page = r.page
                state.hasMore = r.hasMore
                if (r.items.isEmpty()) state.error = AppStrings.s(R.string.online_sheet_empty_list)
                // 只有真拿到才记；失败/取消留空，下次进来还能再试
                state.loadedKey = "${state.source}|"
            }
        } finally {
            state.loading = false
        }
    }

    // 换分类：结果整体替换（不是追加），页码回到 1，网格回到顶部。
    // 返回本页时 loadedKey 已等于当前 key，直接退出，保留列表与滚动位置。
    LaunchedEffect(state.source, state.tagId, state.retryTick) {
        if (state.tagId.isBlank()) return@LaunchedEffect
        val key = "${state.source}|${state.tagId}"
        if (state.loadedKey == key) return@LaunchedEffect
        state.loading = true
        state.error = null
        state.failed = false
        state.morePaused = false
        state.loadingMore = false
        // 这里不能 scrollToItem。loading=true 时网格被骨架替换，不在组合里；
        // LazyGridState.scroll 会先等那次布局（AwaitFirstLayoutModifier），
        // 布局又要等 loading 变 false 才会发生 —— 协程停在 scrollToItem，
        // finally 跑不到，骨架就永远留着。滚回顶部放到数据已经显示之后。
        try {
            val r = withTimeoutOrNull(LIST_TIMEOUT_MS) { gateway.list(state.source, state.tagId, 1) }
            if (r == null || r.error != null) {
                state.failed = true
                state.error = AppStrings.s(R.string.online_sheet_load_failed)
            } else {
                state.sheets = r.items
                state.page = r.page
                state.hasMore = r.hasMore
                if (r.items.isEmpty()) state.error = AppStrings.s(R.string.online_sheet_empty_list)
                // 成功才记这一档。以前是发请求前就写 loadedKey，
                // 请求被取消后 effect 重跑会被它短路，页面永远停在骨架上。
                state.loadedKey = key
            }
        } finally {
            state.loading = false
        }
    }

    fun loadMore() {
        if (state.loadingMore || state.loading || state.morePaused || !state.hasMore || state.sheets.isEmpty()) return
        val key = state.loadedKey ?: return
        val before = state.sheets.size
        state.loadingMore = true
        scope.launch {
            try {
                val next = state.page + 1
                val r = withTimeoutOrNull(LIST_TIMEOUT_MS) {
                    gateway.list(state.source, state.tagId, next)
                }
                // 用户已经换了分类：这次结果作废，不能拼到新列表后面
                if (state.loadedKey != key) return@launch
                if (r == null || r.error != null) {
                    state.morePaused = true
                } else if (r.items.isEmpty()) {
                    state.hasMore = false
                } else {
                    val merged = (state.sheets + r.items).distinctBy { it.key }
                    state.sheets = merged
                    state.page = next
                    // 整页都是重复项时再请求也拿不到新歌单，停下来
                    state.hasMore = r.hasMore && merged.size > before
                }
            } finally {
                state.loadingMore = false
            }
        }
    }

    // 滑到接近底部再取下一页。没有「加载更多」按钮。
    LaunchedEffect(state.loadedKey) {
        var pausedAt = Int.MIN_VALUE
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            ScrollTail(last, total, state.loading, state.loadingMore, state.morePaused, state.hasMore)
        }.collect { tail ->
            if (tail.paused) {
                if (pausedAt == Int.MIN_VALUE) pausedAt = tail.last
                else if (tail.last != pausedAt && tail.last >= 0) {
                    state.morePaused = false
                    pausedAt = Int.MIN_VALUE
                }
                return@collect
            }
            pausedAt = Int.MIN_VALUE
            val nearEnd = !tail.loading && !tail.loadingMore && tail.hasMore &&
                tail.total > 0 && tail.last >= tail.total - 4
            if (nearEnd) loadMore()
        }
    }

    // 网格已经画出来之后才滚回顶部。同一档回来（scrolledKey 还在）不再滚，保留位置。
    LaunchedEffect(state.loadedKey, state.loading) {
        val key = state.loadedKey ?: return@LaunchedEffect
        if (state.loading || key == state.scrolledKey) return@LaunchedEffect
        if (state.sheets.isNotEmpty()) gridState.scrollToItem(0)
        state.scrolledKey = key
    }

    Column(modifier.fillMaxSize()) {
        // 平台 / 分组 / 标签三层筛选收在同一块浅色面板里，避免三排 chip 直接贴在内容上
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(OnlineSonglists.SOURCES) { id ->
                    FilterChip(
                        selected = state.source == id,
                        onClick = { if (state.source != id) state.source = id },
                        label = {
                            Text(
                                BuiltInSearch.SOURCE_NAMES[id]?.removeSuffix("音乐") ?: id,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }

            if (state.groups.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.groups.indices.toList()) { index ->
                        val group = state.groups[index]
                        FilterChip(
                            selected = state.groupIndex == index,
                            onClick = {
                                state.groupIndex = index
                                group.tags.firstOrNull()?.id?.let { state.tagId = it }
                            },
                            label = {
                                Text(group.name, style = MaterialTheme.typography.labelMedium)
                            },
                        )
                    }
                }
                val tags = state.groups.getOrNull(state.groupIndex)?.tags.orEmpty()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tags) { tag ->
                        FilterChip(
                            selected = state.tagId == tag.id,
                            onClick = { if (state.tagId != tag.id) state.tagId = tag.id },
                            label = {
                                Text(tag.name, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }
        }

        when {
            // 骨架而不是整页转圈：加载完成时卡片原地出现，不出现「空 → 一堆」的跳动
            state.loading -> LxGridSkeleton(Modifier.weight(1f))
            state.sheets.isEmpty() -> LxEmptyPane(
                title = state.error ?: AppStrings.s(R.string.online_sheet_empty_list),
                // 请求失败/超时才给重试；「这一类真没歌单」不用假装能重试
                modifier = Modifier.weight(1f),
                action = if (state.failed) {
                    {
                        Button(onClick = { state.retry() }) {
                            Text(AppStrings.s(R.string.online_retry))
                        }
                    }
                } else {
                    null
                },
            )
            else -> {
                // weight 而不是 fillMaxSize：上面还有分类条，网格只占剩余高度。
                // fillMaxSize 会按整页高度测量，视口比看得见的区域更高，滑起来像没动。
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("sheet-grid"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                ) {
                    itemsIndexed(state.sheets, key = { _, sheet -> sheet.key }) { index, sheet ->
                        SheetCard(sheet, index, onClick = { onOpen(sheet) })
                    }
                    if (state.loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 歌单卡片：封面 + 两行标题 + 作者/播放量。封面取不到就是首字渐变，不留空洞。 */
@Composable
private fun SheetCard(sheet: OnlineSonglists.Sheet, index: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        LxCover(
            url = sheet.cover,
            cacheKey = sheet.key,
            name = sheet.name,
            targetPx = SHEET_COVER_PX,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(14.dp),
            initialSize = 30.sp,
            // 首屏卡片错峰 45ms：同一帧不再排 10 个请求，滑起来更顺
            staggerMs = (index % 6) * 45L,
        )
        Text(
            sheet.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            listOf(sheet.author, sheet.playCount).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * 歌单详情：头部信息 + 歌单内歌曲。
 *
 * 「收进我的列表」写的是**本地 Playlist**（`playlists` / `playlist_songs`），
 * 与收藏是两回事：收藏是单曲标记，这里是把整个歌单攒成一份自己的列表。
 */
@Composable
internal fun SheetDetailPane(
    sheet: OnlineSonglists.Sheet,
    tracks: List<OnlineTrack>,
    loading: Boolean,
    error: String?,
    saving: Boolean,
    /** 已经收进我的列表：按钮换文案并禁用，避免再点一次建出第二条同名列表 */
    saved: Boolean = false,
    favoriteKeys: Set<String> = emptySet(),
    /** 由 `OnlinePage` 顶层持有：返回列表再进来时停在上次看的那一首 */
    listState: LazyListState = rememberLazyListState(),
    scrollGen: Int = 0,
    scrolledGen: Int = 0,
    onScrolled: (Int) -> Unit = {},
    onPlay: (Int) -> Unit,
    onFavorite: (OnlineTrack) -> Unit,
    onSaveToList: () -> Unit,
) {
    when {
        loading -> LxListSkeleton(rows = 8)
        tracks.isEmpty() -> LxEmptyPane(
            title = error ?: AppStrings.s(R.string.online_sheet_empty),
        )
        else -> {
            LaunchedEffect(scrollGen) {
                if (scrollGen == 0 || scrollGen == scrolledGen) return@LaunchedEffect
                listState.scrollToItem(0)
                onScrolled(scrollGen)
            }
            LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "head") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetCoverLarge(sheet)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                sheet.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOf(
                                    sheet.author,
                                    AppStrings.s(R.string.online_sheet_song_count, tracks.size),
                                    sheet.playCount,
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = onSaveToList,
                                enabled = !saving && !saved,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    when {
                                        saving -> AppStrings.s(R.string.online_saving)
                                        saved -> AppStrings.s(R.string.online_saved_button)
                                        else -> AppStrings.s(R.string.online_save_to_list)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
            listItemsIndexed(tracks, key = { _, track -> track.stableKey }) { index, track ->
                TrackRow(
                    track = track,
                    favorite = track.stableKey in favoriteKeys,
                    onPlay = { onPlay(index) },
                    onFavorite = { onFavorite(track) },
                    staggerMs = (index % 6) * 30L,
                )
            }
        }
        }
    }
}

@Composable
private fun SheetCoverLarge(sheet: OnlineSonglists.Sheet) {
    LxCover(
        url = sheet.cover,
        cacheKey = sheet.key,
        name = sheet.name,
        targetPx = SHEET_COVER_LARGE_PX,
        modifier = Modifier.size(96.dp),
        shape = RoundedCornerShape(16.dp),
        initialSize = 30.sp,
    )
}

/**
 * 分类 / 歌单列表的单次等待上限。比 OkHttp 自身的 readTimeout(20s) 短，
 * 先让界面落到「失败了，点重试」而不是干等转圈。
 */
private const val TAGS_TIMEOUT_MS = 10_000L
private const val LIST_TIMEOUT_MS = 12_000L

/** 150dp 卡片在 xxhdpi 上约 450px；取 320 兼顾清晰度与内存 */
private const val SHEET_COVER_PX = 320

/** 详情头部 96dp → 约 290px，与列表同档，避免二次解码 */
private const val SHEET_COVER_LARGE_PX = 320
