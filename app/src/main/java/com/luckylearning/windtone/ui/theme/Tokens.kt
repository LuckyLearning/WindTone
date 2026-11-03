package com.luckylearning.windtone.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 基础视觉令牌（UI v2 · 2026-11-03）。
 *
 * ## 为什么要这一层
 *
 * 重构前令牌来源分裂成 6 套：`Theme.kt`（M3 角色色 + 五档圆角）、`DesignTokens.kt`（渐变 /
 * 投影 / 动效 / 玻璃）、`docs/ui/ui.css`（文档站视觉真源）、`CarMetrics.kt`（车机 23 字段）、
 * `LyricsColors`（悬浮歌词 8 色板）、`CarLibraryScreen.kt` 里 4 个局部 `CAR_*` 常量。
 * 更根本的是**四类基础令牌完全缺失**：间距（44 个文件里 22 种 dp 混用）、图标尺寸
 * （13 种 `.size()` 散落）、圆角（30 个文件 60+ 处手写，事实标准 12dp 却不在五档里）、
 * 动效时长（`220/320/1400/2600/24000 ms` 五类魔法值）。
 *
 * 本层补齐这四类，并与既有 `AppVisual`（渐变 / 投影 / 玻璃 / 品牌色）**分工不重叠**：
 *  · 颜色角色 → `Theme.kt` 的 `LightColors` / `DarkColors`
 *  · 渐变 / 投影染色 / 玻璃 / 语义强调色 → `AppVisual`
 *  · 间距 / 圆角 / 图标 / 海拔 / 时长 → 本文件
 *  · 形态与密度 → `FormFactor.kt` / `Density.kt`
 *  · 字阶 → `Type.kt`
 *
 * 组件一律引用这里，不再写魔法数值。改一处 = 改全 App。
 */

/** 4dp 栅格的间距档。组件用 `spacing(n) = spacing.unit * n` 推导非标准值，而不是新增档位。 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp

    /** 基准单位，供 `spacing(n)` 风格的连续推导。 */
    val unit = 4.dp
}

/**
 * 圆角五档（与 `AppShapes` / `ui.css --r-*` 逐值一致）+ 全圆角。
 * `Theme.kt` 的 `AppShapes` 已改为从这里读取，保证「唯一出处」。
 */
object Radius {
    val xs = 6.dp
    val s = 10.dp
    val m = 14.dp
    val l = 20.dp
    val xl = 28.dp
    val pill = 999.dp
}

/** 图标尺寸档；与 `docs/ui/ui.css` 的 `.ic*` 语义对应（Compose 侧此前完全没有常量）。 */
object IconSize {
    val xs = 13.dp
    val s = 16.dp
    val m = 20.dp
    val base = 24.dp
    val l = 28.dp
    val xl = 34.dp
    val xxl = 48.dp
}

/** 海拔三档，与 `Modifier.windShadow(level)` 的三档 elevation 一一对应。 */
object Elevation {
    val card = 6.dp
    val floating = 12.dp
    val modal = 22.dp
}

/**
 * 动效时长。基础三档直接复用 `AppVisual.Motion`（不重复定义），
 * 这里只收敛此前散落在各屏的五类魔法值。
 */
object Duration {
    val fastMs = AppVisual.Motion.FastMs
    val baseMs = AppVisual.Motion.BaseMs
    val slowMs = AppVisual.Motion.SlowMs

    /** 抽屉淡入 / 滑入（LibraryDrawer）。 */
    val drawerMs = 220
    /** 歌词高亮 alpha（LyricsPane）。 */
    val lyricsHighlightMs = 320
    /** 骨架屏扫光（Common.LibrarySkeleton）。 */
    val skeletonMs = 1400
    /** 呼吸动画（PlayerScreenParts）。 */
    val breathMs = 2600
    /** 黑胶匀速旋转一圈（PlayerScreen.CarVinylPane）。 */
    val vinylRotationMs = 24_000
}
