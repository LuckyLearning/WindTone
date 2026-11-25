package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.ui.theme.AppVisual
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.online.BuiltInSearch
import com.luckylearning.windtone.data.online.OnlineCover
import com.luckylearning.windtone.data.online.OnlineSonglists
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.launch

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
    onOpen: (OnlineSonglists.Sheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(OnlineSonglists.SOURCES.first()) }
    var groups by remember { mutableStateOf<List<OnlineSonglists.TagGroup>>(emptyList()) }
    var groupIndex by remember { mutableIntStateOf(0) }
    var tagId by remember { mutableStateOf("") }

    var sheets by remember { mutableStateOf<List<OnlineSonglists.Sheet>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 换平台：分类要重取。分类是静态的（OnlineSonglists 内有缓存），换回来不会再打网络
    LaunchedEffect(source) {
        loading = true
        error = null
        sheets = emptyList()
        page = 1
        hasMore = false
        groupIndex = 0
        tagId = ""
        groups = OnlineSonglists.tags(source)
        val first = groups.firstOrNull()?.tags?.firstOrNull()?.id
        if (first != null) {
            // 赋值即触发下面那个 effect 去拉第一页，避免这里再写一份请求
            tagId = first
            return@LaunchedEffect
        }
        // 该平台没有分类（或分类接口挂了）：不传 tagId 也有一批默认歌单
        val r = OnlineSonglists.list(source, "", 1)
        sheets = r.items
        page = r.page
        hasMore = r.hasMore
        if (r.items.isEmpty()) error = AppStrings.s(R.string.online_sheet_empty_list)
        loading = false
    }

    // 换分类：结果整体替换（不是追加），页码回到 1
    LaunchedEffect(source, tagId) {
        if (tagId.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        val r = OnlineSonglists.list(source, tagId, 1)
        sheets = r.items
        page = r.page
        hasMore = r.hasMore
        if (r.items.isEmpty()) error = AppStrings.s(R.string.online_sheet_empty_list)
        loading = false
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val next = page + 1
            val r = OnlineSonglists.list(source, tagId, next)
            if (r.items.isNotEmpty()) {
                sheets = (sheets + r.items).distinctBy { it.key }
                page = next
            }
            hasMore = r.hasMore && r.items.isNotEmpty()
            loadingMore = false
        }
    }

    Column(modifier.fillMaxSize()) {
        // 平台：只列四家，切过去失败的由下方空态说明，不隐藏入口
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(OnlineSonglists.SOURCES) { id ->
                FilterChip(
                    selected = source == id,
                    onClick = { source = id },
                    label = {
                        Text(
                            BuiltInSearch.SOURCE_NAMES[id]?.removeSuffix("音乐") ?: id,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }

        if (groups.isNotEmpty()) {
            // 分组：主题 / 语种 / 风格…（四家里只有咪咕没有分组，已被合并成一组）
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(groups.indices.toList()) { index ->
                    val group = groups[index]
                    FilterChip(
                        selected = groupIndex == index,
                        onClick = {
                            groupIndex = index
                            group.tags.firstOrNull()?.id?.let { tagId = it }
                        },
                        label = {
                            Text(group.name, style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
            val tags = groups.getOrNull(groupIndex)?.tags.orEmpty()
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tags) { tag ->
                    FilterChip(
                        selected = tagId == tag.id,
                        onClick = { if (tagId != tag.id) tagId = tag.id },
                        label = {
                            Text(tag.name, style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            sheets.isEmpty() -> EmptyHint(error ?: AppStrings.s(R.string.online_no_result))
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sheets, key = { it.key }) { sheet ->
                        SheetCard(sheet, onClick = { onOpen(sheet) })
                    }
                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(onClick = { loadMore() }, enabled = !loadingMore) {
                                    Text(
                                        if (loadingMore) AppStrings.s(R.string.online_loading)
                                        else AppStrings.s(R.string.online_load_more),
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** 歌单卡片：封面 + 两行标题 + 作者/播放量。封面取不到就是纯色块，不留空洞 */
@Composable
private fun SheetCard(sheet: OnlineSonglists.Sheet, onClick: () -> Unit) {
    var bitmap by remember(sheet.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sheet.key, sheet.cover) {
        bitmap = sheetCoverRetry { OnlineCover.thumbUrl(sheet.cover, SHEET_COVER_PX) }
    }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(AppVisual.coverBrush(sheet.key)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    AppVisual.coverInitial(sheet.name),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            sheet.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            listOf(sheet.author, sheet.playCount).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    onPlay: (Int) -> Unit,
    onFavorite: (OnlineTrack) -> Unit,
    onSaveToList: () -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.online_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        tracks.isEmpty() -> EmptyHint(error ?: AppStrings.s(R.string.online_sheet_empty))
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "head") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetCoverLarge(sheet)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            sheet.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onSaveToList, enabled = !saving && !saved) {
                            Text(
                                when {
                                    saving -> AppStrings.s(R.string.online_saving)
                                    saved -> AppStrings.s(R.string.online_saved_button)
                                    else -> AppStrings.s(R.string.online_save_to_list)
                                },
                            )
                        }
                    }
                }
            }
            items(tracks, key = { it.stableKey }) { track ->
                val index = tracks.indexOfFirst { it.stableKey == track.stableKey }
                TrackRow(
                    track = track,
                    favorite = track.stableKey in favoriteKeys,
                    onPlay = { onPlay(index) },
                    onFavorite = { onFavorite(track) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SheetCoverLarge(sheet: OnlineSonglists.Sheet) {
    var bitmap by remember(sheet.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sheet.key, sheet.cover) {
        bitmap = sheetCoverRetry { OnlineCover.thumbUrl(sheet.cover, SHEET_COVER_LARGE_PX) }
    }
    Box(
        Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppVisual.coverBrush(sheet.key)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                AppVisual.coverInitial(sheet.name),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private suspend fun sheetCoverRetry(block: suspend () -> Bitmap?): Bitmap? {
    var attempt = 0
    while (true) {
        val bmp = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (bmp != null) return bmp
        if (attempt >= 2) return null
        attempt++
        delay(800L * attempt)
    }
}

/** 140dp 卡片在 xhdpi 上是 420px；取 300 已够清晰，再大只是白占内存 */
private const val SHEET_COVER_PX = 300

/** 详情头部 88dp → 264px，取同档位避免二次解码 */
private const val SHEET_COVER_LARGE_PX = 300
