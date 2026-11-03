package com.luckylearning.windtone.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 统一排版（UI v2 · 2026-11-03）。
 *
 * ## 为什么要这一层
 *
 * 重构前 `MusicPlayerTheme` 调用 `MaterialTheme(colorScheme=…, shapes=…)` **未传 typography**，
 * 全项目吃 Material3 默认排版；同时约 20 个文件里 60+ 行硬编码 `fontSize = 9/10/11/12/13/
 * 14/15/16/18/22/28.sp`，车机另有一套 `CarMetrics.titleSp/artistSp` 与 `LyricsPane ×1.5`。
 * 也就是说「同一级标题」在手机、车机、悬浮歌词三处由三套互不知情的数值决定。
 *
 * 本层显式声明全站字阶，作为唯一出处；`MusicPlayerTheme` 已接线传入。
 *
 * ⚠️ 第一阶段的取值**与 Material3 默认逐值一致**，因此全 App 视觉零回归——先把「出处」
 * 收敛，不急着改观感。车机放大改由 [UiDensity] 的 `scale` / `titleSp` 在组件层驱动
 * （阶段二），不再需要第二套 Typography。
 */
val AppTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Normal,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
)
