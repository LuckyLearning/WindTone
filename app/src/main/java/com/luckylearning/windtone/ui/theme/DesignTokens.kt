package com.luckylearning.windtone.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计规范 v2 · 视觉升级层令牌（与 `docs/ui/ui.css` 的 --grad-* / --sh-* / --dur-* / --glass-*
 * 一一对应）。
 *
 * 为什么要单独一层：`Theme.kt` 管的是「Material 颜色角色 + 五档圆角」——这些 MaterialTheme
 * 能直接消费。但 v2 规范里还有一类 MaterialTheme 表达不了的视觉增强：风之渐变、**带色**
 * 投影、动效曲线、玻璃拟态、以及车机导航的语义强调色。此前它们散落在各屏手写（同一层级
 * 在不同页面取值不同），这里收敛成唯一出处，组件只引用这里，不再各自造轮子。
 *
 * ⚠️ 改这里等于改全 App 的视觉基调：动之前先对 `docs/ui/ui.css` 的令牌块。
 */
object AppVisual {

    // ============================================================ 风之渐变
    /** 品牌主渐变（135°，靛 → 紫 → 青）。对应 --grad-brand。 */
    val WindStops = listOf(Color(0xFF5B4BE0), Color(0xFF8B5CF6), Color(0xFF22D3EE))

    /** 柔版（紫 → 青），用于面积较大、不想太抢的地方。对应 --grad-brand-soft。 */
    val WindSoftStops = listOf(Color(0xFF7C6BFF), Color(0xFF22D3EE))

    val WindGradient: Brush
        get() = Brush.linearGradient(WindStops, start = Offset.Zero, end = Offset.Infinite)

    val WindSoftGradient: Brush
        get() = Brush.linearGradient(WindSoftStops, start = Offset.Zero, end = Offset.Infinite)

    /** 车机主播放键用（140°，比品牌渐变收敛，避免中控大屏大面积过亮）。对应 --grad-action。 */
    val ActionGradient: Brush
        get() = Brush.linearGradient(
            listOf(Color(0xFF8B7CF6), Color(0xFF5040D8)),
            start = Offset.Zero,
            end = Offset.Infinite,
        )

    // ============================================================ 靛蓝投影
    /** 投影染色：不用纯黑，用主色系深靛，阴影与品牌同调。对应 --sh-1/2/3 的 rgb。 */
    val ShadowTint = Color(0xFF4C35B0)
    val ShadowTintDeep = Color(0xFF281A78)

    // ============================================================ 动效
    /** 时长 / 曲线。对应 --dur-1/2/3 与 --ease。 */
    object Motion {
        const val FastMs = 180
        const val BaseMs = 260
        const val SlowMs = 400
        val Ease: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    }

    // ============================================================ 语义强调色
    /**
     * 车机左导航的入口色（设计稿 `.ac-*`）：图标带语义色、文字中性，一眼区分入口又不花。
     * 与 `docs/ui/06-car.html` / `07-spec.html` 的色板逐值一致。
     */
    object Accent {
        val Songs = Color(0xFF5B4BE0)
        val Album = Color(0xFFE8793B)
        val Artist = Color(0xFF7C6BF0)
        val AlbumArtist = Color(0xFF9B6BC9)
        val Genre = Color(0xFFC45B8A)
        val Year = Color(0xFF5BA88A)
        val Folder = Color(0xFF3B82C4)
        val Playlist = Color(0xFF17A398)
    }

    /** 收藏红心（--fav）：浅色主题偏深、深色主题偏亮。 */
    fun favColor(dark: Boolean): Color = if (dark) Color(0xFFFF7A8A) else Color(0xFFD93852)

    // ============================================================ 玻璃拟态
    /**
     * 玻璃底色（--glass-bg）。无真模糊（minSdk 24 无 RenderEffect），用高不透明度近似。
     *
     * 2026-10-22 用户反馈「抽屉透明度太高，底下内容全透出来」：暗色 0xB8(72%) → 0xE6(90%)、
     * 浅色 0xA8(66%) → 0xF0(94%)。玻璃感靠 [glassBorder] 描边与渐变维持，底色必须压得住
     * 播放页的歌词 / 唱片，否则弹层上的字与底下内容糊成一片。
     */
    fun glassBg(dark: Boolean): Color = if (dark) Color(0xE61C1A25) else Color(0xF0FFFFFF)

    /** 玻璃描边（--glass-bd）。 */
    fun glassBorder(dark: Boolean): Color =
        if (dark) Color(0x38C5BFFF) else Color(0x335B4BE0)

    // ============================================================ 封面缺省（六档确定性渐变）
    /**
     * 没有内嵌封面时的兜底：不再画统一的灰色音符，改成「首字 + 六档渐变」。
     * 取档按 [coverPaletteIndex]（stableKey 哈希），同一首歌永远同一色，扫一眼靠颜色定位。
     * 六档对应设计稿 `.cv3-tile.t1..t6` / `.cv3-cover.t1..t6`。
     */
    private val CoverPalettes = listOf(
        Color(0xFF8B7CF6) to Color(0xFF5040D8),
        Color(0xFFFB7185) to Color(0xFFE23D5A),
        Color(0xFF5AA9F8) to Color(0xFF2F76E8),
        Color(0xFF3ED6A8) to Color(0xFF0E9E8F),
        Color(0xFFFBBF4A) to Color(0xFFEF7C1A),
        Color(0xFFF472B6) to Color(0xFFA855F7),
    )

    /** 稳定的调色板下标：同一 key 永远返回同一档（不依赖运行期随机 / 时间）。 */
    fun coverPaletteIndex(key: String): Int {
        if (key.isEmpty()) return 0
        var h = 0
        for (c in key) h = 31 * h + c.code
        return (h and 0x7fffffff) % CoverPalettes.size
    }

    fun coverBrush(key: String): Brush {
        val (a, b) = CoverPalettes[coverPaletteIndex(key)]
        return Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset.Infinite)
    }

    /** 封面首字：CJK 取第一个字、拉丁取首字母大写；空白回退「♪」。 */
    fun coverInitial(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return "♪"
        return String(Character.toChars(t.codePointAt(0))).uppercase()
    }
}

/**
 * 三档带色投影的 Modifier（对应 --sh-1/2/3）。
 *
 * 为什么不用 `Surface(shadowElevation=…)`：Surface 的阴影固定走黑色，做不出「靛蓝投影」。
 * 这里用 `Modifier.shadow` 的 ambient/spot 上色，阴影与品牌同调。
 *
 * @param level 1=轻浮起（卡片）/ 2=浮层（迷你条、菜单）/ 3=模态（弹窗、Sheet）
 */
fun Modifier.windShadow(
    level: Int = 1,
    shape: Shape = RoundedCornerShape(14.dp),
): Modifier {
    val elevation: Dp = when {
        level >= 3 -> 22.dp
        level == 2 -> 12.dp
        else -> 6.dp
    }
    val tint = if (level >= 3) AppVisual.ShadowTintDeep else AppVisual.ShadowTint
    return shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = tint,
        spotColor = tint,
    )
}

/** 当前配色是否为深色：按**实际绘制亮度**判断，兼容「主题跟随封面取色」派生出来的深浅。 */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 玻璃容器底色（弹层 / 浮层直接用 `containerColor = glassContainerColor()`）。 */
@Composable
fun glassContainerColor(): Color = AppVisual.glassBg(isDarkScheme())
