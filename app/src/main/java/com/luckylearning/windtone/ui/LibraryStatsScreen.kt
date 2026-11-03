package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.luckylearning.windtone.ui.theme.adaptiveDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.FormatStat
import com.luckylearning.windtone.data.LibraryStats
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.formatListenDuration

/**
 * 曲库格式与容量页（v1.1.17）。
 *
 * 回答三个问题：**有哪些格式、各多少首、各占多大**，外加一条「无损占了多大比例」。
 * 数据来自内存里的曲库（`library.songs`），由 [com.luckylearning.windtone.data.buildLibraryStats]
 * 纯函数聚合 —— 本页不做任何统计逻辑，只负责画。
 *
 * 用柱状条而不是饼图：格式通常只有 3~6 种，条形的长度差比扇形角度好读得多，
 * 也和收听报告页的图表风格一致，不额外引图表库。
 */
@Composable
fun LibraryStatsScreen(
    stats: LibraryStats,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }

    // 宽屏（平板横屏 / 车机）限宽：否则一根比例条会被拉到半米长，占比读不出来
    // UI v2 阶段三：限宽改读统一密度表，不再用第 4 套 `>= 840` 局部判据。
    val density = adaptiveDensity()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    stringResource(R.string.ls_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = density.contentMaxWidth)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 18.dp),
            ) {
                if (stats.totalSongs <= 0) {
                    Text(
                        stringResource(R.string.ls_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    Spacer(Modifier.height(28.dp))
                    return@Column
                }

                // ------------------------------------------------ 汇总
                StatCard(
                    rows = listOf(
                        stringResource(R.string.ls_stat_total_songs) to pluralStringResource(
                            R.plurals.lib_song_count,
                            stats.totalSongs,
                            stats.totalSongs,
                        ),
                        stringResource(R.string.ls_stat_total_size) to formatByteSize(stats.totalBytes),
                        stringResource(R.string.ls_stat_total_duration) to formatListenDuration(stats.totalDurationMs),
                        stringResource(R.string.ls_stat_avg_size) to formatByteSize(stats.averageBytes),
                        stringResource(R.string.ls_stat_format_count) to stringResource(
                            R.string.ls_format_kinds,
                            stats.formatCount,
                        ),
                    )
                )
                Spacer(Modifier.height(18.dp))

                // ------------------------------------------------ 格式分布
                SectionLabel(stringResource(R.string.ls_section_by_size))
                stats.formats.forEach { stat ->
                    FormatBar(stat = stat, share = stats.shareOf(stat))
                }

                // ------------------------------------------------ 无损 / 有损
                if (stats.losslessSongs > 0 || stats.lossySongs > 0) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel(stringResource(R.string.ls_section_lossless_lossy))
                    val losslessLabel = stringResource(R.string.ls_lossless)
                    val lossyLabel = stringResource(R.string.ls_lossy)
                    val unknownLabel = stringResource(R.string.ls_unknown_format)
                    val losslessRow = if (stats.losslessSongs > 0) {
                        val count = pluralStringResource(
                            R.plurals.lib_song_count,
                            stats.losslessSongs,
                            stats.losslessSongs,
                        )
                        losslessLabel to (
                            stringResource(
                                R.string.ls_count_dot_size,
                                count,
                                formatByteSize(stats.losslessBytes),
                            ) + sharePctSuffix(stats.losslessBytes, stats.totalBytes)
                        )
                    } else {
                        null
                    }
                    val lossyRow = if (stats.lossySongs > 0) {
                        val count = pluralStringResource(
                            R.plurals.lib_song_count,
                            stats.lossySongs,
                            stats.lossySongs,
                        )
                        lossyLabel to (
                            stringResource(
                                R.string.ls_count_dot_size,
                                count,
                                formatByteSize(stats.lossyBytes),
                            ) + sharePctSuffix(stats.lossyBytes, stats.totalBytes)
                        )
                    } else {
                        null
                    }
                    val unknownRow = if (stats.unknownFormatSongs > 0) {
                        unknownLabel to pluralStringResource(
                            R.plurals.lib_song_count,
                            stats.unknownFormatSongs,
                            stats.unknownFormatSongs,
                        )
                    } else {
                        null
                    }
                    StatCard(rows = listOfNotNull(losslessRow, lossyRow, unknownRow))
                }

                // ------------------------------------------------ 口径说明
                Spacer(Modifier.height(16.dp))
                val footnote = stringResource(R.string.ls_footnote_base) +
                    (if (stats.unknownSizeSongs > 0) {
                        stringResource(
                            R.string.ls_footnote_unknown_size,
                            stats.unknownSizeSongs,
                        )
                    } else {
                        ""
                    }) +
                    stringResource(R.string.ls_footnote_m4a)
                Text(
                    footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

// ================================================================ 单个格式

/**
 * 一行格式：`MP3 ────────── 872 首 · 6.21 GB · 72%`。
 *
 * 比例条与文字用**同一份** [share]，不各自算一遍 —— 条长 72% 而文字写 71% 这种
 * 对不上的细节，正是"统计页看着不可信"的来源。
 */
@Composable
private fun FormatBar(stat: FormatStat, share: Float) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stat.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(R.plurals.lib_song_count, stat.songs, stat.songs),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                formatByteSize(stat.bytes),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${(share * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 34.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        // 底槽 + 填充：没有底槽时，占比很小的格式会看起来像"没有条形"
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(scheme.onSurfaceVariant.copy(alpha = 0.15f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (stat.lossless) scheme.tertiary else scheme.primary.copy(alpha = 0.85f)),
            )
        }
        // 平均码率只在算得出来时显示（时长未知的曲目会让它变成无意义的 0）
        if (stat.averageKbps > 0) {
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(
                    R.string.ls_avg_bitrate,
                    stat.averageKbps,
                    formatByteSize(stat.averageBytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 「 · 72%」这种后缀；合计为 0 时返回空串，避免写出「0%」误导 */
@Composable
private fun sharePctSuffix(part: Long, total: Long): String =
    if (total <= 0L || part <= 0L) ""
    else stringResource(R.string.ls_share_pct, (part * 100 / total).toInt())
