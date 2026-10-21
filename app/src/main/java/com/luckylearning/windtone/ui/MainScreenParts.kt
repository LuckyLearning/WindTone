package com.luckylearning.windtone.ui

/**
 * 主界面的子组件（MainScreen.kt 的拆出文件，2026-10-21）。
 *
 * 收的都是「签名自包含、与 MainScreen 主体不共享局部状态」的 composable：
 *  - [PlayerPane]：播放页在手机（全屏覆盖层）与平板右栏（面板）两处的薄封装，
 *    避免把 20 多个回调抄两遍；
 *  - [SelectionBar] + [SelectionAction]：多选态底部操作栏（顶替迷你播放条与底部导航栏）；
 *  - [SaveQueueAsPlaylistDialog]：把当前队列存成歌单的弹窗；
 *  - [defaultPlaylistName]：预填名（`队列 10-15 14:30`）。
 *
 * 为什么不继续拆：`MainScreen` 主体是四路状态（library / player / update / maintenance）
 * 的编排中枢， DeepLink、权限申请、抽屉、各类 BackHandler 全在里面，
 * 硬抽要穿几十个参数 —— 那部分留在 MainScreen.kt（见 docs/数据层重构规划.md §13）。
 */

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放页在手机上是全屏覆盖层、在平板右栏里是一块面板，两处调用只差一个 `pane`。
 * 抽成一个薄封装，避免把 20 多个回调抄两遍（抄两遍就一定会有一处漏改）。
 */
@Composable
internal fun PlayerPane(
    playerVm: PlayerViewModel,
    player: com.luckylearning.windtone.state.PlayerUiState,
    library: com.luckylearning.windtone.state.LibraryUiState,
    lib: LibraryViewModel,
    canDrawOverlay: Boolean,
    onShowQueue: () -> Unit,
    onShowAudioInfo: () -> Unit,
    onExportLyrics: ((android.net.Uri) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        PlayerScreen(
            state = player,
            progressFlow = playerVm.progress,
            onClose = playerVm::closePlayer,
            onToggle = playerVm::togglePlay,
            onNext = playerVm::next,
            onPrevious = playerVm::previous,
            onSeekTo = playerVm::seekTo,
            onCyclePlayMode = playerVm::cyclePlayMode,
            onLyricOffset = playerVm::setLyricOffset,
            onResetLyricOffset = playerVm::resetLyricOffset,
            onLyricOffsetAbsolute = playerVm::setLyricOffsetAbsolute,
            onLyricScale = playerVm::setLyricScale,
            onShowQueue = onShowQueue,
            onSleep = playerVm::sleepAfter,
            onSleepEndOfSong = playerVm::sleepEndOfSong,
            onSleepEndOfAlbum = playerVm::sleepEndOfAlbum,
            onCancelSleep = playerVm::cancelSleep,
            onSpeed = playerVm::setSpeed,
            onPitch = playerVm::setPitchSemitones,
            onExportLyrics = onExportLyrics ?: { playerVm.exportLyrics(it) },
            onShowAudioInfo = onShowAudioInfo,
            // 车载沉浸态常驻歌名行的红心：收藏状态存在 LibraryUiState 里，
            // 用 stableKey 做集合查找，和长按菜单保持一致。
            isFavorite = player.current?.let { it.stableKey in library.favoriteUris } == true,
            onToggleFavorite = lib::toggleFavorite,
            // 播放设置弹层里的悬浮歌词快捷开关（与设置页同一个开关、同一套授权逻辑）
            canDrawOverlay = canDrawOverlay,
            onLyricOverlay = playerVm::setLyricOverlay,
            onLyricsPaneVisible = playerVm::setLyricsPaneVisible,
            pane = onExportLyrics == null,
        )
    }
}
/**
 * 多选态底部操作栏。
 *
 * 位置：**顶替**迷你播放条与底部导航栏（而不是叠在上面）——多选是一个短暂的、
 * 有明确开始与结束的状态，把它和"日常导航"混在一起只会让人误点。
 */
@Composable
internal fun SelectionBar(
    count: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onEnqueue: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteFiles: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasSelection = count > 0
    Surface(color = scheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = "退出多选", tint = scheme.onSurface)
                }
                Text(
                    if (hasSelection) "已选 $count 首" else "请选择歌曲",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onInvert) { Text("反选") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
            ) {
                SelectionAction(AppIcons.PlaylistAdd, "加入歌单", hasSelection, onAddToPlaylist)
                SelectionAction(AppIcons.FavoriteBorder, "收藏", hasSelection, onFavorite)
                SelectionAction(AppIcons.QueueMusic, "加入队列", hasSelection, onEnqueue)
                SelectionAction(AppIcons.VisibilityOff, "移出曲库", hasSelection, onRemoveFromLibrary, danger = true)
                SelectionAction(Icons.Filled.Close, "删除文件", hasSelection, onDeleteFiles, danger = true)
            }
        }
    }
}
@Composable
internal fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
/** 把当前队列存成歌单：给个预填名，省得每次手打 */
@Composable
internal fun SaveQueueAsPlaylistDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultPlaylistName()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把当前队列存为歌单") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "将把队列里的 $count 首歌按当前顺序保存成一个新歌单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("歌单名称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
/** 默认歌单名：`队列 10-15 14:30`（按时间命名，用户可改） */
internal fun defaultPlaylistName(): String {
    val now = java.util.Calendar.getInstance()
    val md = "%02d-%02d".format(now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
    val hm = "%02d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
    return "队列 $md $hm"
}
