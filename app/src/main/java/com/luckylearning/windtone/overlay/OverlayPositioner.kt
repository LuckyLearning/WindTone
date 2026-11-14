package com.luckylearning.windtone.overlay

import android.view.Gravity
import kotlin.math.roundToInt
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

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

    /**
     * 「对齐」档位：靠右（v1.2.54）。
     *
     * **只有标题块用得到**：歌词本体的对齐仍是「居中 / 靠左」两档，
     * 这一档进不了 [Prefs.lyricOverlayAlign]（它的 setter 夹在 0..1）。
     */
    const val ALIGN_RIGHT = 2

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

    /**
     * 把「余量百分比」翻译成人话，给设置页与自检共用。
     *
     * 两端各留 2% 的容差：拖动落盘是浮点换算，用户停在「大概最左」时
     * 存下来的可能是 1 或 2 —— 把它显示成「偏左 1%」既无意义又像 bug。
     */
    fun offsetLabel(offsetPercent: Int): String {
        val v = offsetPercent.coerceIn(0, 100)
        return when {
            v <= 2 -> AppStrings.s(R.string.ovp_left).ifEmpty { "靠左" }
            v >= 98 -> AppStrings.s(R.string.ovp_right).ifEmpty { "靠右" }
            kotlin.math.abs(v - 50) <= 2 -> AppStrings.s(R.string.ovp_center).ifEmpty { "居中" }
            v < 50 -> AppStrings.s(R.string.ovp_left_pct, v).ifEmpty { "偏左 ${v}%" }
            else -> AppStrings.s(R.string.ovp_right_pct, v).ifEmpty { "偏右 ${v}%" }
        }
    }

    /**
     * 拖拽落盘的逆运算：把全屏模式下的窗口横坐标 `x` 翻回「余量百分比」。
     *
     * 与 [fullFrame] 的 `x = maxX * offsetPercent / 100` 互逆，单测钉死：
     * `[maxX] <= 0`（满宽、水平不可拖动）时返回 [range] 起点，保持用户原偏好不被抹成居中。
     */
    fun offsetPercentFromX(x: Int, maxX: Int, range: IntRange): Int {
        if (maxX <= 0) return range.first
        return (x * 100f / maxX).roundToInt().coerceIn(range.first, range.last)
    }

    /**
     * 「全屏高度」模式（v1.1.19）下的窗口外形：宽度 = 屏宽 × 百分比，
     * 高度 = 屏高 − 底部让位，纵向钉在顶部，横向按 [offsetPercent] 在
     * 「屏宽 − 窗宽」这段余量里取位。
     *
     * 底部让位是给车机 Dock 留的：车机（DiLink 等）底部有一条系统常驻按钮栏，
     * 悬浮窗压上去就会盖住返回 / 空调这些键 —— v1.1.14 的「设置点不到」正是
     * 这条浮层压出来的，别再犯一次。
     *
     * **为什么位置用百分比而不是像素**（v1.1.20）：全屏模式是可以左右挪的，
     * 但「左右能挪多远」跟着宽度走（余量 = 屏宽 − 窗宽）。存像素的话，
     * 换分辨率 / 转屏后那个绝对坐标会跑到屏幕外或者贴在奇怪的位置；
     * 存「余量的百分比」则天然跟着屏宽缩放：0 = 最左、50 = 居中、100 = 最右。
     * 单条字幕那边仍存像素 —— 它的窗口小而自由，用户拖到哪儿就是哪儿。
     *
     * 夹取都是**防呆**，不是正常路径：
     *  - 宽度不低于屏宽 30%（再窄就放不下 11 行歌词，成了条竖线）；
     *  - 让位不能超过半屏（改 dpi / 极窄屏时，否则窗口会被算成一条缝）；
     *  - 宽高至少 1px —— `LayoutParams.height = 0` 在部分 ROM 上等于「不显示」，
     *    而这个模式的失败表现就是「开关开了、屏幕上什么都没有」，极难排查。
     */
    data class FullFrame(val width: Int, val height: Int, val x: Int, val y: Int)

    /** 全屏模式宽度的下限（占屏宽的百分比）：比这更窄，11 行歌词会挤成一条竖线 */
    const val FULL_WIDTH_MIN_PERCENT = 30

    fun fullFrame(
        screenW: Int,
        screenH: Int,
        bottomReserved: Int,
        widthPercent: Int = 100,
        offsetPercent: Int = 50,
    ): FullFrame {
        val safeW = screenW.coerceAtLeast(1)
        val pct = widthPercent.coerceIn(FULL_WIDTH_MIN_PERCENT, 100)
        val w = (safeW * pct / 100f).toInt().coerceIn(1, safeW)
        val reserved = bottomReserved.coerceIn(0, (screenH / 2).coerceAtLeast(0))
        val h = (screenH - reserved).coerceAtLeast(1)
        // 横向余量：宽度 = 屏宽时正好是 0（此时水平位置滑块怎么调都不动，
        // 与单条字幕 100% 宽时「只能上下拖」是同一个几何事实）。
        val maxX = (safeW - w).coerceAtLeast(0)
        val x = (maxX * offsetPercent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, maxX)
        return FullFrame(w, h, x, 0)
    }
}
