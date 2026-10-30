@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.luckylearning.windtone.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.BookmarkRepository
import com.luckylearning.windtone.data.PlaybackBookmark
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.ui.theme.glassContainerColor
import kotlinx.coroutines.launch

/** H3: bookmark current position + list jump/delete. */
@Composable
fun BookmarkSheet(
    song: Song?,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val repo = remember(app) { (app as MusicApp).container.bookmarks }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<PlaybackBookmark>>(emptyList()) }

    suspend fun reload() {
        rows = if (song == null) emptyList() else repo.listForSong(song)
    }

    LaunchedEffect(song?.stableKey) {
        reload()
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(R.string.po_bookmarks),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    song == null -> stringResource(R.string.bm_no_song)
                    else -> stringResource(
                        R.string.bm_current_position,
                        song.title,
                        BookmarkRepository.formatClock(positionMs),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Button(
                onClick = {
                    val s = song ?: return@Button
                    scope.launch {
                        repo.addAt(s, positionMs)
                        reload()
                    }
                },
                enabled = song != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(AppIcons.Bookmark, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        R.string.bm_add_here,
                        BookmarkRepository.formatClock(positionMs),
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (rows.isEmpty()) {
                    stringResource(R.string.bm_empty)
                } else {
                    stringResource(R.string.bm_list_count, rows.size)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    BookmarkRow(
                        row = row,
                        onJump = {
                            onSeekTo(row.positionMs)
                            onClose()
                        },
                        onDelete = {
                            scope.launch {
                                repo.delete(row.id)
                                reload()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.bm_close))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BookmarkRow(
    row: PlaybackBookmark,
    onJump: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = row.note.ifBlank { BookmarkRepository.formatClock(row.positionMs) }
    val subtitle = if (row.note.isBlank()) null else BookmarkRepository.formatClock(row.positionMs)

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = onJump, shape = RoundedCornerShape(10.dp)) {
            Text(stringResource(R.string.bm_jump))
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
    }
}
