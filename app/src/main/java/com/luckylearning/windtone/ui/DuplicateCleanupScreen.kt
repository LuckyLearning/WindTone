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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.DuplicateGroup
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState

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
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "重复歌曲清理",
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
                    "判定口径：标题 + 歌手 + 时长（精确到秒）三者完全一致才算重复 —— " +
                        "刻意保守，宁可漏判也不会把《加州旅馆》的现场版和录音室版当成同一首。\n" +
                        "每组保留一首（优先文件更大、其次更早入库的那份），其余移出曲库。\n" +
                        "移出曲库不会删除文件，也不会丢收藏与播放统计，可在「关于与诊断」里恢复。",
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
                            "正在比对…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    !state.duplicatesScanned -> {
                        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Text("开始扫描")
                        }
                    }

                    groups.isEmpty() -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "没有发现重复歌曲。",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Text("重新扫描")
                        }
                    }

                    else -> {
                        Text(
                            "发现 ${groups.size} 组重复，可移出 $removable 首",
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
                            Text("移出这 $removable 首重复歌曲")
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onClear) { Text("清除扫描结果") }
                            TextButton(onClick = onScan) { Text("重新扫描") }
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
            title = { Text("移出 $removable 首重复歌曲？") },
            text = {
                Text(
                    "这 $removable 首会从曲库中移出（打上「已移出曲库」标记）：\n" +
                        "· 音频文件不会被删除；\n" +
                        "· 收藏、播放统计、歌单里的条目也会一并从曲库视图中隐藏，但数据保留；\n" +
                        "· 重新扫描不会再收回来，直到你在「关于与诊断 → 恢复已移出曲库的歌曲」里恢复。\n\n" +
                        "提交前请确认上面每一组「保留」的那首确实是你要留的。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClean()
                        showConfirm = false
                    },
                ) { Text("移出") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } },
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
        modifier = Modifier.fillMaxWidth(),
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
                if (keep) "保留" else "移出",
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
                "${formatFileSize(song.sizeBytes)} · ${formatDuration(song.durationMs)} · " +
                    com.luckylearning.windtone.data.MusicRepository.sourceLabel(song.source),
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
