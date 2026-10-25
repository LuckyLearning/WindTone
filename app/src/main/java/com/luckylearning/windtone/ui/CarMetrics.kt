package com.luckylearning.windtone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 车机版布局尺寸（v1.1.98）。
 *
 * ## 为什么需要这一层
 *
 * 车机版此前用的是**一套写死的尺码**（左导航 320dp、播放条 112dp、侧键 64dp…），
 * 这套值是按 1707dp 宽的大屏调的。而实测这台 DiLink 是 **1280×656dp、密度 1.5** ——
 * 宽不算窄，但**高度只有 656dp**：播放条 112dp 就吃掉 17% 的纵向空间，
 * 加左导航的胖按钮，整屏显得又挤又"按钮特别大"。
 *
 * 所以这里按「屏幕短边」分两档：
 *  · [compact]：高度 < 760dp 或宽度 < 1400dp（这台车机落在这一档）；
 *  · 常规档：大屏车机 / 横屏平板，维持原来的尺码。
 *
 * 为什么用纯函数 + 数据类：这类"整体缩一圈"的调整最容易改出不一致（导航缩了、播放条没缩），
 * 抽成一张表后所有组件读同一份，且可以对档位判定上单测。
 */
internal data class CarMetrics(
    val compact: Boolean,
    val navWidth: Dp,
    val navIcon: Dp,
    val navPadH: Dp,
    val navPadV: Dp,
    val navItemGap: Dp,
    val navTextSp: TextUnit,
    val navTitleSp: TextUnit,
    val navCorner: Dp,
    val headerPadH: Dp,
    val headerPadV: Dp,
    val barHeight: Dp,
    val sideTile: Dp,
    val sideIcon: Dp,
    val sideCorner: Dp,
    val sideGap: Dp,
    val rowMinHeight: Dp,
    /** 播放页主键（播放 / 暂停）直径 */
    val mainButton: Dp,
    /** 上一首 / 下一首按钮直径 */
    val sideButton: Dp,
    /** 播放模式 / 队列等次级按钮直径 */
    val secondaryButton: Dp,
    /** 底部控制行三段的间距 */
    val controlGap: Dp,
    /** 车机歌名字号 */
    val titleSp: TextUnit,
    /** 车机歌手字号 */
    val artistSp: TextUnit,
)

/**
 * 按屏幕尺寸（dp）决定车机版尺码。
 *
 * 阈值说明：656dp 高是实测车机，760dp 留出"再矮一点也能用"的余量；
 * 宽度 1400dp 用来把常见平板（1280）也拉进紧凑档 —— 它们和车机一样吃紧的是高度。
 */
internal fun carMetricsFor(widthDp: Float, heightDp: Float): CarMetrics {
    val compact = heightDp < 760f || widthDp < 1400f
    return if (compact) {
        CarMetrics(
            compact = true,
            navWidth = 208.dp,
            navIcon = 20.dp,
            navPadH = 12.dp,
            navPadV = 9.dp,
            navItemGap = 1.dp,
            navTextSp = 15.sp,
            navTitleSp = 18.sp,
            navCorner = 12.dp,
            headerPadH = 18.dp,
            headerPadV = 6.dp,
            barHeight = 84.dp,
            sideTile = 48.dp,
            sideIcon = 22.dp,
            sideCorner = 14.dp,
            sideGap = 6.dp,
            rowMinHeight = 62.dp,
            mainButton = 62.dp,
            sideButton = 52.dp,
            secondaryButton = 52.dp,
            controlGap = 26.dp,
            titleSp = 20.sp,
            artistSp = 14.sp,
        )
    } else {
        CarMetrics(
            compact = false,
            navWidth = 300.dp,
            navIcon = 24.dp,
            navPadH = 14.dp,
            navPadV = 12.dp,
            navItemGap = 3.dp,
            navTextSp = 17.sp,
            navTitleSp = 22.sp,
            navCorner = 14.dp,
            headerPadH = 24.dp,
            headerPadV = 10.dp,
            barHeight = 112.dp,
            sideTile = 60.dp,
            sideIcon = 26.dp,
            sideCorner = 20.dp,
            sideGap = 8.dp,
            rowMinHeight = 76.dp,
            mainButton = 76.dp,
            sideButton = 64.dp,
            secondaryButton = 64.dp,
            controlGap = 40.dp,
            titleSp = 24.sp,
            artistSp = 16.sp,
        )
    }
}

/** 取当前屏幕对应的尺码（配置变化时自动重算） */
@Composable
internal fun rememberCarMetrics(): CarMetrics {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.screenHeightDp) {
        carMetricsFor(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
    }
}
