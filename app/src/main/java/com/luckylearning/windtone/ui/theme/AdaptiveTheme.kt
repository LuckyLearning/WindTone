package com.luckylearning.windtone.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 一体化形态 / 密度的**全局注入层**（UI v2 · 阶段三）。
 *
 * ## 为什么用 CompositionLocal
 *
 * 阶段一已经把三套判据收敛成 [FormFactor.resolve] 这个纯函数，但如果每个屏幕都各自
 * 调一次 `resolve()`，就又回到「谁也不服谁」：调用方仍可以随手写一个
 * `screenWidthDp >= 840`，判定结果又分裂了。
 *
 * 这里把**判定结果本身**放到组合树根上：由 [MusicPlayerTheme] 在一次调用里算出
 * [UiFormFactor] 与 [UiDensity]，全 App 任何 Composable 只读
 * `LocalUiFormFactor.current` / `LocalUiDensity.current`。想再写局部判据既没必要、
 * 也会立刻在代码审查里显形。这是「一套令牌 + 一个判据」在架构上的落点。
 *
 * 默认值取手机档：预览（`@Preview`）、悬浮窗等脱离 Activity 的组合树不会崩，
 * 也不会误按车机尺寸渲染。
 */

/** 当前组合树的形态。默认 PHONE，由 [ProvideAdaptiveLayout] 在主题根部覆盖。 */
val LocalUiFormFactor = staticCompositionLocalOf { UiFormFactor.PHONE }

/** 当前组合树的密度表。默认 PHONE 档，由 [ProvideAdaptiveLayout] 在主题根部覆盖。 */
val LocalUiDensity = staticCompositionLocalOf {
    Density.forFormFactor(UiFormFactor.PHONE, 0f, 0f)
}

/**
 * 唯一的注入点。由 [MusicPlayerTheme] 调用，调用方只负责提供两项**事实**：
 * 用户是否拨了车载模式、设备是否被识别为车机。
 *
 * 尺寸从 [LocalConfiguration] 读，横竖屏由 [FormFactor.resolve] 判定，
 * 密度由 [Density.forFormFactor] 查表——组件层不再出现任何 `if (carMode) A.dp else B.dp`。
 */
@Composable
fun ProvideAdaptiveLayout(
    carMode: Boolean,
    carHardware: Boolean,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp.toFloat()
    val heightDp = configuration.screenHeightDp.toFloat()
    val formFactor = FormFactor.resolve(
        widthDp = widthDp,
        heightDp = heightDp,
        carMode = carMode,
        carHardware = carHardware,
    )
    val density = Density.forFormFactor(formFactor, widthDp, heightDp)
    CompositionLocalProvider(
        LocalUiFormFactor provides formFactor,
        LocalUiDensity provides density,
        content = content,
    )
}

/** 便捷读法：让调用点写成 `adaptiveDensity().rowMin`，一眼看出不再有端分支。 */
@Composable
fun adaptiveDensity(): UiDensity = LocalUiDensity.current

/** 便捷读法：让调用点写成 `adaptiveFormFactor().needsFocusRing`。 */
@Composable
fun adaptiveFormFactor(): UiFormFactor = LocalUiFormFactor.current
