package com.luckylearning.windtone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.LyricsSearch
import kotlinx.coroutines.launch

/**
 * 搜索歌词：当前歌词缺失或明显对不上时的手动换词入口。
 *
 * 复用内置在线搜索（酷我 / 酷狗 / QQ / 咪咕）找候选，选中后按该源的歌词接口取正文，
 * 交给引擎立即替换并持久化；不切歌、不重启播放，也不改音频。
 */
@Composable
internal fun LyricsSearchSheet(
    song: Song?,
    manual: Boolean,
    onApply: (String) -> Unit,
    onRestore: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember(song?.stableKey) {
        mutableStateOf(
            listOfNotNull(
                song?.title?.takeIf { it.isNotBlank() },
                song?.artist?.takeIf { it.isNotBlank() },
            ).joinToString(" "),
        )
    }
    var results by remember { mutableStateOf<List<LyricsSearch.Candidate>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var fetchingKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank() || searching) return
        searching = true
        message = null
        scope.launch {
            results = runCatching { LyricsSearch.search(q) }.getOrDefault(emptyList())
            searching = false
            if (results.isEmpty()) message = R.string.lyrics_search_empty
        }
    }

    // 打开即按当前歌名 / 歌手搜一次，减少一次点击；用户仍可改关键词重搜。
    LaunchedEffect(song?.stableKey) {
        if (query.isNotBlank()) runSearch()
    }

    AppSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.lyrics_search),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.lyrics_search_sub),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                enabled = song != null && !searching,
                placeholder = { Text(stringResource(R.string.lyrics_search_hint)) },
                trailingIcon = {
                    IconButton(onClick = { runSearch() }, enabled = query.isNotBlank() && !searching) {
                        Icon(AppIcons.Search, contentDescription = stringResource(R.string.lyrics_search_action))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                Text(
                    stringResource(R.string.lyrics_search_searching),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            message?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .padding(top = 6.dp),
            ) {
                items(results, key = { it.key }) { candidate ->
                    val fetching = fetchingKey == candidate.key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = fetchingKey == null) {
                                message = null
                                fetchingKey = candidate.key
                                scope.launch {
                                    val text = runCatching {
                                        LyricsSearch.fetchText(candidate.track)
                                    }.getOrNull()
                                    fetchingKey = null
                                    if (text.isNullOrBlank()) {
                                        message = R.string.lyrics_search_failed
                                    } else {
                                        onApply(text)
                                    }
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${candidate.sourceLabel} · ${candidate.track.displayArtist}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (fetching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                stringResource(R.string.lyrics_search),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (manual && onRestore != null) {
                TextButton(onClick = onRestore, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.lyrics_search_restore))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
