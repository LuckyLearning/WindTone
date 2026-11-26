package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.OnlineLists

/**
 * 洛雪源的「我的列表」分区。
 *
 * 只展示**从在线歌单收进来的**列表（[OnlineLists] 判定）；本地自建列表在本地模式的同名入口里。
 * 两处入口不混排，是为了让「我现在在哪个音源模式」这件事在菜单上就能看出来。
 */
@Composable
internal fun MyListsPane(
    lists: List<OnlineLists.Summary>,
    loading: Boolean,
    /** 最近播放的前几首，用来拼封面；空列表时显示占位 */
    recentCover: List<Song> = emptyList(),
    recentCount: Int = 0,
    onOpen: (OnlineLists.Summary) -> Unit,
    onOpenRecent: () -> Unit = {},
) {
    when {
        // 骨架保持列表形状：加载完成时行原地出现，不再「空白 → 一堆」跳一下
        loading && lists.isEmpty() && recentCount == 0 -> LxListSkeleton(rows = 8)
        lists.isEmpty() && recentCount == 0 ->
            LxEmptyPane(title = stringResource(R.string.online_mine_empty))
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            // 「最近播放」固定排第一：按时间自动生成，不属于用户自建列表
            item(key = "recent") {
                RecentRow(cover = recentCover, count = recentCount, onClick = onOpenRecent)
            }
            items(lists, key = { it.id }) { list ->
                MyListRow(list = list, onClick = { onOpen(list) })
            }
        }
    }
}

/** 与自建列表同一套卡片：最近播放只是数据来源不同，样式不另起一套 */
@Composable
private fun RecentRow(cover: List<Song>, count: Int, onClick: () -> Unit) {
    LxCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaylistCoverArt(songs = cover, size = 56.dp, cornerRadius = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.vp_recent),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    pluralStringResource(R.plurals.lib_song_count, count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            LxCountPill(count)
        }
    }
}

@Composable
private fun MyListRow(list: OnlineLists.Summary, onClick: () -> Unit) {
    LxCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaylistCoverArt(songs = list.cover, size = 56.dp, cornerRadius = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    list.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    pluralStringResource(R.plurals.lib_song_count, list.count, list.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            LxCountPill(list.count)
        }
    }
}

/** 我的列表详情：点一首从该首连续播放整份列表 */
@Composable
internal fun MyListDetailPane(
    songs: List<Song>,
    loading: Boolean,
    favoriteKeys: Set<String>,
    onPlay: (Int) -> Unit,
    onFavorite: (Song) -> Unit,
) {
    when {
        loading && songs.isEmpty() -> LxListSkeleton(rows = 8)
        songs.isEmpty() -> LxEmptyPane(title = stringResource(R.string.online_mine_empty))
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            items(songs, key = { it.stableKey }) { song ->
                val index = songs.indexOfFirst { it.stableKey == song.stableKey }
                MySongRow(
                    song = song,
                    favorite = song.stableKey in favoriteKeys,
                    onPlay = { onPlay(index) },
                    onFavorite = { onFavorite(song) },
                )
            }
        }
    }
}

@Composable
private fun MySongRow(
    song: Song,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    LxCard(
        onClick = onPlay,
        contentPadding = PaddingValues(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(song = song, size = 48.dp, cornerRadius = 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(song.artistOrUnknown, formatDuration(song.durationMs))
                        .filter { it.isNotBlank() && it != "--:--" }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (favorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = stringResource(
                        if (favorite) R.string.cd_unfavorite else R.string.cd_favorite,
                    ),
                    tint = if (favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
