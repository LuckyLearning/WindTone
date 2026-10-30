package com.luckylearning.windtone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.PlaylistCoverMode
import com.luckylearning.windtone.state.playlistCoverMode

/**
 * 歌单列表封面：0 首占位图标，1 首整图，2~4 首 2×2 拼图（缺格留空）。
 *
 * 格内走现有 [CoverArt] → CoverRepository，不另开解码路径。
 */
@Composable
fun PlaylistCoverArt(
    songs: List<Song>,
    size: Dp = 52.dp,
    cornerRadius: Dp = 12.dp,
    gap: Dp = 1.dp,
    modifier: Modifier = Modifier,
) {
    val mode = playlistCoverMode(songs.size)
    val shape = RoundedCornerShape(cornerRadius)
    when (mode) {
        PlaylistCoverMode.ICON -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        PlaylistCoverMode.SINGLE -> {
            CoverArt(
                song = songs.first(),
                size = size,
                cornerRadius = cornerRadius,
                modifier = modifier,
            )
        }
        PlaylistCoverMode.GRID -> {
            val cell = (size - gap) / 2
            val emptyColor = MaterialTheme.colorScheme.surfaceContainerHighest
            Column(
                modifier = modifier
                    .size(size)
                    .clip(shape)
                    .background(emptyColor),
            ) {
                Row {
                    GridCell(songs.getOrNull(0), cell)
                    Spacer(Modifier.width(gap))
                    GridCell(songs.getOrNull(1), cell, emptyColor)
                }
                Spacer(Modifier.height(gap))
                Row {
                    GridCell(songs.getOrNull(2), cell, emptyColor)
                    Spacer(Modifier.width(gap))
                    GridCell(songs.getOrNull(3), cell, emptyColor)
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    song: Song?,
    size: Dp,
    emptyColor: Color? = null,
) {
    if (song != null) {
        CoverArt(song = song, size = size, cornerRadius = 0.dp)
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(emptyColor ?: MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}
