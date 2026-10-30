package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import com.luckylearning.windtone.ui.theme.AppVisual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 一次滑动切歌的去向。[NONE] = 没到阈值，什么都不做。
 *
 * 2026-10-30 起：手机中部切歌主路径改到**纵向**（上滑下一首 / 下滑上一首）；
 * 横向改切「歌词 ↔ 封面」。车机中部横向仍切歌（双栏同时在，没有视图可切）。
 */
internal enum class SwipeSwitch { NONE, NEXT, PREVIOUS }

/**
 * 横向滑动切换「歌词 / 封面」面板的去向。
 *
 * - [SHOW_COVER]：向左划过阈值 → 封面
 * - [SHOW_LYRICS]：向右划过阈值 → 歌词
 * - [NONE]：没过阈值
 */
internal enum class SwipePane { NONE, SHOW_COVER, SHOW_LYRICS }

/**
 * 播放页中部手势的判据与参数。
 *
 * ## 2026-10-30 分工（Spike）
 *
 * | 方向 | 手机 | 车机 |
 * |---|---|---|
 * | 左右 | 歌词 ↔ 封面 | 仍切歌（双栏同场） |
 * | 上下 | 切歌 | 切歌 |
 *
 * ## 与歌词纵向滚动的冲突：**边缘才切歌**
 *
 * 歌词 `LazyColumn` / `verticalScroll` 先吃纵向；只有子级滚不动、把 overscroll
 * 剩给父级时（顶向下拉 / 底向上推），才累计并在松手时判切歌。中段滚歌词不误切。
 *
 * 封面态没有纵向滚动子级，走 [playerVerticalSkipDirect] 整区上下切。
 *
 * 阈值 / 甩速 / 阻力与 2026-10-27 同一套。
 */
internal object PlayerSwipe {

    /** 位移阈值：沿滑动轴尺寸的 12%。 */
    const val ThresholdRatio: Float = 0.12f

    /** 快速轻扫速度阈值（px/s）。 */
    const val FlingVelocity: Float = 900f

    /** 跟手位移上限：沿轴尺寸的 1/6。 */
    const val MaxOffsetRatio: Float = 1f / 6f

    /** 跟手阻力。 */
    const val Resistance: Float = 0.45f

    /**
     * 判定要不要切歌。
     *
     * @param dragPx 累计位移（向左 / 向上为负）
     * @param sizePx 沿该轴的手势区尺寸
     * @param velocity 松手瞬间沿轴速度（向左 / 向上为负）
     *
     * 位移与速度冲突时**以位移为准**。
     */
    fun resolve(dragPx: Float, sizePx: Float, velocity: Float = 0f): SwipeSwitch {
        if (sizePx <= 0f) return SwipeSwitch.NONE
        val farEnough = abs(dragPx) >= sizePx * ThresholdRatio
        val fastEnough = abs(velocity) >= FlingVelocity
        if (!farEnough && !fastEnough) return SwipeSwitch.NONE
        val dir = if (farEnough) dragPx else velocity
        return if (dir < 0f) SwipeSwitch.NEXT else SwipeSwitch.PREVIOUS
    }

    /**
     * 判定横向要不要切面板：向左 → 封面，向右 → 歌词。
     */
    fun resolvePane(dragPx: Float, widthPx: Float, velocityX: Float = 0f): SwipePane {
        if (widthPx <= 0f) return SwipePane.NONE
        val farEnough = abs(dragPx) >= widthPx * ThresholdRatio
        val fastEnough = abs(velocityX) >= FlingVelocity
        if (!farEnough && !fastEnough) return SwipePane.NONE
        val dir = if (farEnough) dragPx else velocityX
        return if (dir < 0f) SwipePane.SHOW_COVER else SwipePane.SHOW_LYRICS
    }

    fun resisted(dragPx: Float, sizePx: Float): Float {
        if (sizePx <= 0f) return 0f
        val cap = sizePx * MaxOffsetRatio
        return (dragPx * Resistance).coerceIn(-cap, cap)
    }

    const val SettleMs: Int = AppVisual.Motion.BaseMs
}

/** 横向：手机切歌词/封面。 */
internal fun Modifier.playerSwipeToPane(
    offset: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    onPane: (SwipePane) -> Unit,
): Modifier = pointerInput(Unit) {
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
            change.consume()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                offset.snapTo(PlayerSwipe.resisted(accum, size.width.toFloat()))
            }
        },
        onDragEnd = {
            val vx = tracker.calculateVelocity().x
            val pane = PlayerSwipe.resolvePane(accum, size.width.toFloat(), vx)
            if (pane != SwipePane.NONE) onPane(pane)
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

/**
 * 横向滑动切歌（车机中部 / 旧调用兼容）。
 *
 * ⚠️ 只能有一层 `pointerInput`（v1.2.10 双层导致手势未挂上）。
 */
internal fun Modifier.playerSwipeToSwitch(
    offset: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    onSwipe: (SwipeSwitch) -> Unit,
): Modifier = pointerInput(Unit) {
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
            change.consume()
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

/**
 * 纵向切歌 · 边缘 overscroll 路径（歌词态 / 车机左列）。
 *
 * 子级滚得动时 available.y == 0，这里不累计；滚不动才认。
 */
internal fun Modifier.playerVerticalSkipEdge(
    onSwipe: (SwipeSwitch) -> Unit,
): Modifier = composed {
    val connection = remember(onSwipe) { VerticalSkipConnection(onSwipe) }
    Modifier
        .onSizeChanged { connection.updateAxisSize(it.height.toFloat()) }
        .nestedScroll(connection)
}

/**
 * 纵向切歌 · 直接拖路径（封面态 / 无滚动子级）。
 *
 * 与 [playerVerticalSkipEdge] 不要叠在同一可滚动树上，否则边缘可能连跳两首。
 */
internal fun Modifier.playerVerticalSkipDirect(
    onSwipe: (SwipeSwitch) -> Unit,
): Modifier = pointerInput(onSwipe) {
    val tracker = VelocityTracker()
    var accum = 0f
    detectVerticalDragGestures(
        onDragStart = {
            accum = 0f
            tracker.resetTracking()
        },
        onVerticalDrag = { change, dragAmount ->
            accum += dragAmount
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
        },
        onDragEnd = {
            val vy = tracker.calculateVelocity().y
            val dir = PlayerSwipe.resolve(accum, size.height.toFloat(), vy)
            if (dir != SwipeSwitch.NONE) onSwipe(dir)
        },
    )
}

/**
 * 歌词滚到顶/底后，把子级吃不掉的纵向 overscroll 收成切歌。
 */
internal class VerticalSkipConnection(
    private val onSwipe: (SwipeSwitch) -> Unit,
) : NestedScrollConnection {
    private var accum = 0f
    private var axisPx = 0f

    fun updateAxisSize(px: Float) {
        axisPx = px
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (available.y == 0f) return Offset.Zero
        accum += available.y
        return Offset(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val size = when {
            axisPx > 0f -> axisPx
            abs(accum) > 0f -> abs(accum) / PlayerSwipe.ThresholdRatio
            else -> 0f
        }
        val dir = PlayerSwipe.resolve(accum, size, available.y)
        accum = 0f
        if (dir != SwipeSwitch.NONE) onSwipe(dir)
        return Velocity.Zero
    }
}
