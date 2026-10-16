package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.playback.Pitch
import kotlin.math.abs

internal enum class ColorSlot(
    val title: String,
    val intro: String,
    /** 是否提供「跟随主题」。只有播放页的已唱色能跟随主题（它默认就跟着 primary 走）。 */
    val canFollowTheme: Boolean,
) {
    /** 播放页逐字歌词里「已唱过的那一段」 */
    LYRIC_SUNG(
        "已唱部分的颜色",
        "逐字歌词里已经唱过的那一段。选「跟随主题」就交给封面取色，随歌曲一起变化。",
        true,
    ),

    /** 悬浮窗里还没唱到的正文 */
    OVERLAY_TEXT(
        "悬浮歌词文字颜色",
        "桌面悬浮歌词里还没唱到的那部分。悬浮窗是固定色，不跟随主题。",
        false,
    ),

    /** 悬浮窗里已唱过的那一段 */
    OVERLAY_SUNG(
        "悬浮歌词已唱颜色",
        "桌面悬浮歌词里当前行已经唱过的那一段。还没唱到的部分由「文字颜色」单独控制。",
        false,
    ),
}

/**
 * 颜色弹层的预设色板（效果图 ㉙E 里的 8 个 `.swatch`）。
 *
 * 刻意不做成枚举：枚举承载不了**用户自定义**的颜色，而这三处的取值
 * 既可能是预设、也可能是任意 ARGB，还可能是「跟随主题」哨兵。
 */
internal object LyricsColors {
    /** 弹层里自定义滑块的起点色（暖黄，与悬浮窗已唱色的默认值同色系） */
    const val DEFAULT_SEED = 0xFFFFCD56.toInt()

    data class Preset(val name: String, val argb: Int)

    /** 白 / 暖黄 / 天蓝 / 青绿 / 草绿 / 桃粉 / 橘橙 / 雾紫 */
    val presets = listOf(
        Preset("纯白", 0xFFFFFFFF.toInt()),
        Preset("暖黄", 0xFFFFCD56.toInt()),
        Preset("天蓝", 0xFF4FC3F7.toInt()),
        Preset("青绿", 0xFF4DD0E1.toInt()),
        Preset("草绿", 0xFF81C784.toInt()),
        Preset("桃粉", 0xFFF06292.toInt()),
        Preset("橘橙", 0xFFFF7043.toInt()),
        Preset("雾紫", 0xFFBA68C8.toInt()),
    )

    /**
     * 取一个颜色的人话名字：哨兵 →「跟随主题」，预设 → 色名，其余 → `#RRGGBB`。
     *
     * 设置行右侧要回答「现在是什么颜色」，直接印 `-12593` 没人看得懂。
     */
    fun label(argb: Int): String = when (argb) {
        SettingsDefaults.COLOR_FOLLOW_THEME -> "跟随主题"
        else -> presets.firstOrNull { it.argb == argb }?.name ?: hex(argb)
    }

    /** ARGB → `#RRGGBB`（丢掉 alpha：这三处颜色都是不透明的） */
    fun hex(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return "#" + rgb.toString(16).uppercase().padStart(6, '0')
    }
}

/**
 * 颜色选择弹层（效果图 ㉙E，三处歌词颜色共用）。
 *
 * 三段式 —— **跟随主题 / 预设色板 / 自定义**：
 *  - 「跟随主题」只对播放页已唱色出现；悬浮窗那两处是固定色，没有这一项。
 *  - 预设 8 色覆盖「白 / 暖 / 冷 / 亮」几类常见偏好，车机上一点就中。
 *  - 自定义给色相 / 饱和度 / 亮度三个滑块 + 一条实时预览条。
 *
 * **为什么不用色轮**：车机上手指粗、精度差，色轮边缘的色相极难选准；
 * 三个滑块 + 预览条的组合在大屏上反而更好操作。
 *
 * 选中即落盘（[onPick]）——悬浮窗那两处会顺带触发一次 6 秒前台预览，
 * 不用退到桌面去验证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ColorPickerSheet(
    title: String,
    intro: String,
    value: Int,
    canFollowTheme: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 「跟随主题」没有具体颜色可回填，自定义区从 [LyricsColors.DEFAULT_SEED] 起步 ——
    // 不从随机色、也不从上一个弹层留下的色相起步，滑块初始位置每次一样。
    val seed = if (value == SettingsDefaults.COLOR_FOLLOW_THEME) {
        LyricsColors.DEFAULT_SEED
    } else {
        value
    }
    // 用 FloatArray 但每次都赋**新数组**：原地改元素不会触发重组，这个坑很安静。
    var hsv by remember(value) { mutableStateOf(hsvOf(seed)) }
    val custom = android.graphics.Color.HSVToColor(hsv)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                intro,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (canFollowTheme) {
                SectionTitle("跟随主题")
                PickRow(
                    title = "跟随主题",
                    subtitle = "从当前歌曲封面取色，随主题一起变化",
                    selected = value == SettingsDefaults.COLOR_FOLLOW_THEME,
                    onClick = {
                        onPick(SettingsDefaults.COLOR_FOLLOW_THEME)
                        onDismiss()
                    },
                )
            }

            SectionTitle("预设")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LyricsColors.presets.forEach { preset ->
                    ColorSwatch(
                        argb = preset.argb,
                        selected = value == preset.argb,
                        onClick = {
                            onPick(preset.argb)
                            onDismiss()
                        },
                    )
                }
            }

            SectionTitle("自定义")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(custom))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            )
            // 三个滑块只在**松手**时才写回：拖动时逐帧 setLyricOverlayXxxColor 会连着
            // 触发预览，把浮动窗口的排版刷成流水；预览条本身逐帧在变，反馈并不缺。
            HsvSlider(
                label = "色相",
                value = hsv[0],
                range = 0f..360f,
                onChange = { hsv = floatArrayOf(it, hsv[1], hsv[2]) },
                onCommit = { onPick(android.graphics.Color.HSVToColor(hsv)) },
            )
            HsvSlider(
                label = "饱和度",
                value = hsv[1],
                range = 0f..1f,
                onChange = { hsv = floatArrayOf(hsv[0], it, hsv[2]) },
                onCommit = { onPick(android.graphics.Color.HSVToColor(hsv)) },
            )
            HsvSlider(
                label = "亮度",
                value = hsv[2],
                range = 0f..1f,
                onChange = { hsv = floatArrayOf(hsv[0], hsv[1], it) },
                onCommit = { onPick(android.graphics.Color.HSVToColor(hsv)) },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = {
                        onPick(custom)
                        onDismiss()
                    },
                ) { Text("使用这个颜色") }
            }
        }
    }
}

/** 弹层里「跟随主题」那一行：左标题 + 右 ✓（无箭头，与效果图的 `.row-action` 一致） */
@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 预设色板里的一颗色点：34dp 圆，选中时描边加粗并在中心叠一个 ✓ */
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = contrastTint(argb),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 自定义区的一个分量滑块（色相 / 饱和度 / 亮度） */
@Composable
private fun HsvSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, fontSize = 13.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * 在给定底色上叠 ✓ 时该用黑还是白。
 *
 * 用 BT.601 的感知亮度而不是 RGB 算术均值：纯黄与纯蓝的算术均值一模一样，
 * 但人眼看黄的亮度接近白、蓝的亮度接近黑，按算术均值选会正好选反。
 */
private fun contrastTint(argb: Int): Color {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val luma = 0.299f * r + 0.587f * g + 0.114f * b
    return if (luma > 150f) Color(0xFF1B1B1B) else Color.White
}

/** ARGB → `[色相 0..360, 饱和度 0..1, 亮度 0..1]`（自定义滑块的初始位置） */
private fun hsvOf(argb: Int): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv
}

/** 累计收听时长的友好显示 */
