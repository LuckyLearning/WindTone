package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 时长格式化。
 * 原实现走 `String.format` + `Formatter` 解析，列表里每行每帧都要调用一次，
 * 这里改成手写拼接，并顺手支持超过一小时的长度（1:02:03）。
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return buildString(8) {
        if (m >= 60) {
            append(m / 60)
            append(':')
            val mm = m % 60
            if (mm < 10) append('0')
            append(mm)
        } else {
            append(m)
        }
        append(':')
        if (s < 10) append('0')
        append(s)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "-"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).toInt()} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 专辑封面：有内嵌图就用图，没有就画一个唱片占位。
 *
 * 走 [CoverRepository.cover]，它带「解码后位图」的 LRU 缓存。
 * 此前每次滚回可见区域都要重新解码一遍封面，是长列表掉帧的主要来源之一。
 */
@Composable
fun CoverArt(
    song: Song?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    circular: Boolean = false,
) {
    val appContext = LocalContext.current.applicationContext
    val density = LocalDensity.current
    var bitmap by remember(song?.stableKey, size) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(song?.stableKey, size) {
        bitmap = null
        val s = song ?: return@LaunchedEffect
        val targetPx = with(density) { size.roundToPx() } * 2
        bitmap = runCatching { CoverRepository.cover(appContext, s, targetPx) }.getOrNull()
    }

    val shape = if (circular) CircleShape else RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                modifier = Modifier.size(size * 0.4f),
            )
        }
    }
}

// ================================================================ 歌曲行

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val tint = if (isCurrent) scheme.primary else scheme.onSurface

    Surface(
        color = if (isCurrent) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick?.invoke()
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            CoverArt(song = song, size = 48.dp, cornerRadius = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = tint,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.artistOrUnknown,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    LyricBadge(if (song.hasSidecarLyric) "LRC" else "内嵌", subtle = !song.hasSidecarLyric)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            when {
                onRemove != null -> IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "从歌单移除",
                        tint = scheme.onSurfaceVariant,
                    )
                }
                // 长按才能操作发现性太差，这里给一个显式的「更多」入口
                onMore != null -> IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricBadge(text: String, subtle: Boolean = false) {
    Surface(
        color = if (subtle) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            color = if (subtle) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

// ================================================================ 迷你播放条

/**
 * 底部迷你播放条。
 *
 * 改动：从「贴底直角区块」改成浮动圆角卡片，并补上了三处易用性缺口——
 * 进度条可点击/拖拽跳转、封面左右滑切歌、双击暂停。
 */
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progressFlow: StateFlow<PlaybackProgress>,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (song == null) return
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    Surface(
        color = scheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onExpand),
    ) {
        Column {
            MiniProgressBar(
                progressFlow = progressFlow,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, bottom = 6.dp),
            ) {
                // 封面区域：左右滑切歌 + 双击暂停
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .swipeAndDoubleTap(
                            onSwipeNext = onNext,
                            onSwipePrevious = onPrevious,
                            onDoubleTap = onToggle,
                        ),
                ) {
                    CoverArt(song = song, size = 46.dp, cornerRadius = 12.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        song.artistOrUnknown,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle, modifier = Modifier.size(46.dp)) {
                    Icon(
                        imageVector = if (isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = scheme.onSurface,
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(46.dp)) {
                    Icon(
                        imageVector = AppIcons.SkipNext,
                        contentDescription = "下一首",
                        tint = scheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 迷你条上的细进度条：可点击跳转、可拖拽拖动 */
@Composable
private fun MiniProgressBar(
    progressFlow: StateFlow<PlaybackProgress>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val scheme = MaterialTheme.colorScheme

    val drag = dragFraction
    val played: Float
    val buffered: Float
    if (drag != null) {
        played = drag
        buffered = 1f
    } else if (progress.durationMs > 0L) {
        played = (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
        buffered = (progress.bufferedMs.toFloat() / progress.durationMs).coerceIn(played, 1f)
    } else {
        played = 0f
        buffered = 0f
    }

    // pointerInput 的 block 只执行一次，闭包会捕获「当时的」lambda 实例。
    // 用 rememberUpdatedState 包一层，确保拖动结束读到的总是最新的时长。
    val commitLatest = rememberUpdatedState<(Float) -> Unit> { f ->
        dragFraction = null
        onSeek((f * progress.durationMs).toLong())
    }
    val fractionLatest = rememberUpdatedState<(Float) -> Unit> { f -> dragFraction = f }

    Canvas(
        modifier = modifier
            .height(16.dp)
            .seekable(
                onFraction = { fractionLatest.value(it) },
                onCommit = { commitLatest.value(it) },
            ),
    ) {
        val trackH = 3.dp.toPx() * (if (dragFraction != null) 1.8f else 1f)
        val cy = size.height / 2f
        val radius = trackH / 2f

        drawRoundRect(
            color = scheme.surfaceVariant,
            topLeft = Offset(0f, cy - radius),
            size = Size(size.width, trackH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        if (buffered > 0f) {
            drawRoundRect(
                color = scheme.primary.copy(alpha = 0.3f),
                topLeft = Offset(0f, cy - radius),
                size = Size(size.width * buffered, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
        }
        if (played > 0f) {
            drawRoundRect(
                color = scheme.primary,
                topLeft = Offset(0f, cy - radius),
                size = Size(size.width * played, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
            drawCircle(
                color = scheme.primary,
                radius = if (dragFraction != null) 7.dp.toPx() else 4.dp.toPx(),
                center = Offset(size.width * played, cy),
            )
        }
    }
}

// ================================================================ 手势扩展

/** 横向滑动切歌 + 双击播放/暂停（迷你条封面区域用） */
private fun Modifier.swipeAndDoubleTap(
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    onDoubleTap: () -> Unit,
    threshold: Float = 70f,
): Modifier = this
    .pointerInput(Unit) {
        var accum = 0f
        detectHorizontalDragGestures(
            onDragStart = { accum = 0f },
            onHorizontalDrag = { _, amount -> accum += amount },
            onDragEnd = {
                if (accum < -threshold) onSwipeNext() else if (accum > threshold) onSwipePrevious()
            },
        )
    }
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }

/** 进度条：可拖拽、可点击跳转；拖动中实时回调比例，结束时提交 */
internal fun Modifier.seekable(
    onFraction: (Float) -> Unit,
    onCommit: (Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        var last = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                last = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                onFraction(last)
            },
            onHorizontalDrag = { change, _ ->
                last = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                onFraction(last)
            },
            onDragEnd = { onCommit(last) },
            onDragCancel = { onCommit(last) },
        )
    }
    .pointerInput(Unit) {
        detectTapGestures(onTap = { offset ->
            val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
            onFraction(f)
            onCommit(f)
        })
    }

// ================================================================ 空态

/**
 * 空态：Canvas 手绘唱片插画 + 明确的行动按钮。
 * 原来只有一段灰色文字，用户看到后不知道下一步该做什么。
 */
@Composable
fun EmptyHint(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyVinyl(size = 128.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, shape = MaterialTheme.shapes.large) {
                Text(actionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondaryAction, shape = MaterialTheme.shapes.large) {
                Text(secondaryActionLabel)
            }
        }
    }
}

/** 用 Canvas 画的简约唱片插画（避免引入任何图片资源） */
@Composable
private fun EmptyVinyl(size: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primaryContainer,
                        scheme.secondaryContainer,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f
            val ring = scheme.onPrimaryContainer.copy(alpha = 0.10f)
            for (i in 1..4) {
                drawCircle(
                    color = ring,
                    radius = r * (0.42f + i * 0.12f),
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(
                color = scheme.primary.copy(alpha = 0.85f),
                radius = r * 0.20f,
                center = c,
            )
            drawCircle(
                color = scheme.onPrimaryContainer.copy(alpha = 0.35f),
                radius = r * 0.055f,
                center = c,
            )
        }
        Icon(
            imageVector = AppIcons.MusicNote,
            contentDescription = null,
            tint = scheme.onPrimaryContainer.copy(alpha = 0.75f),
            modifier = Modifier
                .size(size * 0.22f)
                .align(Alignment.BottomEnd)
                .padding(end = size * 0.14f, bottom = size * 0.14f),
        )
    }
}

// ================================================================ 骨架屏

/** 扫描中的骨架屏：8 行占位 + 微光扫过，替代原来单调的转圈 */
@Composable
fun LibrarySkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmerBrush()
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        repeat(8) { index ->
            val dim = 1f - (index % 4) * 0.12f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .alpha(dim)
                        .background(shimmer)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .alpha(dim)
                            .background(shimmer)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.35f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .alpha(dim * 0.8f)
                            .background(shimmer)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(30.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .alpha(dim * 0.8f)
                        .background(shimmer)
                )
            }
        }
    }
}

@Composable
private fun rememberShimmerBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surfaceContainerHighest
    val highlight = scheme.surfaceContainerLow

    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    return remember(progress, base, highlight) {
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(progress * 600f - 300f, 0f),
            end = Offset(progress * 600f, 300f),
        )
    }
}

/** 分组 / 歌单列表右侧的数量胶囊 */
@Composable
fun CountPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}
