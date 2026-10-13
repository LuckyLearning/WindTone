package com.luckylearning.windtone.ui

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onLyricOffset: (Int) -> Unit,
    onResetLyricOffset: () -> Unit,
    onLyricScale: (Float) -> Unit,
    onShowQueue: () -> Unit,
    onSleep: (Int) -> Unit,
    onSleepEndOfSong: () -> Unit,
    onCancelSleep: () -> Unit,
    onSpeed: (Float) -> Unit,
    onExportLyrics: (android.net.Uri) -> Unit,
) {
    var showLyrics by remember { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    val lyricsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) onExportLyrics(uri) }

    // ---------------------------------------------------------- 音量手势
    val appContext = LocalContext.current.applicationContext
    val audioManager = remember(appContext) {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumePercent by remember { mutableStateOf<Int?>(null) }
    var volumeTick by remember { mutableIntStateOf(0) }
    var volumeAccum by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(volumeTick) {
        if (volumeTick > 0) {
            delay(1200)
            volumePercent = null
        }
    }

    val onVolumeDelta: (Float) -> Unit = { delta ->
        volumeAccum += delta
        val steps = volumeAccum.toInt()
        if (steps != 0) {
            volumeAccum -= steps
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next = (current + steps).coerceIn(0, maxVolume)
            if (next != current) {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
            }
            volumePercent = next * 100 / maxVolume
            volumeTick++
        }
    }

    val song = state.current
    val scheme = MaterialTheme.colorScheme

    // 顶层只「收集」不「读取」：State 对象可以安全地传给子组件，
    // Compose 的失效是跟着 .value 的读取点走的，所以播放页本身不会随进度重组，
    // 只有真正读它的进度条 / 时间文字 / 歌词面板会重组。
    val progressState = progressFlow.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // 背景：封面主色 → 背景色的垂直渐变，营造氛围。
        // 注意必须用「不透明混色」而不是 copy(alpha=)：播放页是覆盖在主界面之上的，
        // 半透明背景会把底下的歌曲列表和歌词透出来，整个页面糊成一片（v3.0.1 修复）。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            lerp(scheme.background, scheme.primary, 0.32f),
                            lerp(scheme.background, scheme.primary, 0.10f),
                            scheme.background,
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            // ------------------------------------------------------ 顶栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "收起播放页",
                        tint = scheme.onSurface,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song?.title ?: "未在播放",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = song?.artistOrUnknown ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = onShowQueue) {
                    Icon(
                        imageVector = AppIcons.QueueMusic,
                        contentDescription = "播放队列",
                        tint = scheme.onSurface,
                    )
                }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        imageVector = if (showLyrics) AppIcons.Album else AppIcons.Lyrics,
                        contentDescription = if (showLyrics) "切换为封面" else "切换为歌词",
                        tint = scheme.onSurface,
                    )
                }
                IconButton(onClick = { showOptions = true }) {
                    Icon(
                        imageVector = AppIcons.Tune,
                        contentDescription = "播放设置",
                        tint = scheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // --------------------------------------------- 中部：封面 / 歌词
            Box(Modifier.weight(1f)) {
                if (showLyrics) {
                    LyricsPane(
                        lyrics = state.lyrics,
                        loading = state.lyricsLoading,
                        progressState = progressState,
                        offsetMs = state.lyricOffsetMs,
                        scale = state.lyricScale,
                        onSeekTo = onSeekTo,
                    )
                } else {
                    CoverPane(
                        song = song,
                        isPlaying = state.isPlaying,
                        onToggle = onToggle,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onVolumeDelta = onVolumeDelta,
                    )
                }
            }

            // ------------------------------------------------------ 进度条
            PlaybackSlider(
                progressState = progressState,
                fallbackDurationMs = state.durationMs,
                onSeekTo = onSeekTo,
            )

            val durationFallback = state.durationMs
            Row(Modifier.fillMaxWidth()) {
                TimeLabel(progressState, durationFallback, showPosition = true)
                Spacer(Modifier.weight(1f))
                song?.let {
                    Text(
                        text = com.luckylearning.windtone.data.MusicRepository.sourceLabel(it.source),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                TimeLabel(progressState, durationFallback, showPosition = false)
            }

            Spacer(Modifier.height(4.dp))

            // ------------------------------------------------------ 控制区
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = AppIcons.Shuffle,
                        contentDescription = if (state.shuffle) "关闭随机播放" else "开启随机播放",
                        tint = if (state.shuffle) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = AppIcons.SkipPrevious,
                        contentDescription = "上一首",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(34.dp),
                    )
                }
                FilledIconButton(
                    onClick = onToggle,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    modifier = Modifier
                        .size(66.dp)
                        .shadow(10.dp, CircleShape),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = AppIcons.SkipNext,
                        contentDescription = "下一首",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne
                        else AppIcons.Repeat,
                        contentDescription = "循环模式",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) scheme.onSurfaceVariant
                        else scheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ------------------------------------------------------ 状态信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val src = state.lyrics.source
                LyricSourceChip(label = src.label)
                if (state.lyricOffsetMs != 0) {
                    Spacer(Modifier.width(6.dp))
                    LyricSourceChip(label = "偏移 ${state.lyricOffsetMs}ms")
                }
                if (state.lyrics.hasWordTiming) {
                    Spacer(Modifier.width(6.dp))
                    LyricSourceChip(label = "逐字")
                }
                if (state.sleepRemainingMs > 0) {
                    Spacer(Modifier.width(6.dp))
                    LyricSourceChip(
                        label = "睡眠 %d:%02d".format(
                            state.sleepRemainingMs / 60000,
                            (state.sleepRemainingMs % 60000) / 1000,
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "队列 ${state.queueSize} 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        // 音量提示浮层
        volumePercent?.let { pct ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = scheme.inverseSurface.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 40.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.VolumeUp,
                        contentDescription = null,
                        tint = scheme.inverseOnSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "$pct%",
                        color = scheme.inverseOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    if (showOptions) {
        PlayerOptionsSheet(
            speed = state.speed,
            lyricScale = state.lyricScale,
            lyricOffsetMs = state.lyricOffsetMs,
            sleepRemainingMs = state.sleepRemainingMs,
            onSpeed = onSpeed,
            onLyricScale = onLyricScale,
            onLyricOffset = onLyricOffset,
            onResetOffset = onResetLyricOffset,
            onOpenEq = { showOptions = false; showEq = true },
            onOpenSleep = { showOptions = false; showSleep = true },
            onExportLyrics = {
                showOptions = false
                lyricsExportLauncher.launch("${song?.baseName ?: "歌词"}.lrc")
            },
            onDismiss = { showOptions = false },
        )
    }
    if (showEq) EqSheet(onClose = { showEq = false })
    if (showSleep) SleepSheet(
        onClose = { showSleep = false },
        onSet = { onSleep(it); showSleep = false },
        onEndOfSong = { onSleepEndOfSong(); showSleep = false },
        onCancel = { onCancelSleep(); showSleep = false },
    )
}

// ================================================================ 自定义进度条

/**
 * 播放进度条（自绘）。
 *
 * 相比默认 Slider：
 *  - 能显示缓冲进度（默认 Slider 只有单色轨道）；
 *  - 拖动时轨道与圆点放大，反馈更明确；
 *  - 时间气泡跟随拖动位置。
 */
@Composable
private fun PlaybackSlider(
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
                    color = scheme.primary,
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
private fun TimeLabel(
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
private fun CoverPane(
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
                .shadow(18.dp, RoundedCornerShape(24.dp))
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
