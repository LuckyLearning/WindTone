package com.luckylearning.windtone.overlay

import android.view.Gravity

/**
 * 悬浮歌词窗口的摆放计算 —— 纯函数，不碰 `WindowManager` / `Prefs`。
 *
 * 为什么值得单独抽出来：**「窗口跑到屏幕外」这条线上出过两次真机事故**
 * （归一化用「屏宽/2」把窗口推出屏幕、夹取只按屏幕尺寸不扣窗口自身尺寸），
 * 而这两次的效果都是「自检全绿，桌面上什么都没有」—— 极难排查。
 * 摆在这里就能用单测把边界钉死。
 *
 * 两条规则（详见 `docs/AI开发指南.md` §11.9）：
 * - **横向由「对齐方式」单独决定**：居中 → `TOP|CENTER_HORIZONTAL`（`x = 0` 是
 *   相对屏幕中心的偏移，才是真居中）；居左 → `TOP|START` + 左沿边距。
 * - **纵向沿用用户拖出来的坐标**：`savedY == [UNSET_Y]` 表示从没拖过，走默认高度。
 *
 * 所有坐标都要夹到屏幕内，且夹取上限必须用「屏幕尺寸 − 窗口自身尺寸」：
 * 只按屏幕尺寸的话 `x = screenW` 会让窗口**完全落在屏幕外**。
 */
object OverlayPositioner {

    /** 水平对齐：居中（默认） */
    const val ALIGN_CENTER = 0

    /** 水平对齐：居左 */
    const val ALIGN_LEFT = 1

    /** `savedY` 的这个值表示「用户从未拖动过」，此时用 [Params.defaultY] */
    const val UNSET_Y: Int = Int.MIN_VALUE

    /**
     * 摆放结果。`gravity` 直接用 `android.view.Gravity` 的组合位，
     * 因为它是编译期常量（`static final int`），纯 JVM 单测里也能安全求值。
     */
    data class Placed(val gravity: Int, val x: Int, val y: Int)

    /** 摆放所需的全部输入（都已换算成像素） */
    data class Params(
        /** 水平对齐档位，只认 [ALIGN_LEFT] 与 [ALIGN_CENTER]（其它值按居中） */
        val align: Int,
        /** Prefs 里的纵向坐标；[UNSET_Y] 表示从未拖过 */
        val savedY: Int,
        val screenW: Int,
        val screenH: Int,
        /** 窗口自身的宽 / 高；挂载瞬间还没测量时为 0（此时不夹取，等价于不生效） */
        val windowW: Int,
        val windowH: Int,
        /** 从未拖过时的默认纵向位置（调用方按「状态栏 + 64dp」算好） */
        val defaultY: Int,
        /** 居左时距屏幕左沿的边距（调用方按 dp 换算好） */
        val leftMargin: Int,
    )

    fun place(p: Params): Placed {
        val leftAligned = p.align == ALIGN_LEFT
        val gravity = if (leftAligned) {
            Gravity.TOP or Gravity.START
        } else {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        // 「屏幕 − 窗口」而不是「屏幕」：后者会把窗口整个推到边界之外。
        // 窗口还没测量（=0）时 maxX/maxY 退化成屏幕尺寸，夹取不生效 —— 这正是想要的，
        // 因为此刻夹取没有意义；挂载完成后布局回调会再摆一次。
        val maxX = (p.screenW - p.windowW).coerceAtLeast(0)
        val maxY = (p.screenH - p.windowH).coerceAtLeast(0)
        val x = if (leftAligned) p.leftMargin.coerceIn(0, maxX) else 0
        val y = (if (p.savedY == UNSET_Y) p.defaultY else p.savedY).coerceIn(0, maxY)
        return Placed(gravity, x, y)
    }
}
