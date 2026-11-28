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
 * **点候选即切换，但弹层不关**：
 *  - 不会在搜索完成时自动套用任何结果，必须由用户点某一个候选；
 *  - 点中后立即替换当前歌词，同时保留列表，方便继续点别的版本当场比较；
 *  - 列表右侧「当前使用」标出正在生效的那一份，「恢复自动获取」随时退回自动链路。
 *
 * 这样既不是「系统替你选」，也不会出现「选了半天却没生效」。
 */
@Composable
internal fun LyricsSearchSheet(
    song: Song?,
    manual: Boolean,
    onApply: (String) -> Boolean,
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
    var appliedKey by remember { mutableStateOf<String?>(null) }
    var selectedLyrics by remember { mutableStateOf<Lyrics?>(null) }
    var fetchingKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank() || searching) return
        searching = true
        message = null
        selectedKey = null
        appliedKey = null
        selectedLyrics = null
        scope.launch {
            results = runCatching { LyricsSearch.search(q) }.getOrDefault(emptyList())
            searching = false
            if (results.isEmpty()) message = R.string.lyrics_search_empty
        }
    }

    /** 点候选即应用；失败（取不到词 / 已切歌）只提示，不动当前歌词。 */
    fun selectCandidate(candidate: LyricsSearch.Candidate) {
        if (fetchingKey != null) return
        fetchingKey = candidate.key
        message = null
        scope.launch {
            val text = runCatching { LyricsSearch.fetchText(candidate.track) }.getOrNull()
            fetchingKey = null
            if (text.isNullOrBlank()) {
                message = R.string.lyrics_search_failed
                return@launch
            }
            val parsed = LyricsSearch.parse(text)
            if (parsed.isEmpty) {
                message = R.string.lyrics_search_failed
                return@launch
            }
            if (onApply(text)) {
                selectedKey = candidate.key
                appliedKey = candidate.key
                selectedLyrics = parsed
            } else {
                message = R.string.lyrics_search_song_changed
            }
        }
    }

    // 打开即按当前歌名 / 歌手搜一次，减少一次点击；用户仍可改关键词重搜。
    // 换歌时也把上一首的选择 / 预览清掉，避免把 A 的结果看成 B 的。
    LaunchedEffect(song?.stableKey) {
        selectedKey = null
        appliedKey = null
        selectedLyrics = null
        message = null
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
                    .heightIn(max = 250.dp)
                    .padding(top = 4.dp),
            ) {
                items(results, key = { it.key }) { candidate ->
                    val fetching = fetchingKey == candidate.key
                    val applied = appliedKey == candidate.key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (applied) MaterialTheme.colorScheme.primaryContainer
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
                            applied -> Text(
                                stringResource(R.string.lyrics_search_current),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            else -> Text(
                                stringResource(R.string.lyrics_search_switch),
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
                            .heightIn(max = 130.dp)
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
                            lyrics.lines.take(8).joinToString("\n") { it.text.ifBlank { " " } },
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
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.lyrics_search_done))
                }
            }
        }
    }
}
