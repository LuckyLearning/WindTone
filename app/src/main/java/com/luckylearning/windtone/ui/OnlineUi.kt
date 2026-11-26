package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.online.OnlineCover
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.ui.theme.AppVisual
import kotlinx.coroutines.delay

/**
 * 洛雪模式的统一视觉件。
 *
 * 目的不是「换一套皮肤」，而是把原来各页各写一份的间距 / 卡片 / 封面 / 加载态收成一处：
 * 搜索、歌单、榜单、我的列表、音源管理都用同一档圆角、同一档底色、同一套空态与骨架，
 * 页面之间只差内容，不差手感。
 *
 * 约定：
 * - 卡片圆角 16dp，列表容器左右 12dp，卡片间距 6~8dp；
 * - 封面统一走 [LxCover] / [LxTrackCover]，同步预读缓存，未命中才显示首字渐变占位；
 * - 加载优先用骨架（列表/网格），只有「整页一次性的等待」才用转圈。
 */

/** 统一卡片：16dp 圆角、`surfaceContainerLow` 底色、可选点击。 */
@Composable
internal fun LxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** 分组标题：左侧 3dp 主色竖条 + 标题 + 可选计数。 */
@Composable
internal fun LxSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            LxCountPill(count)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 计数胶囊：曲目数 / 分组条数。 */
@Composable
internal fun LxCountPill(count: Int) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 筛选 chip 的统一横向容器：左右 12dp、间距 6dp。 */
@Composable
internal fun LxChipRow(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * 歌单 / 榜单封面。
 *
 * - **先查缓存**：命中就直接画，避免「占位一帧 → 换图」的闪；
 * - 未命中才起协程；[staggerMs] 让首屏卡片错峰几十毫秒，避免同一帧排队几十个请求；
 * - 失败保持首字渐变，不显示错误图标（列表里不该比没图更吵）。
 * - 已经有位图时不要从透明度 0 再淡入。滚出再滚回时组合是新的，
 *   从 0 淡入的那 200ms 看起来就是封面又变回了占位图。
 */
@Composable
internal fun LxCover(
    url: String?,
    cacheKey: String,
    name: String,
    targetPx: Int,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    initialSize: TextUnit = 28.sp,
    staggerMs: Long = 0L,
) {
    var bitmap by remember(cacheKey, url) {
        mutableStateOf(OnlineCover.peekThumb(url, targetPx))
    }
    LaunchedEffect(cacheKey, url, targetPx) {
        if (bitmap?.isRecycled == false || url.isNullOrBlank()) return@LaunchedEffect
        if (staggerMs > 0L) delay(staggerMs)
        OnlineCover.thumbUrl(url, targetPx)?.let { bitmap = it }
    }
    LxCoverFrame(
        bitmap = bitmap,
        cacheKey = cacheKey,
        name = name,
        modifier = modifier,
        shape = shape,
        initialSize = initialSize,
    )
}

/** 在线曲目封面：与 [LxCover] 同一套行为，只是数据入口是 [OnlineTrack]。 */
@Composable
internal fun LxTrackCover(
    track: OnlineTrack,
    targetPx: Int,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    initialSize: TextUnit = 16.sp,
    staggerMs: Long = 0L,
) {
    val context = LocalContext.current
    var bitmap by remember(track.stableKey) {
        mutableStateOf(OnlineCover.peekTrack(track.stableKey, targetPx))
    }
    LaunchedEffect(track.stableKey, targetPx) {
        if (bitmap != null) return@LaunchedEffect
        if (staggerMs > 0L) delay(staggerMs)
        OnlineCover.thumb(context, track, targetPx)?.let { bitmap = it }
    }
    LxCoverFrame(
        bitmap = bitmap,
        cacheKey = track.stableKey,
        name = track.title,
        modifier = modifier,
        shape = shape,
        initialSize = initialSize,
    )
}

@Composable
private fun LxCoverFrame(
    bitmap: Bitmap?,
    cacheKey: String,
    name: String,
    modifier: Modifier,
    shape: Shape,
    initialSize: TextUnit,
) {
    Box(
        modifier.clip(shape).background(AppVisual.coverBrush(cacheKey)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            // 不要从 0 淡入。滚出屏幕再回来时这项会重新进入组合，
            // 透明度从 0 开始的那一段就是用户看到的「封面又变回占位图」。
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                AppVisual.coverInitial(name),
                color = Color.White,
                fontSize = initialSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** 列表骨架：几行等高的占位卡，替代整页转圈，避免加载完成时列表整体跳动。 */
@Composable
internal fun LxListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 8,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SkeletonBar(widthFraction = if (index % 3 == 0) 0.62f else 0.48f)
                    Spacer(Modifier.height(7.dp))
                    SkeletonBar(widthFraction = 0.3f, height = 9.dp)
                }
            }
        }
    }
}

/** 歌单网格骨架：两列方形封面 + 两行文字条。 */
@Composable
internal fun LxGridSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) {
                    Column(Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Spacer(Modifier.height(8.dp))
                        SkeletonBar(widthFraction = 0.72f)
                        Spacer(Modifier.height(6.dp))
                        SkeletonBar(widthFraction = 0.42f, height = 9.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** 统一空态：渐变圆 + 标题 + 说明 + 可选操作。 */
@Composable
internal fun LxEmptyPane(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppVisual.WindGradient),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                AppIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}
