package com.luckylearning.windtone.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import com.luckylearning.windtone.ui.theme.AppVisual

/**
 * 设置页「下钻 / 返回」的**唯一**滑行动效（v1.2.9）。
 *
 * 之前每页切换是瞬切：点分类卡，整屏内容一帧之内换掉，用户只能靠标题变化判断
 * 自己进了哪一层。三级结构（主页 → 分类 → 子项）下这一点尤其难受 —— 从「歌词」
 * 钻进「桌面悬浮歌词」时，屏幕几乎没动，看不出是「进去了」还是「就停在这」。
 *
 * 现在统一成一次横向滑动，方向由**层级深度**决定（见 [depth]）：
 *  - **下钻**（目标更深）：新页从右侧推入，旧页向左退出 1/4 屏宽；
 *  - **返回**（目标更浅）：新页从左侧推入，旧页向右退出 1/4 屏宽。
 *
 * 两处刻意的取舍：
 *  1. **旧页只退 1/4，不跟着整块平移**。整块同步平移像传送带，两页都动等于没有参照；
 *     退一小段 + 淡出，层次感和「谁在上面」一眼可辨（与 iOS / Material shared-axis 同思路）。
 *  2. **时长只取全站动效令牌**（`--dur-2` = 260ms / `--dur-1` = 180ms），不另写魔法数。
 *     车机上 260ms 也够快，不会拖慢驾驶操作。
 *
 * 全部参数来自 [AppVisual.Motion]，改一处即全站一致。
 */
internal object SettingsMotion {

    /** 正文滑动时长，对齐全站 `--dur-2`。 */
    const val SlideMs: Int = AppVisual.Motion.BaseMs

    /** 标题淡入淡出时长，对齐全站 `--dur-1`。 */
    const val FadeMs: Int = AppVisual.Motion.FastMs

    /**
     * 旧页退出的屏宽比例。
     *
     * 取 1/4 而不是 1：整屏平移时两页速度相同、边界始终贴合同一条缝，
     * 视觉上像「翻页」；退 1/4 会让新页盖在旧页上，形成前后景。
     */
    private const val TrailingRatio = 0.25f

    /** 页面切换的横向滑动。[forward] = 是否在往更深一层走（下钻）。 */
    fun slide(forward: Boolean): ContentTransform {
        val slideSpec = tween<IntOffset>(SlideMs, easing = AppVisual.Motion.Ease)
        val fadeSpec = tween<Float>(FadeMs, easing = AppVisual.Motion.Ease)
        val enter = slideInHorizontally(slideSpec) { width ->
            if (forward) width else -width
        } + fadeIn(fadeSpec)
        val exit = slideOutHorizontally(slideSpec) { width ->
            val back = (width * TrailingRatio).toInt()
            if (forward) -back else back
        } + fadeOut(fadeSpec)
        return enter togetherWith exit
    }

    /**
     * 标题只做淡入淡出，不跟着横向滑。
     *
     * 标题与返回键同处一栏：标题若横向滑动，会从返回键上方「穿过去」，
     * 而标题长度又随页面变化（「设置」→「桌面悬浮歌词」），滑动时整栏会被撑得忽宽忽窄。
     */
    fun titleFade(): ContentTransform =
        fadeIn(tween(FadeMs, easing = AppVisual.Motion.Ease)) togetherWith
            fadeOut(tween(FadeMs, easing = AppVisual.Motion.Ease))
}

/**
 * 该页在设置层级中的深度：主页 = 0，每上一层 +1。
 *
 * 用来判断滑动方向，也是「设置页最多三层」这条结构约束的可执行版本。
 * 沿 [SettingsPage.parent] 一路上溯；父页写错成自己（或成环）时靠项数上限兜住，
 * 不会在这里死循环。
 */
internal fun SettingsPage.depth(): Int {
    var depth = 0
    var cursor = this
    while (cursor != SettingsPage.ROOT) {
        val next = cursor.parent()
        // 父页指回自己 = 配置写错；跳出而不是一直转，行为退化成「浅层」。
        if (next == cursor || depth > SettingsPage.entries.size) break
        cursor = next
        depth++
    }
    return depth
}
