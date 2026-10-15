package com.luckylearning.windtone.ui
import com.luckylearning.windtone.state.PlaybackProgress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.lyrics.LyricLine
import com.luckylearning.windtone.lyrics.Lyrics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * 歌词面板：
 *  - 有时间轴 -> 自动居中滚动 + 当前行高亮 + 点击某行跳转
 *  - 逐字时间轴 -> 当前卡拉OK染色
 *  - 双语 -> 主行下显示翻译副行
 *  - 纯文本 -> 普通滚动
 *  - 无歌词 -> 给出获取歌词的提示
 *
 * 性能：进度以 `State<PlaybackProgress>` 传入（延迟读取）。
 * 面板内部用 `derivedStateOf` 把「每 250ms 变一次的位置」折算成
 * 「只在换行时才变一次的行号」，因此歌词列表不再跟着进度条逐帧重组。
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics,
    loading: Boolean,
    progressState: State<PlaybackProgress>,
    offsetMs: Int,
    scale: Float,
    wordByWord: Boolean = true,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // 车载模式：字号整体再放大 [CAR_LYRIC_BOOST] 倍。
    // 车机屏幕离驾驶位更远（约 60~70cm），手机那套 15.5 / 17.5sp 在
    // 1707dp 宽屏上会显得又小又空，瞄一眼读不清。这里直接折叠进 scale，
    // 内部所有字号 / 行距计算一行都不用改。
    carMode: Boolean = false,
    /**
     * 长按某一行：回调该行的**时间戳**（纯文本歌词没有时间轴，不会触发）。
     *
     * 用来做「就唱到这句」的偏移校准（批次 10.4-②）——用户听到唱到哪一句时
     * 直接长按那一行，比在设置里按 ±100ms 试错直观得多。传 null 关闭该手势。
     */
    onLineLongPress: ((Long) -> Unit)? = null,
) {
    val effectiveScale = if (carMode) scale * CAR_LYRIC_BOOST else scale
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> LyricsLoading()

            lyrics.isEmpty -> NoLyricsHint()

            !lyrics.synced -> PlainLyrics(lyrics, effectiveScale)

            else -> SyncedLyrics(
                lyrics = lyrics,
                progressState = progressState,
                offsetMs = offsetMs,
                scale = effectiveScale,
                wordByWord = wordByWord,
                onSeekTo = onSeekTo,
                onLineLongPress = onLineLongPress,
            )
        }
    }
}

/**
 * 车载模式歌词放大系数。手机正文 15.5sp × 1.5 ≈ 23sp、当前行 17.5sp × 1.5 ≈ 26sp，
 * 与效果图 ㊲「车机歌词明显大于手机」的意图一致，同时不至于大到一行放不下几个字。
 */
private const val CAR_LYRIC_BOOST = 1.5f

/** 歌词加载中的占位：三行渐隐灰条，比转圈更贴近最终形态 */
@Composable
private fun LyricsLoading() {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == 1) 0.62f else 0.46f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.surfaceContainerHighest.copy(alpha = 0.7f))
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NoLyricsHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(28.dp),
    ) {
        Text(
            "暂无歌词",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "歌词获取顺序：\n" +
                "1. 音频同目录的同名 .lrc / .krc / .yrc 文件\n" +
                "2. 音频文件内嵌歌词（MP3 的 USLT/SYLT、FLAC/Ogg 的 LYRICS、M4A 的 ©lyr）\n\n" +
                "把歌词文件放到歌曲同一目录，或在电脑上用音乐标签工具写入内嵌歌词即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, scale: Float) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "纯文本歌词（无时间轴）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        lyrics.lines.forEach { line ->
            Text(
                text = line.text.ifEmpty { " " },
                fontSize = (15 * scale).sp,
                lineHeight = (26 * scale).sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    progressState: State<PlaybackProgress>,
    offsetMs: Int,
    scale: Float,
    wordByWord: Boolean,
    onSeekTo: (Long) -> Unit,
    onLineLongPress: ((Long) -> Unit)?,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var userPausedUntil by remember { mutableStateOf(0L) }
    var programmatic by remember { mutableStateOf(false) }

    // 关键优化：把每 250ms 刷新一次的位置，折算成「只在切换行时变」的索引。
    val indexState = remember(lyrics, offsetMs) {
        derivedStateOf { lyrics.indexAt(progressState.value.positionMs + offsetMs) }
    }
    val currentIndex by indexState

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !programmatic) {
                    // 用户手动滚动后暂停自动跟随 4 秒，避免「抢滚动」
                    userPausedUntil = System.currentTimeMillis() + 4000L
                }
            }
    }

    // 居中跟随：监听「当前行索引 + 视口高度」，任一变化都把当前行滚回视口正中。
    //
    // 为什么不能只监听 currentIndex（旧实现）：视口高度会变——车机进入沉浸态时
    // 顶栏 / 控制键收起，歌词区突然变高；初次布局时视口还是 0。这些时刻
    // 行索引没变，旧 effect 不执行，当前行就停在旧位置，看起来沉到屏幕底部。
    // snapshotFlow 在视口尺寸或索引任一变化时都会重新发射，顺带解决了
    // 「viewportH == 0 直接 return 后再也不校正」的初次布局问题。
    LaunchedEffect(listState, density, scale) {
        snapshotFlow {
            val h = listState.layoutInfo.viewportSize.height
            if (h <= 0) null else h to currentIndex
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (viewportH, index) ->
                if (index < 0) return@collect
                if (System.currentTimeMillis() < userPausedUntil) return@collect
                // 行高近似值：文字行高 25dp + 上下 padding 18dp，不必逐行精确测量
                val linePx = with(density) { (34 * scale).dp.toPx() }
                val target = -(viewportH / 2f - linePx)
                programmatic = true
                try {
                    listState.animateScrollToItem(index, target.toInt())
                } catch (_: Throwable) {
                } finally {
                    programmatic = false
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 140.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            LyricLineItem(
                line = line,
                active = index == currentIndex,
                progressState = progressState,
                offsetMs = offsetMs,
                scale = scale,
                wordByWord = wordByWord,
                onClick = { if (line.timeMs >= 0) onSeekTo(line.timeMs) },
                // 没有时间轴的行（`timeMs < 0`）不参与校准：拿它算偏移只会算出一个
                // 无意义的巨大数字，等于把偏移一次搞坏。
                onLongClick = onLineLongPress
                    ?.takeIf { line.timeMs >= 0 }
                    ?.let { cb -> { cb(line.timeMs) } },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LyricLineItem(
    line: LyricLine,
    active: Boolean,
    progressState: State<PlaybackProgress>,
    offsetMs: Int,
    scale: Float,
    wordByWord: Boolean,
    onClick: () -> Unit,
    /** 长按这一行（校准偏移用）；null = 不接管长按 */
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.42f,
        animationSpec = tween(320),
        label = "lyricAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (wordByWord && line.hasWords && active) {
                KaraokeText(
                    line = line,
                    progressState = progressState,
                    offsetMs = offsetMs,
                    scale = scale,
                )
            } else {
                Text(
                    text = line.text.ifEmpty { "♪" },
                    modifier = Modifier.alpha(alpha),
                    color = if (active) scheme.primary else scheme.onSurface,
                    fontSize = (if (active) 17.5f * scale else 15.5f * scale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = (25 * scale).sp,
                    textAlign = TextAlign.Center,
                )
            }
            line.translation?.let { tr ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tr,
                    modifier = Modifier.alpha(if (active) 0.85f else alpha * 0.8f),
                    color = if (active) scheme.primary.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    fontSize = (13 * scale).sp,
                    lineHeight = (18 * scale).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 逐字卡拉OK染色：当前行每个字按时间进度从浅色过渡到主题色 */
@Composable
private fun KaraokeText(
    line: LyricLine,
    progressState: State<PlaybackProgress>,
    offsetMs: Int,
    scale: Float,
) {
    // 只有当前行会读进度，因此也只有这一行会随播放逐帧重组
    val positionMs = progressState.value.positionMs + offsetMs

    var progress by remember(line) { mutableStateOf(0f) }
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(220),
        label = "karaoke",
    )
    LaunchedEffect(positionMs, line) {
        progress = wordProgress(line, positionMs)
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

    val annotated = buildAnnotatedString {
        for (word in line.words) {
            val start = word.startMs
            val end = start + word.durationMs.coerceAtLeast(1L)
            val wStartF = wordFraction(line, start)
            val wEndF = wordFraction(line, end)
            // 该字已着色的比例
            val filled = ((animated - wStartF) / (wEndF - wStartF).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            if (filled >= 1f) {
                pushStyle(SpanStyle(color = activeColor, fontWeight = FontWeight.SemiBold))
                append(word.text)
                pop()
            } else if (filled <= 0f) {
                pushStyle(SpanStyle(color = idleColor))
                append(word.text)
                pop()
            } else {
                // 按字符细分染色
                val chars = word.text.toCharArray()
                val cut = (filled * chars.size).toInt()
                if (cut > 0) {
                    pushStyle(SpanStyle(color = activeColor, fontWeight = FontWeight.SemiBold))
                    append(String(chars, 0, cut))
                    pop()
                }
                if (cut < chars.size) {
                    pushStyle(SpanStyle(color = idleColor))
                    append(String(chars, cut, chars.size - cut))
                    pop()
                }
            }
        }
    }

    Text(
        text = annotated,
        fontSize = (17.5 * scale).sp,
        lineHeight = (25 * scale).sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 词时间 -> 行内归一化进度（0..1） */
private fun wordFraction(line: LyricLine, ms: Long): Float {
    val total = line.lineDurationMs.coerceAtLeast(1L)
    return ((ms - line.timeMs).toFloat() / total).coerceIn(0f, 1f)
}

/** 当前行整体完成度（相对行起点的时间比例） */
private fun wordProgress(line: LyricLine, positionMs: Long): Float {
    val rel = positionMs - line.timeMs
    if (rel < 0) return 0f
    val total = line.lineDurationMs.coerceAtLeast(1L)
    return (rel.toFloat() / total).coerceIn(0f, 1f)
}

/** 简单的歌词来源提示条 */
@Composable
fun LyricSourceChip(label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
