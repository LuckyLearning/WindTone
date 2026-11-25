package com.luckylearning.windtone.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    onOpen: (OnlineLists.Summary) -> Unit,
) {
    when {
        loading && lists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        lists.isEmpty() -> EmptyHint(stringResource(R.string.online_mine_empty))
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lists, key = { it.id }) { list ->
                MyListRow(list = list, onClick = { onOpen(list) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MyListRow(list: OnlineLists.Summary, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            PlaylistCoverArt(songs = list.cover, size = 52.dp, cornerRadius = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    list.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.lib_song_count, list.count, list.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            CountPill("${list.count}")
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
        loading && songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        songs.isEmpty() -> EmptyHint(stringResource(R.string.online_mine_empty))
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
            item { Spacer(Modifier.height(24.dp)) }
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(song = song, size = 44.dp, cornerRadius = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(song.artistOrUnknown, formatDuration(song.durationMs))
                        .filter { it.isNotBlank() && it != "--:--" }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
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
