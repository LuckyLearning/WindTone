package com.luckylearning.windtone.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.LyricsSearch
import com.luckylearning.windtone.lyrics.Lyrics
import kotlinx.coroutines.launch

/**
 * 搜索歌词：当前歌词缺失或明显对不上时的手动换词入口。
 *
 * **两步走，绝不自动套用**：
 *  1. 点候选 → 只拉取并展示预览，列表里标「已选」，不动当前歌词；
 *  2. 确认预览后按「使用这份歌词」→ 才替换并持久化。
 *
 * 这样用户在多个同名版本间可以来回点、看够再决定；已应用手动词之后，再次打开仍可
 * 重新搜索换一份，或「恢复自动获取」。
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
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var selectedLyrics by remember { mutableStateOf<Lyrics?>(null) }
    var fetchingKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank() || searching) return
        searching = true
        message = null
        selectedKey = null
        selectedText = null
        selectedLyrics = null
        scope.launch {
            results = runCatching { LyricsSearch.search(q) }.getOrDefault(emptyList())
            searching = false
            if (results.isEmpty()) message = R.string.lyrics_search_empty
        }
    }

    /** 点候选只“选中 + 取预览”，不应用；应用只由底部按钮触发。 */
    fun selectCandidate(candidate: LyricsSearch.Candidate) {
        if (fetchingKey != null) return
        selectedKey = candidate.key
        selectedText = null
        selectedLyrics = null
        message = null
        fetchingKey = candidate.key
        scope.launch {
            val text = runCatching { LyricsSearch.fetchText(candidate.track) }.getOrNull()
            fetchingKey = null
            if (text.isNullOrBlank()) {
                selectedKey = null
                message = R.string.lyrics_search_failed
            } else {
                selectedText = text
                selectedLyrics = LyricsSearch.parse(text)
            }
        }
    }

    // 打开即按当前歌名 / 歌手搜一次，减少一次点击；用户仍可改关键词重搜。
    LaunchedEffect(song?.stableKey) {
        if (query.isNotBlank()) runSearch()
    }

    AppSheet(onDismissRequest = onDismiss, maxFraction = 0.88f) {
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
            Text(
                stringResource(R.string.lyrics_search_preview_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(top = 4.dp),
            ) {
                items(results, key = { it.key }) { candidate ->
                    val selected = selectedKey == candidate.key
                    val fetching = fetchingKey == candidate.key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            )
                            .clickable(enabled = fetchingKey == null) { selectCandidate(candidate) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
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
                        when {
                            fetching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            selected -> Text(
                                stringResource(R.string.lyrics_search_selected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            else -> Text(
                                stringResource(R.string.lyrics_search_select),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            selectedLyrics?.let { lyrics ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(
                        Modifier
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.lyrics_search_preview, lyrics.lines.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            lyrics.lines.take(10).joinToString("\n") { it.text.ifBlank { " " } },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (manual && onRestore != null) {
                    TextButton(onClick = onRestore) {
                        Text(stringResource(R.string.lyrics_search_restore))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { selectedText?.let(onApply) },
                    enabled = selectedText != null && fetchingKey == null,
                ) {
                    Text(stringResource(R.string.lyrics_search_use))
                }
            }
        }
    }
}
