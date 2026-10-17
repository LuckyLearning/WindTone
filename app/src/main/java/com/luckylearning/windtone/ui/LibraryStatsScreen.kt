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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                    "曲库格式与容量",
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
                if (stats.totalSongs <= 0) {
                    Text(
                        "曲库还没有歌曲。扫描完成后这里会列出各格式的首数与占用。",
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
                        "歌曲总数" to "${stats.totalSongs} 首",
                        "合计占用" to formatByteSize(stats.totalBytes),
                        "总时长" to formatListenDuration(stats.totalDurationMs),
                        "平均每首" to formatByteSize(stats.averageBytes),
                        "格式种类" to "${stats.formatCount} 种",
                    )
                )
                Spacer(Modifier.height(18.dp))

                // ------------------------------------------------ 格式分布
                SectionLabel("按占用排序")
                stats.formats.forEach { stat ->
                    FormatBar(stat = stat, share = stats.shareOf(stat))
                }

                // ------------------------------------------------ 无损 / 有损
                if (stats.losslessSongs > 0 || stats.lossySongs > 0) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("无损 / 有损")
                    StatCard(
                        rows = buildList {
                            if (stats.losslessSongs > 0) {
                                add(
                                    "无损" to "${stats.losslessSongs} 首 · " +
                                        formatByteSize(stats.losslessBytes) +
                                        shareSuffix(stats.losslessBytes, stats.totalBytes),
                                )
                            }
                            if (stats.lossySongs > 0) {
                                add(
                                    "有损" to "${stats.lossySongs} 首 · " +
                                        formatByteSize(stats.lossyBytes) +
                                        shareSuffix(stats.lossyBytes, stats.totalBytes),
                                )
                            }
                            if (stats.unknownFormatSongs > 0) {
                                add("格式未知" to "${stats.unknownFormatSongs} 首")
                            }
                        }
                    )
                }

                // ------------------------------------------------ 口径说明
                Spacer(Modifier.height(16.dp))
                Text(
                    buildString {
                        append("占用按文件大小累加，与系统「文件管理」里看到的一致；")
                        append("同一首歌的歌词、封面若单独存成文件，不计在这里。\n")
                        if (stats.unknownSizeSongs > 0) {
                            append(
                                "有 ${stats.unknownSizeSongs} 首读不到文件大小" +
                                    "（老系统 / 外部存储未挂载时常见），它们只计首数、不计占用。\n",
                            )
                        }
                        append("m4a 可能是无损的 ALAC，也可能是有损的 AAC，按扩展名分不出来，统计里计入「有损」。")
                    },
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
                "${stat.songs} 首",
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
                "平均 ${stat.averageKbps} kbps · 平均每首 ${formatByteSize(stat.averageBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 「 · 72%」这种后缀；合计为 0 时返回空串，避免写出「0%」误导 */
private fun shareSuffix(part: Long, total: Long): String =
    if (total <= 0L || part <= 0L) "" else " · ${part * 100 / total}%"
