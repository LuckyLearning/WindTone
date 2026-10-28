package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.luckylearning.windtone.ui.theme.AppVisual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 一次横向滑动切歌的去向。[NONE] = 没到阈值，什么都不做。 */
internal enum class SwipeSwitch { NONE, NEXT, PREVIOUS }

/**
 * 播放页「左滑下一首 / 右滑上一首」的判据与参数（2026-10-27）。
 *
 * 迷你播放条的封面区早就有这个手势（`Common.kt` 的 `swipeAndDoubleTap`），但那只覆盖
 * 96dp 见方的一小块；播放页整屏反而没有 —— 车机上行车时去点右上角那个 48dp 的「下一首」，
 * 比划一下难得多。
 *
 * 与迷你条那套的区别只有一点：**跟手**。手指拖动时中部内容跟着位移一小段（带阻力衰减），
 * 松手若没过阈值就弹回原位 —— 有这一步，用户才知道「这一划被认了」，而不是划完歌变了、
 * 屏幕上什么都没发生。位移上限刻意压在屏宽的 1/6：整屏跟着走会盖掉顶栏和底部控制行。
 *
 * 手势挂在**中部内容区**（歌词 / 封面）而不是整页：
 *  - 顶部有返回箭头与歌词 / 封面切换键，底部有进度条（本身就是横向拖动）与播放键，
 *    挂整页必然要和进度条抢手势；
 *  - 中部歌词区是纵向滚动，横向手势与之天然互斥（方向滑差判定），不会互相打岔。
 */
internal object PlayerSwipe {

    /** 位移阈值：屏宽的 12%。车机 1707dp 宽约 205dp，手机 400dp 宽约 48dp。 */
    const val ThresholdRatio: Float = 0.12f

    /**
     * 快速轻扫的速度阈值（px/s）。位移不够但甩得够快也算切歌 ——
     * 行车中手指划不了那么长，只认位移会让人觉得「划了没反应」。
     */
    const val FlingVelocity: Float = 900f

    /** 跟手位移上限：屏宽的 1/6。 */
    const val MaxOffsetRatio: Float = 1f / 6f

    /** 跟手阻力：手指走 1px，内容只走 0.45px。 */
    const val Resistance: Float = 0.45f

    /**
     * 判定这次滑动要不要切歌。
     *
     * @param dragPx 本次手势的累计横向位移（向左为负）
     * @param widthPx 手势区域宽度
     * @param velocityX 松手瞬间的横向速度（px/s，向左为负）
     *
     * 位移与速度方向冲突时（甩出去又拉回来）**以位移为准**：那种手势的真实意图
     * 就是「不想切」，按速度判会反向跳一首。
     */
    fun resolve(dragPx: Float, widthPx: Float, velocityX: Float = 0f): SwipeSwitch {
        if (widthPx <= 0f) return SwipeSwitch.NONE
        val farEnough = abs(dragPx) >= widthPx * ThresholdRatio
        val fastEnough = abs(velocityX) >= FlingVelocity
        if (!farEnough && !fastEnough) return SwipeSwitch.NONE
        val dir = if (farEnough) dragPx else velocityX
        return if (dir < 0f) SwipeSwitch.NEXT else SwipeSwitch.PREVIOUS
    }

    /**
     * 跟手位移：加阻力并夹在上限内。
     *
     * 夹上限而不是让它一直跟手，是为了滑动过程中**顶栏与底部控制行不被盖住**；
     * 到上限后手指继续走，内容不再动 —— 弹性到头的感觉本身就传达了「够了」。
     */
    fun resisted(dragPx: Float, widthPx: Float): Float {
        if (widthPx <= 0f) return 0f
        val cap = widthPx * MaxOffsetRatio
        return (dragPx * Resistance).coerceIn(-cap, cap)
    }

    /** 松手回弹时长，与全站 `--dur-2` 同源。 */
    const val SettleMs: Int = AppVisual.Motion.BaseMs
}

/**
 * 把「横向滑动切歌」挂到某个区域上。
 *
 * @param offset 跟手位移量（调用方负责读它做 `graphicsLayer` 平移）
 * @param scope 用于驱动 [offset] 的作用域
 * @param onSwipe 判定出方向后的回调（[SwipeSwitch.NONE] 不会回调）
 */
internal fun Modifier.playerSwipeToSwitch(
    offset: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    onSwipe: (SwipeSwitch) -> Unit,
): Modifier = pointerInput(Unit) {
    // ⚠️ 这里**只能有一层** `pointerInput`。
    // v1.2.10 曾写成 `pointerInput(Unit) { pointerInput(Unit) { ... } }`：外层 block 里
    // 那次 `pointerInput` 只是又构造了一个 Modifier（扩展函数接收者仍是本函数的 `this`），
    // 返回值没人接，等于**手势检测器从未挂到任何节点上** —— 滑动完全无反应，而编译、
    // 单测、lint、打包全都不会报（判据纯函数的 8 例单测照样全绿）。
    // 回归锁：`PlayerSwipeGestureTest`（真滑一次，断言回调收到了方向）。
    val tracker = VelocityTracker()
    var accum = 0f
    detectHorizontalDragGestures(
        onDragStart = {
            accum = 0f
            tracker.resetTracking()
        },
        onHorizontalDrag = { change, dragAmount ->
            accum += dragAmount
            tracker.addPosition(change.uptimeMillis, change.position)
            // 手势已被识别为横向：这里 consume 掉，歌词区的纵向滚动就不会
            // 在后续事件里再抢一次（两者本来方向互斥，消费只是省掉竞争）。
            change.consume()
            // UNDISPATCHED：跟手位移必须**当场**写进 Animatable。
            // 普通 launch 会先入队再派发，120Hz 拖动时几十个协程排队，
            // 屏幕上就会出现「手指已经走了、内容还在追」的滞后。
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                offset.snapTo(PlayerSwipe.resisted(accum, size.width.toFloat()))
            }
        },
        onDragEnd = {
            val vx = tracker.calculateVelocity().x
            val dir = PlayerSwipe.resolve(accum, size.width.toFloat(), vx)
            if (dir != SwipeSwitch.NONE) onSwipe(dir)
            scope.launch {
                offset.animateTo(
                    0f,
                    tween(PlayerSwipe.SettleMs, easing = AppVisual.Motion.Ease),
                )
            }
        },
        onDragCancel = {
            scope.launch {
                offset.animateTo(
                    0f,
                    tween(PlayerSwipe.SettleMs, easing = AppVisual.Motion.Ease),
                )
            }
        },
    )
}
