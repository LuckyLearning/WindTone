package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.data.SettingsDefaults
import kotlin.math.roundToInt

/**
 * 悬浮窗的**几何计算**（v1.1.48）—— 纯函数，不碰 WindowManager / Prefs。
 *
 * 为什么再抽一层：窗口尺寸从「宽度一个百分比」变成了「宽 + 高 + 横 + 纵 四个百分比」，
 * 而这段算法一旦算错，表现是最难查的那种 —— 窗口跑到屏幕外、或者压成一条缝
 * （历史上「自检全绿、桌面什么都没有」两次都是这个原因）。摆在这里就能用单测钉死。
 *
 * 统一规则（两种形态共用）：
 *  - 宽度：屏宽 × 百分比，不低于调用方给的 [Params.minWidthPx]；
 *  - 高度：百分比 → 屏高（减底部让位）× 百分比；`0` 表示**自动**（由行数撑开，
 *    此时用 [Params.autoHeightPx] 参与纵向夹取）；
 *  - 位置：存的是「可移动余量的百分比」—— 0 = 最左/最上，100 = 最右/最下。
 *    用百分比而不是像素，换分辨率、转屏之后位置仍然正确。
 */
object OverlayFrame {

    /** `heightPercent` 取这个值表示「自动」：由内容（行数）决定高度 */
    const val AUTO_HEIGHT = 0

    data class Frame(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        /** true 表示高度该交给 `WRAP_CONTENT`，[height] 只是「当前测得的高度」，仅用于夹取 */
        val autoHeight: Boolean,
    )

    data class Params(
        val screenW: Int,
        val screenH: Int,
        val widthPercent: Int,
        /** 0 = 自动（[AUTO_HEIGHT]）；10..100 = 屏高减让位后的百分比 */
        val heightPercent: Int,
        val xPercent: Int,
        val yPercent: Int,
        /** 宽度下限（像素）：1% 宽度在 1080p 上只有 10px，既看不见也拖不着 */
        val minWidthPx: Int = 1,
        /** 底部让位（像素）：车机 Dock / 手势条，全屏形态才传 */
        val bottomReservedPx: Int = 0,
        /** 自动高度时窗内内容量出来的高度；还没测量过传 0 */
        val autoHeightPx: Int = 0,
    )

    fun frame(p: Params): Frame {
        val sw = p.screenW.coerceAtLeast(1)
        val sh = p.screenH.coerceAtLeast(1)

        val minW = p.minWidthPx.coerceIn(1, sw)
        val w = (sw * p.widthPercent.coerceIn(1, 100) / 100f).toInt().coerceIn(minW, sw)

        // 让位不能超过半屏：改 dpi / 极窄屏时否则会把可用高度算成负数
        val reserved = p.bottomReservedPx.coerceIn(0, (sh / 2).coerceAtLeast(0))
        val usableH = (sh - reserved).coerceAtLeast(1)

        val auto = p.heightPercent <= AUTO_HEIGHT
        val h = if (auto) {
            p.autoHeightPx.coerceIn(1, usableH).takeIf { p.autoHeightPx > 0 } ?: usableH
        } else {
            (usableH * p.heightPercent.coerceIn(1, 100) / 100f).toInt().coerceIn(1, usableH)
        }

        // 「屏幕 − 窗口」而不是「屏幕」：后者会把窗口整个推到边界之外
        val maxX = (sw - w).coerceAtLeast(0)
        val x = (maxX * p.xPercent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, maxX)
        // 纵向余量同样要扣掉底部让位：只按「屏高 − 窗高」算的话，把位置调到最下时
        // 窗口会正好盖在车机 Dock 上 —— 那正是 v1.1.14「设置点不到」的成因
        val maxY = (usableH - h).coerceAtLeast(0)
        val y = (maxY * p.yPercent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, maxY)
        return Frame(w, h, x, y, auto)
    }

    /**
     * 拖拽落盘的逆运算：把像素位置翻回「余量百分比」。
     *
     * [max] 是「屏宽 − 窗宽」这类可移动范围；为 0（满宽）时返回 [fallback]，
     * 保持用户原来的偏好不被抹成某个固定值 —— 满宽时随手拖一下不该改写设置。
     */
    fun percentOf(px: Int, max: Int, fallback: Int = 50): Int {
        if (max <= 0) return fallback.coerceIn(0, 100)
        return (px * 100f / max).roundToInt().coerceIn(0, 100)
    }
}

/**
 * 悬浮窗背景的**取色**（v1.1.48）。
 *
 * 两套语义并存：
 *  - 老的三档（无 / 半透明 / 实底）：保留，保证升级后看起来一模一样；
 *  - 自定义（颜色 + 不透明度）：用户显式打开才生效。
 *
 * 返回值 `null` 表示「完全透明」，调用方据此把背景置空 —— 注意这时**文字的描边要按
 * 无底板处理**，否则浅色字压在浅色车机画面上就是一片糊（v1.1.25 真机踩过的坑）。
 */
object OverlayBackground {

    /** 旧「半透明」档的不透明度：约等于当前默认值，换算法时观感不变 */
    const val TRANSLUCENT_ALPHA_PERCENT = 62

    /**
     * @param custom 是否启用自定义背景（颜色 + 不透明度）
     * @return ARGB 颜色；`null` = 全透明（不画底板）
     */
    fun resolve(backdrop: Int, custom: Boolean, color: Int, alphaPercent: Int): Int? {
        if (custom) {
            val alpha = alphaPercent.coerceIn(0, 100)
            if (alpha <= 0) return null
            return withAlpha(color, alpha)
        }
        return when (backdrop) {
            OverlayAppearance.BACKDROP_NONE -> null
            OverlayAppearance.BACKDROP_SOLID -> withAlpha(SettingsDefaults.OVERLAY_BG_COLOR, 100)
            else -> withAlpha(SettingsDefaults.OVERLAY_BG_COLOR, TRANSLUCENT_ALPHA_PERCENT)
        }
    }

    /**
     * 文字可读性该按哪一档算。
     *
     * 自定义背景把不透明度拉到 0 时，视觉上就是「无底板」，描边必须跟着变硬 ——
     * 否则会出现「没有底板、但描边按有底板给的柔光」，正是 v1.1.25 那个模糊问题。
     */
    fun legibilityKey(backdrop: Int, custom: Boolean, alphaPercent: Int): Int {
        if (!custom) return backdrop
        return if (alphaPercent <= 0) OverlayAppearance.BACKDROP_NONE else OverlayAppearance.BACKDROP_SOLID
    }

    fun withAlpha(color: Int, alphaPercent: Int): Int {
        val a = (255 * alphaPercent.coerceIn(0, 100) / 100f).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
