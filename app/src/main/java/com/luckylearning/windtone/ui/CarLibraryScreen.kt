package com.luckylearning.windtone.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlaybackProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * 车机版首页的三块自绘组件（顶栏 / 视图 Tab / 底部大播放条）。
 *
 * 为什么要单独一套，而不是把手机版撑大：
 *  · 抽屉是「点一下 → 再点一下」两级导航，车机上第二级的目标位置还会变 —— 改成横向 Tab 一步到位；
 *  · 手机版顶栏有抽屉、排序、⋮ 三个入口挤在标题旁，行车中找不到；车机只留「搜索 + 设置」；
 *  · 底部播放条要 112dp、主键 88dp、带收藏与明确的时间，手机版迷你条（46dp 封面）在 1707dp 屏上太小。
 *
 * 视图切换仍然只改 `LibraryUiState.view`，列表渲染完全复用手机版那套（`SongsList` 等），
 * 车载只是把行高、字号、触控目标放大 —— 见 `LibraryLists.SongsList(carMode = true)`。
 *
 * 布局约束（不要再踩）：
 *  · 车机底部 Dock 是车机自己画的浮层，`WindowInsets` 不报，必须由 `MainScreen` 留出 56dp；
 *  · 单栏到底，**不做左右分栏**（v1.1.14 用户明确否决）；
 *  · 内容区限宽 [CAR_CONTENT_MAX_WIDTH]，但底部播放条与顶栏保持横贯全宽。
 */
internal val CAR_TOUCH_TARGET: Dp = 76.dp
internal val CAR_BAR_HEIGHT: Dp = 112.dp
private val CAR_COVER_SIZE: Dp = 80.dp
private val CAR_MAIN_BUTTON: Dp = 88.dp

/** 车机版视图名：比抽屉里的短名更明确（「歌曲」→「全部歌曲」），标签只在这里定义一份 */
internal fun carViewLabel(view: LibraryView): String = when (view) {
    LibraryView.SONGS -> "全部歌曲"
    LibraryView.ALBUM -> "专辑"
    LibraryView.ARTIST -> "歌手"
    LibraryView.FOLDER -> "文件夹"
    LibraryView.PLAYLIST -> "歌单"
}

/** 车机首页顶栏：标题 + 首数 + 搜索 / 设置。搜索就地展开，不跳页、不弹键盘二级页 */
@Composable
internal fun CarLibraryHeader(
    library: LibraryUiState,
    onQuery: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var searchMode by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(library.query) }
    val focus = remember { FocusRequester() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        if (searchMode) {
            TextField(
                value = input,
                onValueChange = {
                    input = it
                    onQuery(it)
                },
                singleLine = true,
                placeholder = {
                    Text(
                        "搜索歌名 / 歌手 / 专辑 / 拼音首字母",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall,
                shape = MaterialTheme.shapes.large,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceContainerHigh,
                    unfocusedContainerColor = scheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
            )
            CarIconButton(onClick = { onSubmitSearch(input) }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = scheme.onBackground)
            }
            CarIconButton(onClick = {
                input = ""
                onQuery("")
                searchMode = false
            }) {
                Icon(Icons.Filled.Close, contentDescription = "退出搜索", tint = scheme.onBackground)
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Text(
                text = "本地音乐",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
            )
            if (library.songs.isNotEmpty()) {
                Spacer(Modifier.width(14.dp))
                Surface(
                    color = scheme.secondaryContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "${library.songs.size} 首",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            CarIconButton(onClick = { searchMode = true }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = scheme.onBackground)
            }
            CarIconButton(onClick = onOpenSettings) {
                Icon(AppIcons.Tune, contentDescription = "设置", tint = scheme.onBackground)
            }
        }
    }
}

/** 视图 Tab：五个浏览视图横向平铺，一步到位（抽屉要点两次） */
@Composable
internal fun CarViewTabs(
    current: LibraryView,
    onSelect: (LibraryView) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
    ) {
        LibraryView.entries.forEach { view ->
            val selected = view == current
            var focused by remember { mutableStateOf(false) }
            Surface(
                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
                contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                shape = RoundedCornerShape(14.dp),
                border = if (focused || selected) {
                    androidx.compose.foundation.BorderStroke(2.dp, scheme.primary)
                } else {
                    null
                },
                modifier = Modifier
                    .height(64.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(view) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 26.dp),
                ) {
                    Text(
                        text = carViewLabel(view),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * 车机底部大播放条：封面 80dp + 歌名 / 歌手 + 收藏 / 上一首 / 播放暂停 / 下一首 + 进度与时间。
 *
 * 刻意**不放**「歌词 / 进播放页」快捷键（用户 2026-10-17 明确要求删掉）：
 * 进播放页本来就是看歌词，播放条只留最基础的控制，少一个入口就少一处行车中的误触；
 * 点封面或歌名仍然可以进播放页。
 */
@Composable
internal fun CarPlayerBar(
    song: Song?,
    isPlaying: Boolean,
    progressFlow: StateFlow<PlaybackProgress>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    if (song == null) return
    val scheme = MaterialTheme.colorScheme
    val progress by progressFlow.collectAsStateWithLifecycle()

    Surface(color = scheme.surfaceContainerLow) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(CAR_BAR_HEIGHT)
                .padding(horizontal = 20.dp),
        ) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onExpand)) {
                CoverArt(song = song, size = CAR_COVER_SIZE, cornerRadius = 16.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .clickable(onClick = onExpand),
            ) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = song.artistOrUnknown,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            CarIconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
            }
            CarIconButton(onClick = onPrevious) {
                Icon(AppIcons.SkipPrevious, contentDescription = "上一首", tint = scheme.onSurface)
            }
            PlayButton(isPlaying = isPlaying, onClick = onToggle)
            CarIconButton(onClick = onNext) {
                Icon(AppIcons.SkipNext, contentDescription = "下一首", tint = scheme.onSurface)
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1.6f)) {
                CarProgressBar(progress = progress, onSeek = onSeek)
            }
        }
    }
}

/** 车机主播放键：88dp 实底圆键，行车中不瞄也能按中 */
@Composable
private fun PlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .size(CAR_MAIN_BUTTON)
            .clip(CircleShape)
            .background(scheme.primary)
            .then(if (focused) Modifier.border(3.dp, scheme.onSurface, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = if (isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = scheme.onPrimary,
            modifier = Modifier.size(44.dp),
        )
    }
}

/**
 * 车机图标键：76dp 触控目标 + 可见焦点环。
 *
 * 焦点环是车机专属需求 —— 方向盘按键 / 中控旋钮操作时没有触摸点，不画出来就不知道焦点在哪。
 */
@Composable
private fun CarIconButton(
    onClick: () -> Unit,
    size: Dp = CAR_TOUCH_TARGET,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(if (focused) Modifier.border(3.dp, scheme.primary, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
    ) {
        content()
    }
}

/** 车机进度条：轨道 12dp、滑块 34dp，自带「已播 / 总时长」 */
@Composable
private fun CarProgressBar(
    progress: PlaybackProgress,
    onSeek: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val drag = dragFraction
    val played = when {
        drag != null -> drag
        progress.durationMs > 0L -> (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val shownMs = if (drag != null) (drag * progress.durationMs).toLong() else progress.positionMs

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .seekable(
                    onFraction = { dragFraction = it },
                    onCommit = { f ->
                        dragFraction = null
                        onSeek((f * progress.durationMs).toLong())
                    },
                ),
        ) {
            val trackH = 12.dp.toPx()
            val cy = size.height / 2f
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            if (played > 0f) {
                drawRoundRect(
                    color = scheme.primary,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width * played, trackH),
                    cornerRadius = radius,
                )
            }
            drawCircle(
                color = scheme.primary,
                radius = 17.dp.toPx(),
                center = Offset(size.width * played, cy),
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatDuration(shownMs),
                fontSize = 16.sp,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(progress.durationMs),
                fontSize = 16.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 从 Compose 的 Context 里捞 Activity（设置里切车载模式要立刻改屏幕方向） */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
