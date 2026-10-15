package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.ListeningReport
import com.luckylearning.windtone.data.formatDayLabel
import com.luckylearning.windtone.data.formatListenDuration
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.ReportUiState
import com.luckylearning.windtone.state.TopSong
import java.util.TimeZone

/** 可选的统计跨度（天） */
private val REPORT_SPANS = listOf(7, 30)

/**
 * 收听报告页（批次 10.1）。
 *
 * 数据全部来自本机：曲线与时段分布读 `listen_log` 的小时桶，榜单读 `song_state`
 * 的累计统计。**不新增任何网络请求**——"纯本地"是这个应用的定位红线。
 *
 * 图表自绘（Row + 加权 Box），不引图表库：需要的只有"柱状对比"这一种形式，
 * 为了它多背一个几百 KB 的依赖、多一套主题适配，收益不成比例。
 */
@Composable
fun ListeningReportScreen(
    state: ReportUiState?,
    onClose: () -> Unit,
    onSetDays: (Int) -> Unit,
    onPlayTop: (List<Song>) -> Unit,
) {
    BackHandler { onClose() }

    val days = state?.days ?: REPORT_SPANS.last()
    val report = state?.report
    // 宽屏（平板横屏 / 车机）限宽，否则一根柱子会被拉到半米宽，比例完全读不出来
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
                    "收听报告",
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
                // ------------------------------------------------ 跨度切换
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REPORT_SPANS.forEach { span ->
                        SpanChip(
                            label = "近 $span 天",
                            selected = span == days,
                            onClick = { if (span != days) onSetDays(span) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (report == null) {
                    Text(
                        "正在统计…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                    return@Column
                }

                // ------------------------------------------------ 概览
                StatGrid(report)
                Spacer(Modifier.height(18.dp))

                // ------------------------------------------------ 日曲线
                SectionHeader("每日收听时长")
                DayCurve(report)
                Spacer(Modifier.height(18.dp))

                // ------------------------------------------------ 时段分布
                SectionHeader("时段分布")
                HourBars(report)
                Spacer(Modifier.height(18.dp))

                // ------------------------------------------------ 榜单
                val topPlayed = state?.topPlayed.orEmpty()
                val topListened = state?.topListened.orEmpty()
                SectionHeader("最常播放")
                TopList(
                    rows = topPlayed,
                    valueOf = { "${it.playCount} 次" },
                    emptyHint = "这段时间还没有播放记录",
                    onPlayAll = { songs -> onPlayTop(songs) },
                )
                Spacer(Modifier.height(14.dp))
                SectionHeader("最长收听")
                TopList(
                    rows = topListened,
                    valueOf = { formatListenDuration(it.listenedMs) },
                    emptyHint = "这段时间还没有播放记录",
                    onPlayAll = { songs -> onPlayTop(songs) },
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

// ================================================================ 概览

@Composable
private fun StatGrid(report: ListeningReport) {
    val rows = listOf(
        "累计收听" to formatListenDuration(report.totalMs),
        "日均" to formatListenDuration(report.averageDayMs),
        "有收听的天数" to "${report.activeDays} 天",
        "最常听的时段" to (report.peakHour?.let { "${it.hour}:00–${it.hour + 1}:00" } ?: "—"),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            rows.forEach { (k, v) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(112.dp),
                    )
                    Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ================================================================ 日曲线

/**
 * 每日收听时长柱状图。
 *
 * 高度按 [ListeningReport.maxDayMs] 归一化；**最长的那天**才顶到满高，
 * 而不是按某个固定阈值——不同人听歌量差十倍，写死阈值要么全满要么全平。
 * 柱子底部对齐（Column + `Arrangement.Bottom`），30 天时每根不到 10dp 宽，
 * 所以不画轴、不做点击，只给"哪天多哪天少"的整体形状。
 */
@Composable
private fun DayCurve(report: ListeningReport) {
    val max = report.maxDayMs
    val scheme = MaterialTheme.colorScheme
    val zone = remember { TimeZone.getDefault() }
    val todayStart = report.days.lastOrNull()?.dayStartMs

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            if (max <= 0L) {
                Text(
                    "这段时间还没有收听记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    report.days.forEach { day ->
                        val fraction = (day.ms.toFloat() / max).coerceIn(0f, 1f)
                        val isToday = day.dayStartMs == todayStart
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(if (fraction <= 0f) 2.dp else (120 * fraction).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        isToday -> scheme.primary
                                        fraction > 0f -> scheme.primary.copy(alpha = 0.55f)
                                        // 没听的那天给一条极矮的底纹，曲线才看得出"这天是 0"而不是缺项
                                        else -> scheme.onSurfaceVariant.copy(alpha = 0.18f)
                                    }
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 首尾各标一个日期，中间不标：30 天时逐日标会糊成一团
        Row(Modifier.fillMaxWidth()) {
            val first = report.days.firstOrNull()
            Text(
                first?.let { formatDayLabel(it.dayStartMs, zone) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                todayStart?.let { "今天 ${formatDayLabel(it, zone)}" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================ 时段分布

/**
 * 24 小时段的累计时长。
 *
 * 24 根柱子等宽平铺（不像日曲线那样按天数自适应），因为横轴刻度是固定的 0..23，
 * 每根都能稳定放下；`hour % 6 == 0` 的位置标刻度，避免逐个标数字。
 */
@Composable
private fun HourBars(report: ListeningReport) {
    val max = report.hours.maxOfOrNull { it.ms } ?: 0L
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(72.dp)) {
            if (max <= 0L) {
                Text(
                    "这段时间还没有收听记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    report.hours.forEach { h ->
                        val fraction = (h.ms.toFloat() / max).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .weight(1f)
                                // 同一块区域里的最高那个时段顶格；其余按时长比例
                                .height(if (fraction <= 0f) 2.dp else (72 * fraction).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (fraction <= 0f) scheme.onSurfaceVariant.copy(alpha = 0.18f)
                                    else scheme.tertiary.copy(alpha = 0.35f + 0.65f * fraction)
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            report.hours.forEach { h ->
                Text(
                    if (h.hour % 6 == 0) "${h.hour}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ================================================================ 榜单

@Composable
private fun TopList(
    rows: List<TopSong>,
    valueOf: (TopSong) -> String,
    emptyHint: String,
    onPlayAll: (List<Song>) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        return
    }
    val songs = rows.mapNotNull { it.cover }
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index < 3) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp),
                )
                CoverArt(song = row.cover, size = 36.dp, cornerRadius = 6.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    valueOf(row),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (songs.isNotEmpty()) {
            TextButton(onClick = { onPlayAll(songs) }) { Text("播放这个榜单") }
        }
    }
}

// ================================================================ 小件

@Composable
private fun SectionHeader(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SpanChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.primaryContainer else scheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 13.sp,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
