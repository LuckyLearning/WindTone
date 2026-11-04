package com.luckylearning.windtone.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Indigo = Color(0xFF5B4BE0)
private val IndigoContainer = Color(0xFFE4E0FF)

/**
 * 统一的圆角规范。
 * 此前各处手写 RoundedCornerShape(8/10/12/16/22.dp)，同一层级的卡片圆角不一致，
 * 视觉上显得零散。这里收敛成 5 档，全应用引用 Shapes token。
 *
 * UI v2：数值改从 [Radius] 读取，保证「圆角只有一处定义」（与 docs/ui/ui.css --r-* 对齐）。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.s),
    medium = RoundedCornerShape(Radius.m),
    large = RoundedCornerShape(Radius.l),
    extraLarge = RoundedCornerShape(Radius.xl),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFF16005C),
    secondary = Color(0xFF605A70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E0F2),
    onSecondaryContainer = Color(0xFF1C1730),
    tertiary = Color(0xFF7B4E7F),
    background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE6E1EC),
    onSurfaceVariant = Color(0xFF48454E),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFC9C4D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    // M3 surface 层次（不填会走默认值，与自定义背景/主色不协调）
    surfaceDim = Color(0xFFDCDAE4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F1F8),
    surfaceContainer = Color(0xFFEDECF4),
    surfaceContainerHigh = Color(0xFFE7E6EF),
    surfaceContainerHighest = Color(0xFFE1E0EA),
    inverseSurface = Color(0xFF302F37),
    inverseOnSurface = Color(0xFFF3F0F7),
    inversePrimary = Color(0xFFC5BFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5BFFF),
    onPrimary = Color(0xFF2B1A87),
    primaryContainer = Color(0xFF4232AD),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF322D41),
    secondaryContainer = Color(0xFF484458),
    onSecondaryContainer = Color(0xFFE7E0F2),
    tertiary = Color(0xFFEEB8F0),
    background = Color(0xFF0F0E14),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF17161D),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47444F),
    onSurfaceVariant = Color(0xFFC9C4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF413E49),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    surfaceDim = Color(0xFF0F0E14),
    surfaceBright = Color(0xFF3A3841),
    surfaceContainerLowest = Color(0xFF0A090F),
    surfaceContainerLow = Color(0xFF131219),
    surfaceContainer = Color(0xFF1A1820),
    surfaceContainerHigh = Color(0xFF24222B),
    surfaceContainerHighest = Color(0xFF2F2D36),
    inverseSurface = Color(0xFFE5E1E9),
    inverseOnSurface = Color(0xFF302F37),
    inversePrimary = Color(0xFF4B3BD8),
)

/** 由封面主色派生的配色（跟随取色） */
private fun deriveScheme(seed: Color, dark: Boolean) = if (dark) {
    DarkColors.copy(
        primary = lighten(seed, 0.55f),
        onPrimary = darken(seed, 0.65f),
        primaryContainer = darken(seed, 0.25f),
        onPrimaryContainer = lighten(seed, 0.9f),
        // 背景/卡片也轻轻染上种子色，避免「背景是紫灰、按钮是封面色」的割裂感
        background = blend(DarkColors.background, seed, 0.05f),
        surface = blend(DarkColors.surface, seed, 0.06f),
        surfaceContainerLow = blend(DarkColors.surfaceContainerLow, seed, 0.07f),
        surfaceContainer = blend(DarkColors.surfaceContainer, seed, 0.08f),
        surfaceContainerHigh = blend(DarkColors.surfaceContainerHigh, seed, 0.09f),
        surfaceContainerHighest = blend(DarkColors.surfaceContainerHighest, seed, 0.10f),
        surfaceVariant = blend(DarkColors.surfaceVariant, seed, 0.08f),
    )
} else {
    LightColors.copy(
        primary = darken(seed, 0.1f),
        onPrimary = Color.White,
        primaryContainer = lighten(seed, 0.82f),
        onPrimaryContainer = darken(seed, 0.72f),
        background = blend(LightColors.background, seed, 0.035f),
        surfaceContainerLow = blend(LightColors.surfaceContainerLow, seed, 0.045f),
        surfaceContainer = blend(LightColors.surfaceContainer, seed, 0.055f),
        surfaceContainerHigh = blend(LightColors.surfaceContainerHigh, seed, 0.065f),
        surfaceContainerHighest = blend(LightColors.surfaceContainerHighest, seed, 0.075f),
    )
}

private fun lighten(c: Color, f: Float) =
    Color(
        red = c.red + (1f - c.red) * f,
        green = c.green + (1f - c.green) * f,
        blue = c.blue + (1f - c.blue) * f,
        alpha = 1f,
    )

private fun darken(c: Color, f: Float) =
    Color(red = c.red * (1f - f), green = c.green * (1f - f), blue = c.blue * (1f - f), alpha = 1f)

/** 把 seed 按 amount 比例混进基础色（amount 越大越靠近 seed 的色相） */
private fun blend(base: Color, seed: Color, amount: Float) = Color(
    red = base.red + (seed.red - base.red) * amount,
    green = base.green + (seed.green - base.green) * amount,
    blue = base.blue + (seed.blue - base.blue) * amount,
    alpha = 1f,
)

@Composable
fun MusicPlayerTheme(
    seedColor: Int? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** UI v2 阶段三：用户显式拨的「车载模式」，与设备识别一起喂给统一判据。 */
    carMode: Boolean = false,
    /** UI v2 阶段三：设备是否被识别为车机（由 Activity 注入，theme 层不碰业务逻辑）。 */
    carHardware: Boolean = false,
    /** Material You：跟随系统壁纸取色（Android 12+）；与封面取色并存时封面优先 */
    materialYou: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val seed = seedColor?.let { Color(it) }
    // 取色优先级：封面取色（种子色非空）> Material You（壁纸）> 手写靛蓝种子色板。
    // Material You 只在 Android 12+ 存在（dynamicXxxColorScheme），低版本静默回落。
    val colors = when {
        seed != null -> deriveScheme(seed, darkTheme)
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                    colors.background.luminance() > 0.5f
            }
        }
    }
    // UI v2 阶段三：形态与密度在主题根部一次判定、全 App 只读这一份结果。
    // 放在 MaterialTheme 外层，保证所有子屏（含设置子页 / 播放页 / 弹层）都能读到。
    ProvideAdaptiveLayout(carMode = carMode, carHardware = carHardware) {
        MaterialTheme(
            colorScheme = colors,
            shapes = AppShapes,
            typography = AppTypography,
        ) {
            content()
        }
    }
}
