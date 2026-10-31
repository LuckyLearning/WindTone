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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.ui.theme.AppVisual
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
 *  · **v3（2026-10-20）改为左常驻导航 + 右内容区**：左导航（宽度见 `CarMetrics.navWidth`）贯穿全高，
 *    播放条只铺右侧内容区（不再横贯全宽）。与 v1.1.14 被否决的「两栏」不同：导航收窄成卡式、
 *    播放条不压导航、Dock 安全区照留，三处都处理过；
 *  · 内容区限宽 [CAR_CONTENT_MAX_WIDTH]（约束的是列表，不含车机左导航）。
 */
internal val CAR_TOUCH_TARGET: Dp = 76.dp
private val CAR_COVER_SIZE: Dp = 80.dp
private val CAR_MAIN_BUTTON: Dp = 88.dp

/** 固定圆角的 Shape 必须是常量：新建实例会让下游 clip / background 无法跳过重组 */
private val COVER_CLIP_SHAPE = RoundedCornerShape(16.dp)
private val COUNT_BADGE_SHAPE = RoundedCornerShape(50)

/**
 * 车机版视图名资源：比抽屉短名更明确（「歌曲」→「全部歌曲」）。
 * 映射只在这里一份；SONGS 用 [R.string.car_view_all_songs]，其余复用 [LibraryView.labelRes]。
 */
internal fun carViewLabelRes(view: LibraryView): Int = when (view) {
    LibraryView.SONGS -> R.string.car_view_all_songs
    else -> view.labelRes()
}

@Composable
internal fun carViewLabel(view: LibraryView): String = stringResource(carViewLabelRes(view))

/** 车机首页顶栏：标题 + 首数 + 搜索。搜索就地展开，不跳页、不弹键盘二级页（设置移入左侧导航） */
@Composable
internal fun CarLibraryHeader(
    library: LibraryUiState,
    onQuery: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var searchMode by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(library.query) }
    val focus = remember { FocusRequester() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rememberCarMetrics().headerPadH, vertical = rememberCarMetrics().headerPadV),
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
                        stringResource(R.string.car_search_hint),
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
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search), tint = scheme.onBackground)
            }
            CarIconButton(onClick = {
                input = ""
                onQuery("")
                searchMode = false
            }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close_search), tint = scheme.onBackground)
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Text(
                text = stringResource(R.string.lib_title_local_music),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
            )
            if (library.songs.isNotEmpty()) {
                Spacer(Modifier.width(14.dp))
                Surface(
                    color = scheme.secondaryContainer,
                    shape = COUNT_BADGE_SHAPE,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.lib_song_count,
                            library.songs.size,
                            library.songs.size,
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            CarIconButton(onClick = { searchMode = true }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search), tint = scheme.onBackground)
            }
        }
    }
}

/** 车机 v3 左导航宽度：设计稿 188px × (1707dp/1000px) ≈ 320dp，占屏宽约 19% */

/** 车机左常驻导航（v3）：品牌 + 五个浏览视图 + 底部设置。选中项用 primary 淡底。 */
@Composable
internal fun CarNavRail(
    current: LibraryView,
    onSelect: (LibraryView) -> Unit,
    onOpenSettings: () -> Unit,
    /** 阿里云盘页是否打开（v1.1.84：车机没有抽屉，云盘入口必须常驻在左导航里） */
    aliyunActive: Boolean = false,
    onOpenAliyun: () -> Unit = {},
    /** 扫描范围（v1.3.0：与手机抽屉对齐 —— 车机此前没有这个入口，只能进设置里找） */
    onOpenScanScope: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val m = rememberCarMetrics()
    Row(
        modifier = Modifier
            .width(m.navWidth)
            .fillMaxHeight()
            .background(scheme.surface),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = if (m.compact) 10.dp else 16.dp, vertical = if (m.compact) 12.dp else 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, bottom = if (m.compact) 12.dp else 20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppVisual.WindGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.app_name), fontSize = m.navTitleSp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
            }
            LibraryView.entries.forEach { view ->
                CarNavItem(
                    label = carViewLabel(view),
                    icon = view.icon,
                    accent = carViewAccent(view),
                    // 云盘页打开时曲库视图不再高亮（与抽屉同一口径）
                    selected = view == current && !aliyunActive,
                    onClick = { onSelect(view) },
                )
            }
            // v1.1.84：车机模式下没有抽屉，阿里云盘原本只能从抽屉进 —— 等于进不去。
            // 这里补一个常驻入口，位置紧跟曲库视图，与手机端「在线音乐」组同一个层级。
            CarNavItem(
                label = stringResource(R.string.drawer_aliyun),
                icon = AppIcons.Cloud,
                accent = scheme.onSurfaceVariant,
                selected = aliyunActive,
                onClick = onOpenAliyun,
            )
            Spacer(Modifier.weight(1f))
            // v1.3.0：与手机抽屉「更多」组对齐 —— 扫描范围 + 设置。
            // 之前车机只有「设置」，用户想换扫描目录得先点进设置再找，与手机上多一层。
            CarNavItem(
                label = stringResource(R.string.drawer_scan_scope),
                icon = AppIcons.Folder,
                accent = scheme.onSurfaceVariant,
                selected = false,
                onClick = onOpenScanScope,
            )
            CarNavItem(
                label = stringResource(R.string.drawer_settings),
                icon = AppIcons.Tune,
                accent = scheme.onSurfaceVariant,
                selected = false,
                onClick = onOpenSettings,
            )
        }
        // 右缘 1px 分隔线（设计稿 .cv3-nav 的右边框）
        Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
    }
}

/** 五个浏览视图的语义色（与设计稿 `.ac-*` 一致） */
private fun carViewAccent(view: LibraryView): Color = when (view) {
    LibraryView.SONGS -> AppVisual.Accent.Songs
    LibraryView.ALBUM -> AppVisual.Accent.Album
    LibraryView.ARTIST -> AppVisual.Accent.Artist
    LibraryView.ALBUM_ARTIST -> AppVisual.Accent.AlbumArtist
    LibraryView.GENRE -> AppVisual.Accent.Genre
    LibraryView.YEAR -> AppVisual.Accent.Year
    LibraryView.FOLDER -> AppVisual.Accent.Folder
    LibraryView.PLAYLIST -> AppVisual.Accent.Playlist
}

/** 导航项：圆角卡、图标语义色、选中项 primary 淡底 + 加粗 + 可见焦点环 */
@Composable
private fun CarNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val m = rememberCarMetrics()
    var focused by remember { mutableStateOf(false) }
    // Shape 不 remember 的话每次重组都是新实例，clip / background / border 无法跳过
    val navShape = remember(m.navCorner) { RoundedCornerShape(m.navCorner) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = m.navItemGap)
            .clip(navShape)
            .then(if (selected) Modifier.background(scheme.primary.copy(alpha = 0.10f)) else Modifier)
            .then(if (focused) Modifier.border(2.dp, scheme.primary, navShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = m.navPadH, vertical = m.navPadV),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) scheme.primary else accent,
            modifier = Modifier.size(m.navIcon),
        )
        Spacer(Modifier.width(if (m.compact) 10.dp else 14.dp))
        Text(
            text = label,
            fontSize = m.navTextSp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) scheme.onBackground else scheme.onSurfaceVariant,
        )
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
    // 这里刻意**不**读 progressFlow：读了就等于整个车机底条（封面、歌名、
    // 四个按钮）跟着进度每 500ms 重组一遍。收集下沉到 CarProgressBar 内部，
    // 失效范围就只剩那一条 Canvas 和两个时间文字。

    Surface(color = scheme.surfaceContainerLow) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(rememberCarMetrics().barHeight)
                .padding(horizontal = 20.dp),
        ) {
            Box(Modifier.clip(COVER_CLIP_SHAPE).clickable(onClick = onExpand)) {
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
                    contentDescription = stringResource(if (isFavorite) R.string.cd_unfavorite else R.string.cd_favorite),
                    tint = if (isFavorite) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
            }
            CarIconButton(onClick = onPrevious) {
                Icon(AppIcons.SkipPrevious, contentDescription = stringResource(R.string.cd_previous), tint = scheme.onSurface)
            }
            PlayButton(isPlaying = isPlaying, onClick = onToggle)
            CarIconButton(onClick = onNext) {
                Icon(AppIcons.SkipNext, contentDescription = stringResource(R.string.cd_next), tint = scheme.onSurface)
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1.6f)) {
                CarProgressBar(progressFlow = progressFlow, onSeek = onSeek)
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
            contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
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

/**
 * 车机进度条：轨道 12dp、滑块 34dp，自带「已播 / 总时长」。
 *
 * 收的是 Flow 而不是快照值：进度在这里才被读取，重组范围就只有本组件
 * （一条 Canvas + 两个文字），不会带上整个底条。
 */
@Composable
private fun CarProgressBar(
    progressFlow: StateFlow<PlaybackProgress>,
    onSeek: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val progress by progressFlow.collectAsStateWithLifecycle()

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
