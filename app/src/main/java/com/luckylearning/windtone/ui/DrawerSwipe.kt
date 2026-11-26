package com.luckylearning.windtone.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 打开抽屉的回调。
 *
 * 挂在页面上的手势如果用 `pointerInput(onOpen)`，父级每次重组都会换一个 lambda，
 * 手势协程跟着重启。播放进度大约每秒重组一次，滑到一半会被掐掉。
 * 回调放进稳定对象，手势循环里读字段，重组不再拆掉正在进行的滑动。
 */
internal class DrawerOpener {
    var onOpen: () -> Unit = {}
}

/**
 * 右滑开抽屉，和子级纵向列表抢同一根手指时怎么判。
 *
 * 洛雪页把这个手势挂在整页的祖先上。`detectHorizontalDragGestures` 一旦水平位移过了
 * slop 就会 consume 后续事件，排行榜的 LazyColumn 在 Main pass 里再也拿不到这段拖动，
 * 手指不松开列表就停住，松开后再滑又正常 —— 「一卡一卡」。
 * 纵向分量不小于横向时必须让给列表；只有明显向右、并且滑得够长，才打开抽屉。
 */
internal object DrawerSwipe {
    enum class Decision { PENDING, OPEN, YIELD }

    fun decide(dx: Float, dy: Float, slop: Float): Decision {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax < slop && ay < slop) return Decision.PENDING
        if (ay >= ax) return Decision.YIELD
        return if (dx > 0f) Decision.OPEN else Decision.YIELD
    }
}

/**
 * 挂在纵向列表的**祖先**上。在 Initial pass 里观察，未判定或判定为纵向时不 consume，
 * 子列表照常滚动；只有判定为向右开抽屉之后才 consume。
 *
 * 判定为纵向之后**不能退出**这次手势。[awaitEachGesture] 在块返回时会去等全部手指抬起。
 * 手指还按着就退出，再碰上列表被详情页拆出组合，这次等待会把后面的滑动一起停住。
 * 所以纵向让出后继续看到松手，只是不再 consume。
 */
internal fun Modifier.drawerSwipe(enabled: Boolean, opener: DrawerOpener): Modifier {
    if (!enabled) return this
    return pointerInput(opener) {
        val slop = viewConfiguration.touchSlop
        val openDistance = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            // 记「当前」判定，而不是「曾经打开过」：先右滑再转纵向，松手不能开抽屉。
            var decision = DrawerSwipe.Decision.PENDING
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (decision == DrawerSwipe.Decision.OPEN && dx >= openDistance) opener.onOpen()
                    break
                }
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                decision = DrawerSwipe.decide(dx, dy, slop)
                when (decision) {
                    DrawerSwipe.Decision.PENDING -> Unit
                    // 让给列表，但手指还按着，循环留到松手。
                    DrawerSwipe.Decision.YIELD -> Unit
                    DrawerSwipe.Decision.OPEN -> change.consume()
                }
            }
        }
    }
}

/** 测试和一次性调用。页面上要传 [remember] 过的 [DrawerOpener]，避免重组重启手势。 */
internal fun Modifier.drawerSwipe(enabled: Boolean, onOpen: () -> Unit): Modifier {
    val opener = DrawerOpener()
    opener.onOpen = onOpen
    return drawerSwipe(enabled, opener)
}
