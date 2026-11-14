package com.luckylearning.windtone.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.PlayerPane
import com.luckylearning.windtone.data.SongFacts
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.ui.theme.AppVisual
import com.luckylearning.windtone.ui.theme.windShadow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放页三页的内容（v1.2.55）。
 *
 * 页序即 [PlayerPane] 的顺序：信息 → 图片 → 歌词。三页都是「自包含、只吃传入状态」
 * 的 composable，翻页容器与手势留在 `PlayerScreen` 里 —— 与
 * `PlayerScreenParts.kt` 同一个拆分理由（见该文件头注释）。
 */

// ================================================================ 第 1 页：歌曲信息

/**
 * 歌曲信息页：标签 / 文件参数 / 所在歌单 / 播放统计。
 *
 * 数据分两路来：[song] 是曲库快照（扫描产出）；[facts] 是进页时拉一次的派生数据
 * （歌单归属 / 播放统计 / 音频参数）。派生数据没回来之前先显示已有的，
 * 不摆骨架条 —— 这里的绝大部分行本来就有值，占位反而更乱。
 */
@Composable
internal fun SongInfoPane(
    song: Song?,
    facts: SongFacts?,
    isFavorite: Boolean,
    carMode: Boolean,
    modifier: Modifier = Modifier,
) {
    if (song == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.player_not_playing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val scheme = MaterialTheme.colorScheme
    val labelSp = if (carMode) 15.sp else 12.5.sp
    val valueSp = if (carMode) 17.sp else 14.sp
    val groupSp = if (carMode) 16.sp else 13.sp
    val artSize = if (carMode) 84.dp else 60.dp
    val audio = facts?.audio

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 头部：小封面 + 歌名 / 歌手。歌名行在页面底部还有一份常驻，这里保留是为了
        // 让「信息页」自己也是一张完整的专辑卡，翻过来不至于先看到一张裸表格。
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(song = song, size = artSize, cornerRadius = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (carMode) 20.sp else 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artistOrUnknown,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = if (carMode) 16.sp else 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        InfoGroup(stringResource(R.string.info_group_tags), groupSp) {
            FactRow(stringResource(R.string.info_field_album), song.albumOrUnknown, labelSp, valueSp)
            val albumArtist = facts?.albumArtist ?: song.albumArtist
            FactRow(stringResource(R.string.info_field_album_artist), albumArtist, labelSp, valueSp)
            val year = facts?.year ?: song.year
            FactRow(stringResource(R.string.info_field_year), year?.toString(), labelSp, valueSp)
            val genre = facts?.genre ?: song.genre
            FactRow(stringResource(R.string.info_field_genre), genre, labelSp, valueSp)
            val disc = facts?.discNumber ?: song.discNumber
            val hasDisc = disc != null && disc > 0
            val trackText = when {
                hasDisc && song.trackNo > 0 ->
                    stringResource(R.string.info_field_disc_value, disc!!) + " · " +
                        stringResource(R.string.info_field_track_value, song.trackNo)
                hasDisc -> stringResource(R.string.info_field_disc_value, disc!!)
                song.trackNo > 0 -> stringResource(R.string.info_field_track_value, song.trackNo)
                else -> null
            }
            FactRow(stringResource(R.string.info_field_track), trackText, labelSp, valueSp)
        }

        InfoGroup(stringResource(R.string.info_group_file), groupSp) {
            FactRow(
                stringResource(R.string.ss_info_container),
                audio?.container ?: song.displayName.substringAfterLast('.', "").uppercase()
                    .ifBlank { null },
                labelSp, valueSp,
            )
            FactRow(stringResource(R.string.ss_info_codec), audio?.codec, labelSp, valueSp)
            FactRow(stringResource(R.string.ss_info_sample_rate), audio?.sampleRateText, labelSp, valueSp)
            FactRow(stringResource(R.string.ss_info_channels), audio?.channelsText, labelSp, valueSp)
            FactRow(stringResource(R.string.ss_info_bits), audio?.bitsText, labelSp, valueSp)
            FactRow(stringResource(R.string.ss_info_bitrate), audio?.bitrateText, labelSp, valueSp)
            FactRow(
                stringResource(R.string.ss_info_duration),
                formatDuration(if (song.durationMs > 0) song.durationMs else audio?.durationMs ?: 0L),
                labelSp, valueSp,
            )
            FactRow(
                stringResource(R.string.ss_info_size),
                audio?.sizeText ?: song.sizeBytes.takeIf { it > 0L }?.let { formatBytes(it) },
                labelSp, valueSp,
            )
            FactRow(stringResource(R.string.ss_info_path), songDisplayPath(song), labelSp, valueSp, maxLines = 4)
        }

        InfoGroup(stringResource(R.string.info_group_playlists), groupSp) {
            val names = buildList {
                if (isFavorite) add(stringResource(R.string.info_playlist_favorites))
                facts?.playlistNames?.let { addAll(it) }
            }
            if (names.isEmpty()) {
                Text(
                    text = stringResource(R.string.info_playlists_empty),
                    fontSize = valueSp,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                names.forEach { name ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = scheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        Text(
                            text = name,
                            fontSize = valueSp,
                            color = scheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        InfoGroup(stringResource(R.string.info_group_stats), groupSp) {
            FactRow(
                stringResource(R.string.info_field_play_count),
                facts?.playCount?.toString(),
                labelSp, valueSp,
            )
            FactRow(
                stringResource(R.string.info_field_last_played),
                facts?.lastPlayedAt?.takeIf { it > 0L }?.let { dateText(it) },
                labelSp, valueSp,
            )
            FactRow(
                stringResource(R.string.info_field_played_total),
                facts?.playedMs?.takeIf { it > 0L }?.let { formatDuration(it) },
                labelSp, valueSp,
            )
            FactRow(
                stringResource(R.string.info_field_added),
                song.dateAdded.takeIf { it > 0L }?.let { dateText(it) },
                labelSp, valueSp,
            )
        }

        // 底部留出页码点的位置（圆点 28dp 触控区），否则最后一行统计会被压住
        Spacer(Modifier.height(28.dp))
    }
}

/** 一组信息（标题 + 分隔行）。样式跟设置页的折叠卡一致，但这里是纯展示、不可折叠。 */
@Composable
private fun InfoGroup(
    title: String,
    titleSp: TextUnit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = titleSp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surfaceContainerLow.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
        }
    }
}

/**
 * 一行「标签 : 值」。
 *
 * [value] 为 null / 空白时显示 `—` 并压暗：缺字段和「值为空」在界面上必须能区分，
 * 直接不显示这一行会让人以为应用漏了字段。
 */
@Composable
private fun FactRow(
    label: String,
    value: String?,
    labelSp: TextUnit,
    valueSp: TextUnit,
    maxLines: Int = 2,
) {
    val scheme = MaterialTheme.colorScheme
    val display = value?.takeIf { it.isNotBlank() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = labelSp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(if (labelSp > 14.sp) 104.dp else 78.dp),
        )
        Text(
            text = display ?: "—",
            fontSize = valueSp,
            color = if (display != null) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.55f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 绝对时间（播放统计用）。`SimpleDateFormat` 非线程安全，只在组合里按需建一个。 */
@Composable
private fun dateText(epochMs: Long): String {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    return remember(epochMs) { fmt.format(Date(epochMs)) }
}

// ================================================================ 第 2 页：图片 + 当前一句

/**
 * 图片页：大封面 + 当前一句歌词。
 *
 * 与旧封面态的区别只有一处 —— 原来封面下面挂的是「专辑 / 文件名 / 手势提示」，
 * 现在挂**当前这一句**：封面是视觉锚点，一句歌词是「此刻在唱什么」的最小信息量，
 * 两者叠在一屏正好是开车时瞄一眼就能读完的东西。
 *
 * 纵向手势不再切歌（v1.2.55），整根轴让给歌词滚动（歌词页）与页面本身。
 */
@Composable
internal fun CoverLyricPane(
    song: Song?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    lyrics: Lyrics,
    progressState: State<PlaybackProgress>,
    offsetMs: Int,
    carMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    // 与 LyricsPane 同一套「进度 → 行号」折叠：位置每 250ms 变一次，但行号只在
    // 换行时才变，这样单行歌词不会跟着进度条逐帧重组。
    val currentLine by remember(lyrics, offsetMs) {
        derivedStateOf {
            if (lyrics.isEmpty || !lyrics.synced) {
                lyrics.lines.firstOrNull()?.takeIf { lyrics.lines.size == 1 }
            } else {
                val at = progressState.value.positionMs + offsetMs
                lyrics.lines.getOrNull(lyrics.indexAt(at))
            }
        }
    }

    // 播放时缓慢呼吸：静止画面有一点生命力（旧封面态同一套参数）。
    // 暂停时改走一次性动画回到 1f，避免无限动画每帧写状态。
    val transition = rememberInfiniteTransition(label = "coverBreath")
    val breath by if (isPlaying) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.025f,
            animationSpec = infiniteRepeatable(
                animation = tween(2600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
    } else {
        animateFloatAsState(targetValue = 1f, label = "breathRest")
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 双击暂停：与旧封面态一致。只消费按下 / 抬起，横向拖动照常上浮给翻页手势。
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        // 封面边长取「可用高 - 歌词区」与宽度的较小者：车机横屏高度紧张时
        // 不能按宽度算，否则封面会把下面那一句顶出屏幕。
        val reserved = if (carMode) 132.dp else 118.dp
        val side = minOf(maxWidth, maxHeight - reserved).coerceAtLeast(96.dp)
        val coverSide = minOf(side, if (carMode) 420.dp else 320.dp)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = breath
                        scaleY = breath
                    }
                    .size(coverSide)
                    .windShadow(level = 3, shape = RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CoverArt(song = song, size = coverSide, cornerRadius = 24.dp)
            }
            Spacer(Modifier.height(if (carMode) 22.dp else 18.dp))
            Text(
                text = currentLine?.text ?: stringResource(R.string.info_no_lyric_line),
                fontSize = if (carMode) 24.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (currentLine != null) scheme.onSurface else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.92f),
            )
            currentLine?.translation?.takeIf { it.isNotBlank() }?.let { tr ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr,
                    fontSize = if (carMode) 17.sp else 13.sp,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.92f),
                )
            }
        }
    }
}

// ================================================================ 页码指示

/**
 * 三页指示点（可点跳页）。
 *
 * 为什么放在中部区域的底部而不是顶栏：顶栏在车机上被「歌名 + 歌手」占满，
 * 手机上又被歌词/封面切换键占着；指示点跟着内容走，才不会在两端摆出两种版式。
 */
@Composable
internal fun PlayerPaneDots(
    current: PlayerPane,
    onSelect: (PlayerPane) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val labels = listOf(
        stringResource(R.string.player_pane_info),
        stringResource(R.string.player_pane_art),
        stringResource(R.string.player_pane_lyrics),
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerPane.entries.forEach { p ->
            val active = p == current
            val label = labels[p.index]
            Box(
                modifier = Modifier
                    .size(if (large) 34.dp else 28.dp)
                    .clip(CircleShape)
                    .clickable { if (!active) onSelect(p) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(
                            when {
                                active && large -> 10.dp
                                active -> 8.dp
                                large -> 8.dp
                                else -> 6.dp
                            }
                        )
                        .clip(CircleShape)
                        .background(
                            if (active) scheme.primary
                            else scheme.onSurfaceVariant.copy(alpha = 0.32f)
                        )
                )
            }
        }
    }
}
