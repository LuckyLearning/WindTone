package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.PlayerPane
import com.luckylearning.windtone.ui.theme.AppVisual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 一次边界滑动的去向（v1.2.55 起只剩「页滑到头了 → 切歌」这一种用法）。
 *
 * [NONE] 不会再由 [PlayerSwipe.resolvePage] 产出，保留它是为了让 `when` 分支完整、
 * 也让调用方的「无动作」语义有个显式值。
 */
internal enum class SwipeSwitch { NONE, NEXT, PREVIOUS }

/**
 * 一次横向滑动的判定结果（v1.2.55）。
 *
 * 播放页中部是三页横排（信息 / 图片 / 歌词），左右滑先翻页；
 * 只有**首尾两页再往外滑**才落到切歌上 —— 这样「页」和「曲」共用一根横轴，
 * 越界的那一下正好是「越过这一张，换下一首」的直觉。
 */
internal sealed interface PageSwipe {
    /** 没到阈值：留在原页 */
    data object Stay : PageSwipe

    /** 翻到相邻页 */
    data class To(val target: PlayerPane) : PageSwipe

    /** 已经在首 / 尾页还继续往外滑：切上一首 / 下一首 */
    data class Skip(val dir: SwipeSwitch) : PageSwipe
}

/**
 * 播放页中部手势的判据与参数（v1.2.55 重写）。
 *
 * ## 为什么不再有「上下滑切歌」
 *
 * 歌词页的 `LazyColumn` 本身就是纵向滚动。旧实现靠「滚到边界才认上下滑」绕开
 * 冲突，但那套机制在车机上会让人分不清「我是在滚歌词还是在切歌」，而且图片页 /
 * 信息页根本没有纵向内容可滚，上下滑纯粹是多余的第二套切歌手势。
 * 现在切歌只走横轴末端，纵向整根轴留给滚动 —— 手势与内容一一对应。
 *
 * ## 与歌词纵向滚动的关系
 *
 * `detectHorizontalDragGestures` 自带方向锁：纵向先超过 touch slop 时它直接放弃，
 * 事件留给子级 `LazyColumn`。所以横向翻页和纵向滚歌词可以叠在同一棵树上。
 */
internal object PlayerSwipe {

    /**
     * 翻页位移阈值：沿横向尺寸的 18%。
     *
     * 比切歌时代的 12% 更「钝」一点 —— 翻页是低成本动作，但误翻会打断正在读的
     * 那一句；宁可让用户多滑一点，也不要一碰就翻过去。
     */
    const val PageThresholdRatio: Float = 0.18f

    /** 快速轻扫速度阈值（px/s）：手指划不长时的兜底 */
    const val FlingVelocity: Float = 900f

    /**
     * 位移阈值的**上限**（dp）。
     *
     * 纯按比例有个致命副作用：车机中部宽 1000~1700dp，18% 就是 180~307dp —— 那已经不叫
     * 「滑一下」了，用户划到一半就松手，判定为「没到阈值」，体感是「这页滑不动」。
     * 手机上 360dp × 18% ≈ 65dp，永远碰不到这个上限，行为不变。
     */
    const val PageThresholdMaxDp: Float = 96f

    /** 首尾页越界时的跟手阻力（不翻页，只给「到边了」的反馈） */
    const val EdgeResistance: Float = 0.32f

    /** 越界跟手位移上限：横向尺寸的 30% */
    const val EdgeMaxRatio: Float = 0.30f

    /** 翻到位 / 回弹的动画时长 */
    const val SettleMs: Int = AppVisual.Motion.BaseMs

    /** 切歌后新页滑入的时长：比回弹略长，让「换了一首」这件事有存在感 */
    const val SkipSettleMs: Int = AppVisual.Motion.BaseMs + 80

    /**
     * 判定这次横滑该翻页还是切歌。
     *
     * @param dragPx 累计位移（向左为负）
     * @param widthPx 中部手势区宽度（px）
     * @param velocityX 松手瞬间横向速度（向左为负）
     * @param pane 拖动开始时所处的页
     *
     * 位移与速度冲突时**以位移为准**（与旧版切歌判据同一原则：甩出去又拉回来时
     * 用户的真实意图是不动）。
     *
     * @param maxThresholdPx 位移阈值的硬上限（px），传 `Float.POSITIVE_INFINITY` 即纯按比例。
     *   调用方按 [PageThresholdMaxDp] 换算后传进来 —— 宽屏上按比例算出来的阈值太长，
     *   见 [PageThresholdMaxDp]。
     */
    fun resolvePage(
        dragPx: Float,
        widthPx: Float,
        velocityX: Float = 0f,
        pane: PlayerPane,
        maxThresholdPx: Float = Float.POSITIVE_INFINITY,
    ): PageSwipe {
        if (widthPx <= 0f) return PageSwipe.Stay
        val threshold = minOf(widthPx * PageThresholdRatio, maxThresholdPx)
        val farEnough = abs(dragPx) >= threshold
        val fastEnough = abs(velocityX) >= FlingVelocity
        if (!farEnough && !fastEnough) return PageSwipe.Stay
        val dir = if (farEnough) dragPx else velocityX
        return if (dir < 0f) {
            pane.next()?.let { PageSwipe.To(it) } ?: PageSwipe.Skip(SwipeSwitch.NEXT)
        } else {
            pane.previous()?.let { PageSwipe.To(it) } ?: PageSwipe.Skip(SwipeSwitch.PREVIOUS)
        }
    }

    /**
     * 跟手位移（px）。
     *
     * 页内 1:1 跟手（要的就是「拖到哪看到哪」）；首尾页越界时乘 [EdgeResistance]
     * 并夹在 [EdgeMaxRatio] 内 —— 拖不动多少，但手指能感到「这里到头了」。
     */
    fun followPx(dragPx: Float, widthPx: Float, pane: PlayerPane): Float {
        if (widthPx <= 0f) return 0f
        val atEdge = (dragPx < 0f && pane.next() == null) || (dragPx > 0f && pane.previous() == null)
        if (!atEdge) return dragPx.coerceIn(-widthPx, widthPx)
        val cap = widthPx * EdgeMaxRatio
        return (dragPx * EdgeResistance).coerceIn(-cap, cap)
    }

    /** 从 [from] 到 [to] 的位移，单位「页」；正值 = 目标在右侧 */
    fun pageDelta(from: PlayerPane, to: PlayerPane): Int = to.index - from.index

    /**
     * 把 [to] 页搬到中心的位移（px）。
     *
     * 三页的布局相位是 `(页序 - 当前页序) * 页宽 + 位移`：要让 [to] 落到 0，
     * 位移必须等于 `([from] - [to]) * 页宽` —— **和 [pageDelta] 反号**。
     * 左滑翻到下一页时它是负的（整排继续往左推）。
     *
     * ⚠️ 这里曾经直接用了 [pageDelta]，结果每次翻页都是「先往反方向弹回、
     * 再硬切到目标页」，v1.2.55 上体感就是「滑一下什么都没变」（2026-11-14 用户反馈）。
     */
    fun settlePx(from: PlayerPane, to: PlayerPane, widthPx: Float): Float =
        (from.index - to.index) * widthPx
}

/**
 * 三页横向翻页手势（v1.2.55）。
 *
 * 三个要点：
 *  1. **只挂中部**：底部进度条是横向拖动，整页挂必然抢手势。
 *  2. **翻页与切歌共用一次判定**（[PlayerSwipe.resolvePage]）：翻到相邻页就把页面
 *     滑到位再回报；首尾页再往外滑就先滑出、回报切歌、然后让新页从另一侧滑入。
 *  3. **`currentPane` / `onSettle` 用 `rememberUpdatedState` 兜一层**：
 *     `pointerInput(Unit)` 的 block 只在首次组合执行一次，闭包捕获的是**那一刻**的
 *     lambda 实例。调用方写的是 `{ PlayerPane.of(state.playerPane) }`，而 `state`
 *     是个普通值参数 —— 捕获住就永远是打开播放页时的那个页号，翻页后再滑仍按旧页
 *     判定（停在歌词页时表现为「怎么滑都是下一首」）。v1.2.55 首发即踩此坑。
 *
 * ⚠️ 只能有一层 `pointerInput`（v1.2.10 双层导致手势未挂上）。
 */
@Composable
internal fun Modifier.playerSwipePages(
    offset: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    currentPane: () -> PlayerPane,
    onSettle: (PageSwipe) -> Unit,
): Modifier {
    // 每次重组都把最新的 lambda 实例塞进这个 State；pointerInput 里读 .value，
    // 于是既不用把页号写进 key（那会在翻页瞬间重建 pointerInput、丢掉手势状态），
    // 也不会读到第一次组合时的旧值。
    val paneLatest = rememberUpdatedState(currentPane)
    val settleLatest = rememberUpdatedState(onSettle)

    return pointerInput(Unit) {
        val tracker = VelocityTracker()
        var accum = 0f
        // 拖动起点处的位移：上一段归位动画没跑完就被新拖动打断时，
        // 从这里接着跟手，页面不会先跳回 0 再走。
        var startOffset = 0f
        var paneAtStart = PlayerPane.ART
        val settle = tween<Float>(PlayerSwipe.SettleMs, easing = AppVisual.Motion.Ease)

        detectHorizontalDragGestures(
            onDragStart = {
                accum = 0f
                paneAtStart = paneLatest.value()
                startOffset = offset.value
                tracker.resetTracking()
                // 上一段归位动画可能还没跑完：让它停下，从当前位移继续跟手，
                // 否则 snapTo 会和 animateTo 抢同一个 Animatable。
                scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.stop() }
            },
            onHorizontalDrag = { change, dragAmount ->
                accum += dragAmount
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offset.snapTo(
                        PlayerSwipe.followPx(startOffset + accum, size.width.toFloat(), paneAtStart),
                    )
                }
            },
            onDragEnd = {
                val vx = tracker.calculateVelocity().x
                val width = size.width.toFloat()
                // 阈值上限按 dp 换算：宽屏（车机）上不能让用户划 300dp 才算翻页
                val maxThresholdPx = PlayerSwipe.PageThresholdMaxDp.dp.toPx()
                val result = PlayerSwipe.resolvePage(accum, width, vx, paneAtStart, maxThresholdPx)
                scope.launch {
                    when (result) {
                        PageSwipe.Stay -> offset.animateTo(0f, settle)

                        is PageSwipe.To -> {
                            // 先滑到目标页的位置，再换页 —— 换页时 offset 归零，
                            // 两者在同一帧生效，视觉上就是一整张翻过去。
                            offset.animateTo(
                                PlayerSwipe.settlePx(paneAtStart, result.target, width),
                                settle,
                            )
                            settleLatest.value(result)
                            offset.snapTo(0f)
                        }

                        is PageSwipe.Skip -> {
                            // 越过这张 → 当前页滑出屏幕，切歌，新页（图片页）从反侧滑入。
                            val outPx = if (result.dir == SwipeSwitch.NEXT) -width else width
                            offset.animateTo(outPx, settle)
                            settleLatest.value(result)
                            offset.snapTo(-outPx)
                            offset.animateTo(
                                0f,
                                tween(PlayerSwipe.SkipSettleMs, easing = AppVisual.Motion.Ease),
                            )
                        }
                    }
                }
            },
            onDragCancel = { scope.launch { offset.animateTo(0f, settle) } },
        )
    }
}
