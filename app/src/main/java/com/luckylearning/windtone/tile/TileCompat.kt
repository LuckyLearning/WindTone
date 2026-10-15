package com.luckylearning.windtone.tile

import android.os.Build
import android.service.quicksettings.Tile

/**
 * 磁贴的跨版本小工具。
 *
 * ## 为什么需要它
 *
 * `Tile.setSubtitle` 是 **API 29** 才加的方法，而本应用 `minSdk = 24`。
 * 低版本上直接给它赋值不是"降级显示"，是**抛 `NoSuchMethodError`** ——
 * 磁贴的刷新跑在应用进程里、由 SystemUI 的回调触发，等于用户一下拉通知面板就崩，
 * 而且堆栈完全看不出和磁贴有关（lint 的 `NewApi` 报的就是这一条）。
 *
 * 低版本就不设副标题：标签本身已经能把"是什么磁贴 / 在放什么"说清楚，
 * 副标题只是锦上添花，不值得为它冒崩溃风险。
 */
internal fun Tile.setSubtitleCompat(text: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = text
}
