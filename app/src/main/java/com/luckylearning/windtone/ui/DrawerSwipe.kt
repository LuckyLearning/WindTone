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
 */
internal fun Modifier.drawerSwipe(enabled: Boolean, onOpen: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(onOpen) {
        val slop = viewConfiguration.touchSlop
        val openDistance = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var opening = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (opening && dx >= openDistance) onOpen()
                    break
                }
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                when (DrawerSwipe.decide(dx, dy, slop)) {
                    DrawerSwipe.Decision.PENDING -> Unit
                    DrawerSwipe.Decision.YIELD -> break
                    DrawerSwipe.Decision.OPEN -> {
                        opening = true
                        change.consume()
                    }
                }
            }
        }
    }
}
