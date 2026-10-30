package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.DuplicateGroup
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.ui.theme.windShadow

/**
 * 重复歌曲清理（批次 10.2）。
 *
 * 与「删除文件」的区别必须说清楚，所以文案里反复强调：这里**只把重复的那几份
 * 移出曲库**（打排除标记），文件、收藏、播放统计都还在，随时能在设置里恢复。
 * 一个会动用户磁盘的批量操作，界面最该做的不是"让你确信它很快"，
 * 而是"让你确信它不会做你没让它做的事"。
 */
@Composable
fun DuplicateCleanupScreen(
    state: LibraryUiState,
    onClose: () -> Unit,
    onScan: () -> Unit,
    onClean: () -> Unit,
    onClear: () -> Unit,
) {
    BackHandler { onClose() }
    var showConfirm by remember { mutableStateOf(false) }
    val groups = state.duplicates
    val removable = groups.sumOf { it.duplicates.size }
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    stringResource(R.string.duplicate_cleanup),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = if (wide) 900.dp else 720.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 18.dp),
            ) {
                Text(
                    stringResource(R.string.dup_rules),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(14.dp))

                when {
                    state.duplicatesScanning -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dup_scanning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    !state.duplicatesScanned -> {
                        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dup_start_scan))
                        }
                    }

                    groups.isEmpty() -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                                .windShadow(level = 1, shape = RoundedCornerShape(12.dp)),
                        ) {
                            Text(
                                stringResource(R.string.dup_none),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dup_rescan))
                        }
                    }

                    else -> {
                        Text(
                            stringResource(R.string.dup_found, groups.size, removable),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))

                        groups.forEach { group ->
                            GroupCard(group)
                            Spacer(Modifier.height(10.dp))
                        }

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { showConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.dup_remove_n, removable))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onClear) {
                                Text(stringResource(R.string.dup_clear_results))
                            }
                            TextButton(onClick = onScan) {
                                Text(stringResource(R.string.dup_rescan))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.dup_confirm_title, removable)) },
            text = {
                Text(
                    stringResource(R.string.dup_confirm_body, removable),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClean()
                        showConfirm = false
                    },
                ) { Text(stringResource(R.string.dup_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 一组重复：先列"保留"，再列"移出"，顺序本身就是答案 */
@Composable
private fun GroupCard(group: DuplicateGroup) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().windShadow(level = 1, shape = RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                group.label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            CopyRow(group.keep, keep = true)
            group.duplicates.forEach { CopyRow(it, keep = false) }
        }
    }
}

@Composable
private fun CopyRow(song: Song, keep: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Box(
            Modifier
                .width(44.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (keep) scheme.primaryContainer else scheme.errorContainer.copy(alpha = 0.6f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (keep) stringResource(R.string.dup_keep) else stringResource(R.string.dup_remove),
                fontSize = 10.sp,
                color = if (keep) scheme.onPrimaryContainer else scheme.onErrorContainer,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.dup_meta,
                    formatFileSize(song.sizeBytes),
                    formatDuration(song.durationMs),
                    com.luckylearning.windtone.data.MusicRepository.sourceLabel(song.source),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (keep) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
