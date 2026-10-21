package com.luckylearning.windtone.ui

import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayMode
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.ui.theme.AppVisual
import com.luckylearning.windtone.ui.theme.windShadow
import kotlin.math.abs

/**
 * 播放页的自绘零件（PlayerScreen.kt 的拆出文件，2026-10-21）。
 *
 * 收的都是「签名自包含、与播放页主体不共享局部状态」的 composable：
 *  - [PlaybackSlider]：自绘进度条（缓冲 / 拖动气泡 / 风之渐变）；
 *  - [TimeLabel]：时间文字（单独抽是为了让它只在需要的时间刻度上重组）；
 *  - [CoverPane]：封面面板（双击暂停 / 左右滑切歌 / 上下滑调音量）；
 *  - [PlayMode.icon]：播放模式 → 图标。
 *
 * 为什么不继续拆：播放页主体（沉浸态计时、音量手势、顶栏、歌名行、控制行）
 * 共享 20+ 个局部状态，硬抽出来要穿几十个参数，可读性反而更差——
 * 那部分留在 PlayerScreen.kt 里（见 docs/数据层重构规划.md §13）。
 */


/**
 * 播放进度条（自绘）。
 *
 * 相比默认 Slider：
 *  - 能显示缓冲进度（默认 Slider 只有单色轨道）；
 *  - 拖动时轨道与圆点放大，反馈更明确；
 *  - 时间气泡跟随拖动位置。
 */
@Composable
internal fun PlaybackSlider(
    progressState: State<PlaybackProgress>,
    fallbackDurationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val progress = progressState.value
    val scheme = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val duration = if (progress.durationMs > 0L) progress.durationMs else fallbackDurationMs
    val playedFraction = if (dragging) dragFraction
    else if (duration > 0L) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    else 0f
    val bufferedFraction = if (duration > 0L)
        (progress.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val seekCommit = rememberUpdatedState<(Float) -> Unit> { f ->
        dragging = false
        if (duration > 0L) onSeekTo((f * duration).toLong())
    }
    val seekFraction = rememberUpdatedState<(Float) -> Unit> { f ->
        dragging = true
        dragFraction = f
    }

    Box {
        if (dragging) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.primary,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Text(
                    formatDuration((playedFraction * duration).toLong()),
                    color = scheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .seekable(
                    onFraction = { seekFraction.value(it) },
                    onCommit = { seekCommit.value(it) },
                ),
        ) {
            val cy = size.height / 2f
            val h = if (dragging) 6.dp.toPx() else 4.dp.toPx()
            val r = h / 2f
            val radius = CornerRadius(r, r)

            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(0f, cy - r),
                size = Size(size.width, h),
                cornerRadius = radius,
            )
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = scheme.primary.copy(alpha = 0.30f),
                    topLeft = Offset(0f, cy - r),
                    size = Size(size.width * bufferedFraction, h),
                    cornerRadius = radius,
                )
            }
            if (playedFraction > 0f) {
                drawRoundRect(
                    // v2：播放进度用风之渐变
                    brush = AppVisual.WindGradient,
                    topLeft = Offset(0f, cy - r),
                    size = Size(size.width * playedFraction, h),
                    cornerRadius = radius,
                )
            }
            val thumbX = size.width * playedFraction
            if (dragging) {
                drawCircle(
                    color = scheme.primary.copy(alpha = 0.22f),
                    radius = 15.dp.toPx(),
                    center = Offset(thumbX, cy),
                )
            }
            drawCircle(
                color = scheme.primary,
                radius = if (dragging) 9.dp.toPx() else 6.dp.toPx(),
                center = Offset(thumbX, cy),
            )
            drawCircle(
                color = scheme.onPrimary,
                radius = if (dragging) 3.5.dp.toPx() else 2.2.dp.toPx(),
                center = Offset(thumbX, cy),
            )
        }
    }
}
/**
 * 时间文字。
 * 单独抽出来是为了让它只在需要的时间刻度上重组，而不是让整个播放页跟着进度刷。
 */
@Composable
internal fun TimeLabel(
    progressState: State<PlaybackProgress>,
    fallbackDurationMs: Long,
    showPosition: Boolean,
) {
    val progress = progressState.value
    val duration = if (progress.durationMs > 0L) progress.durationMs else fallbackDurationMs
    Text(
        text = formatDuration(if (showPosition) progress.positionMs else duration),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
// ================================================================ 封面面板

/**
 * 封面面板：双击播放暂停、左右滑切歌、上下滑调音量。
 *
 * 三个手势共用一个 drag 检测器，按首次位移的主轴方向锁定，
 * 避免「斜着滑」时同时触发切歌与音量。
 */
@Composable
internal fun CoverPane(
    song: Song?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolumeDelta: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // 播放时缓慢呼吸，让静止的画面有一点生命力
    val transition = rememberInfiniteTransition(label = "coverBreath")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPlaying) 1.025f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var accumX = 0f
                var accumY = 0f
                var axis = 0 // 0 未定 / 1 横向 / 2 纵向
                detectDragGestures(
                    onDragStart = {
                        accumX = 0f
                        accumY = 0f
                        axis = 0
                    },
                    onDragEnd = {
                        if (axis == 1) {
                            if (accumX < -90f) onNext() else if (accumX > 90f) onPrevious()
                        }
                    },
                    onDrag = { change, drag ->
                        accumX += drag.x
                        accumY += drag.y
                        if (axis == 0 && (abs(accumX) > 14f || abs(accumY) > 14f)) {
                            axis = if (abs(accumX) > abs(accumY)) 1 else 2
                        }
                        if (axis == 2) {
                            // 向上滑增大音量：每 42px 一档
                            onVolumeDelta(-drag.y / 42f)
                            accumY = 0f
                        }
                        change.consume()
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onToggle() })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                }
                .size(292.dp)
                .windShadow(level = 3, shape = RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(song = song, size = 292.dp, cornerRadius = 24.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = song?.albumOrUnknown ?: "",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = song?.displayName ?: "",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerHigh.copy(alpha = 0.9f),
        ) {
            Text(
                text = "双击暂停 · 左右滑切歌 · 上下滑调音量",
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}
/** 播放模式 → 图标（顺序 / 列表循环 / 单曲循环 / 随机） */
internal val PlayMode.icon: ImageVector
    get() = when (this) {
        PlayMode.SEQUENTIAL -> AppIcons.Sequential
        PlayMode.REPEAT_ALL -> AppIcons.Repeat
        PlayMode.REPEAT_ONE -> AppIcons.RepeatOne
        PlayMode.SHUFFLE -> AppIcons.Shuffle
    }
