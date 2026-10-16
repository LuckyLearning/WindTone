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
 * 两条规则（2026-10-16 用户拍板，推翻此前的「横向归对齐设置」）：
 * - **窗口位置完全由拖动决定**：`savedX` / `savedY` 都是拖出来的坐标，
 *   没拖过（= [UNSET]）才走默认值 —— 横向水平居中、纵向「状态栏下方 64dp」。
 *   设置里的「对齐」**只管歌词文字在窗口内部怎么排**（居中 / 靠左），
 *   与窗口摆在屏幕哪儿无关。
 * - 所有坐标都要夹到屏幕内，且夹取上限必须用「屏幕尺寸 − 窗口自身尺寸」：
 *   只按屏幕尺寸的话 `x = screenW` 会让窗口**完全落在屏幕外**。
 *
 * ⚠️ 由上面第二条推出一条容易被当成 bug 的几何事实：**窗口宽度直接决定了横向能拖多远**
 * （可移动量 = 屏宽 − 窗宽）。窗口宽度 = 屏宽时这个量正好是 0，「悬浮窗只能上下移动」
 * 就是它造成的 —— 不是接线错误。所以 `SettingsDefaults.OVERLAY_WIDTH_PERCENT` 默认取 80%。
 */
object OverlayPositioner {

    /** 「对齐」档位：窗内文字居中（默认）。现在只影响文字排版，不影响窗口位置 */
    const val ALIGN_CENTER = 0

    /** 「对齐」档位：窗内文字靠左。现在只影响文字排版，不影响窗口位置 */
    const val ALIGN_LEFT = 1

    /** `savedX` / `savedY` 的这个值表示「用户从未拖动过」，此时走默认位置 */
    const val UNSET: Int = Int.MIN_VALUE

    /** 兼容旧名（2026-10-16 前只有纵向可拖） */
    const val UNSET_Y: Int = UNSET

    /**
     * 摆放结果。`gravity` 直接用 `android.view.Gravity` 的组合位，
     * 因为它是编译期常量（`static final int`），纯 JVM 单测里也能安全求值。
     */
    data class Placed(val gravity: Int, val x: Int, val y: Int)

    /** 摆放所需的全部输入（都已换算成像素） */
    data class Params(
        /** Prefs 里的横向坐标；[UNSET] 表示从未拖过（走水平居中） */
        val savedX: Int,
        /** Prefs 里的纵向坐标；[UNSET] 表示从未拖过（走 [defaultY]） */
        val savedY: Int,
        val screenW: Int,
        val screenH: Int,
        /** 窗口自身的宽 / 高；挂载瞬间还没测量时为 0（此时不夹取，等价于不生效） */
        val windowW: Int,
        val windowH: Int,
        /** 从未拖过时的默认纵向位置（调用方按「状态栏 + 64dp」算好） */
        val defaultY: Int,
    )

    fun place(p: Params): Placed {
        // 「屏幕 − 窗口」而不是「屏幕」：后者会把窗口整个推到边界之外。
        // 窗口还没测量（=0）时 maxX/maxY 退化成屏幕尺寸，夹取不生效 —— 这正是想要的，
        // 因为此刻夹取没有意义；挂载完成后布局回调会再摆一次。
        val maxX = (p.screenW - p.windowW).coerceAtLeast(0)
        val maxY = (p.screenH - p.windowH).coerceAtLeast(0)
        // 默认横向 = 水平居中。必须用「(屏宽 − 窗宽) / 2」而不是 gravity 的
        // CENTER_HORIZONTAL：后者要求 x = 0 是「相对屏幕中心的偏移」，与
        // 拖动坐标系（x = 距屏幕左沿的像素）不能共存，拖一下就会跳半屏。
        val defaultX = maxX / 2
        val x = (if (p.savedX == UNSET) defaultX else p.savedX).coerceIn(0, maxX)
        val y = (if (p.savedY == UNSET) p.defaultY else p.savedY).coerceIn(0, maxY)
        return Placed(Gravity.TOP or Gravity.START, x, y)
    }
}
