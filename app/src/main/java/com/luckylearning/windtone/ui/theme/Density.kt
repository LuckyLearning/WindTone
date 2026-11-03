package com.luckylearning.windtone.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一密度表（UI v2 · 2026-11-03）。
 *
 * ## 为什么要这一层
 *
 * 重构前手机与车机是**两套尺寸体系**：手机散落着几十处硬编码 `.dp` / `.sp`
 * （`66.dp` 主键、`18.dp` padding、M3 默认字阶），车机则另有 `CarMetrics` 两档 23 字段 +
 * `CarLibraryScreen.kt` 里 4 个局部 `CAR_*` 常量 + `LyricsPane` 的 `×1.5` 歌词倍率 +
 * `MainScreen` 的 `56.dp` Dock 让位。同一个「行高」在两端由四个互不相干的来源决定。
 *
 * 本层把两端收敛成**同一张 [UiDensity] 表**：字段完全相同，只是档位取值不同。
 * 组件读 `density.rowMin` / `density.touchMin`，不再写 `if (carMode) 76.dp else 48.dp`。
 * 这是「手机与车机体验一体化」在尺寸层面的落点——差异是**参数**，不是**两套代码**。
 *
 * 车机紧凑档的取值直接沿用 `CarMetrics`（1280×656dp 的 DiLink 实测值），
 * 保证本层接线时不改变已验证过的真机尺寸。
 */
data class UiDensity(
    val formFactor: UiFormFactor,
    /** 车机紧凑档标记（仅 CAR 有意义）。 */
    val compact: Boolean,
    /** 整体缩放系数，供组件按倍率推导未列入本表的次要尺寸。 */
    val scale: Float,
    /** 基准间距单位；组件用 `spacing * n` 表达，避免再散落魔法 dp。 */
    val spaceUnit: Dp,
    /** 最小可点目标（车机 ≥56dp，因视距远、行驶中操作）。 */
    val touchMin: Dp,
    val icon: Dp,
    val rowMin: Dp,
    val coverSmall: Dp,
    val coverLarge: Dp,
    val nowPlayingBar: Dp,
    val mainButton: Dp,
    val sideButton: Dp,
    /** 常驻侧导航宽度；PHONE 为 0（用抽屉）。 */
    val navWidth: Dp,
    val contentMaxWidth: Dp,
    val horizontalPadding: Dp,
    /** 车机 Dock 让位（DiLink 自绘浮层，insets 报 0）。 */
    val dockReserved: Dp,
    /** 可见焦点环宽度（方向盘 / 旋钮）。 */
    val focusRing: Dp,
    val titleSp: TextUnit,
    val bodySp: TextUnit,
    val captionSp: TextUnit,
)

object Density {

    /** 与 `CarMetrics.carMetricsFor` 相同的分界，保持真机行为不变。 */
    const val COMPACT_SHORT_DP = 760f
    const val COMPACT_WIDTH_DP = 1400f

    /**
     * 按形态 + 屏幕尺寸取密度。纯函数，可在 JVM 单测里断言。
     */
    fun forFormFactor(
        formFactor: UiFormFactor,
        widthDp: Float,
        heightDp: Float,
    ): UiDensity {
        val compact = heightDp < COMPACT_SHORT_DP || widthDp < COMPACT_WIDTH_DP
        return when (formFactor) {
            UiFormFactor.PHONE -> phone()
            UiFormFactor.TABLET -> tablet()
            UiFormFactor.CAR -> if (compact) carCompact() else carRegular()
        }
    }

    private fun phone() = UiDensity(
        formFactor = UiFormFactor.PHONE,
        compact = false,
        scale = 1f,
        spaceUnit = 8.dp,
        touchMin = 48.dp,
        icon = 24.dp,
        rowMin = 56.dp,
        coverSmall = 48.dp,
        coverLarge = 64.dp,
        nowPlayingBar = 64.dp,
        mainButton = 56.dp,
        sideButton = 44.dp,
        navWidth = 0.dp,
        contentMaxWidth = 720.dp,
        horizontalPadding = 18.dp,
        dockReserved = 0.dp,
        focusRing = 0.dp,
        titleSp = 20.sp,
        bodySp = 14.sp,
        captionSp = 11.sp,
    )

    private fun tablet() = UiDensity(
        formFactor = UiFormFactor.TABLET,
        compact = false,
        scale = 1.1f,
        spaceUnit = 10.dp,
        touchMin = 48.dp,
        icon = 24.dp,
        rowMin = 64.dp,
        coverSmall = 56.dp,
        coverLarge = 72.dp,
        nowPlayingBar = 80.dp,
        mainButton = 64.dp,
        sideButton = 52.dp,
        navWidth = 240.dp,
        contentMaxWidth = 900.dp,
        horizontalPadding = 24.dp,
        dockReserved = 0.dp,
        focusRing = 2.dp,
        titleSp = 22.sp,
        bodySp = 15.sp,
        captionSp = 12.sp,
    )

    private fun carCompact() = UiDensity(
        formFactor = UiFormFactor.CAR,
        compact = true,
        scale = 1.25f,
        spaceUnit = 12.dp,
        touchMin = 56.dp,
        icon = 20.dp,
        rowMin = 62.dp,
        coverSmall = 64.dp,
        coverLarge = 80.dp,
        nowPlayingBar = 84.dp,
        mainButton = 62.dp,
        sideButton = 52.dp,
        navWidth = 208.dp,
        contentMaxWidth = 1100.dp,
        horizontalPadding = 18.dp,
        dockReserved = 56.dp,
        focusRing = 3.dp,
        titleSp = 20.sp,
        bodySp = 15.sp,
        captionSp = 12.sp,
    )

    private fun carRegular() = UiDensity(
        formFactor = UiFormFactor.CAR,
        compact = false,
        scale = 1.4f,
        spaceUnit = 14.dp,
        touchMin = 64.dp,
        icon = 24.dp,
        rowMin = 76.dp,
        coverSmall = 64.dp,
        coverLarge = 80.dp,
        nowPlayingBar = 112.dp,
        mainButton = 76.dp,
        sideButton = 64.dp,
        navWidth = 300.dp,
        contentMaxWidth = 1100.dp,
        horizontalPadding = 24.dp,
        dockReserved = 56.dp,
        focusRing = 3.dp,
        titleSp = 24.sp,
        bodySp = 16.sp,
        captionSp = 13.sp,
    )
}
