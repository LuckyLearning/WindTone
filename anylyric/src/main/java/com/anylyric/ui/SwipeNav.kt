package com.anylyric.ui

import kotlin.math.abs

/**
 * 左右滑动切标签页的判定。
 *
 * 单独拎成纯函数只为一件事：手势阈值是「手感」的全部，改一次就得能回归一次。
 * 页面里只管把手指位移与速度喂进来，切换与否不散落在触摸回调中。
 *
 * 返回 +1 = 下一页（手指向左划）、-1 = 上一页、0 = 不动。
 */
internal object SwipeNav {

    /** 越过屏宽的这个比例就算翻页 —— 配合跟手位移，滑动 1/6 屏就该过去。 */
    const val DISTANCE_RATIO = 0.16f

    /** 快速轻扫的速度门槛（px/s）。慢速拖一点点不该翻页，快扫一小段应该翻。 */
    const val FLING_VELOCITY = 900f

    /** 快扫也必须真的移动过：否则「按下立刻抬起」的抖动会被当成划页。 */
    const val MIN_MOVE_PX = 24f

    fun decide(dx: Float, width: Int, velocityX: Float): Int {
        if (width <= 0) return 0
        val farEnough = abs(dx) >= width * DISTANCE_RATIO
        val fastEnough = abs(velocityX) >= FLING_VELOCITY && abs(dx) >= MIN_MOVE_PX
        if (!farEnough && !fastEnough) return 0
        // 手指往左推 = 看后面那一页。
        return if (dx < 0f) 1 else -1
    }
}
