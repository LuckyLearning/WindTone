package com.luckylearning.windtone.overlay

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.luckylearning.windtone.data.SettingsDefaults
import kotlin.math.roundToInt

/**
 * 悬浮歌词的**文本样式**（span 组装）。
 *
 * 从 `LyricOverlayService` 抽出：这段逻辑只依赖「几行文字 + 是否全屏 + 基础色 +
 * 有没有底板」，不碰窗口、生命周期、协程；抽出来后可以在 Robolectric 里直接断言
 * 「主角/配角/译文分别套了什么 span」，不必把整个 Service 拉起来。
 *
 * 三种角色的样式只在这里定义：
 *  - 主角：最亮（颜色由 TextView 的文字色决定，单行时走纯字符串快路径，零 span）；
 *  - 配角（上下句、间奏时的主角）：压暗；
 *  - 双语副行：压暗 + 小一号；全屏模式下再额外压暗一档。
 */
internal object OverlayTextStyler {

    /** 全屏模式：当前行字号相对倍率（播放页 17.5 / 15.5 ≈ 1.13） */
    private const val FULL_CURRENT_SCALE = 1.13f

    /** 全屏模式：译文行字号相对倍率（播放页 13 / 15.5 ≈ 0.84） */
    private const val FULL_TRANSLATION_SCALE = 0.84f

    /** 双语副行的字号倍率（相对主角） */
    private const val TRANSLATION_SCALE = 0.82f

    /**
     * 单条模式下配角的基准不透明度（150/255 ≈ 59%）。
     * v1.1.52 压暗开放为设置项后，单条模式以它为基准按 dimPercent 等比缩放，
     * 默认 42% 时与旧版逐位一致。
     */
    private const val STRIP_CONTEXT_ALPHA = 150

    /** 单条模式压暗基准对应的 dim 百分比（与 [OverlayLegibility.DEFAULT_DIM_PERCENT] 一致） */
    private const val STRIP_CONTEXT_BASE_DIM = 42

    /** 给颜色换一个透明度通道（保留用户自选的色相） */
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /**
     * 组装可绘制文本。
     *
     * @param baseColor    用户选的正文色（全屏模式下配角/译文跟着它压暗）
     * @param noBackdrop   是否无底板；无底板时上下文/译文不能压太暗（见 [OverlayLegibility]）
     * @param currentColor 当前行单独高亮色（v1.1.52）；0 = 跟随 [baseColor]（TextView 文字色）
     * @param dimPercent   上下句压暗百分比（v1.1.52，20–80）；默认 42 与播放页对齐
     * @param textSp 正文字号（sp）—— 分角色字号的换算基准
     * @param currentSp 当前行字号（sp，v1.2.54）；**0 = 跟随正文**
     * @param contextSp 其他行字号（sp，v1.2.54）；**0 = 跟随正文**
     * @param contextColor 其他行颜色（v1.2.54）；0 = 跟随 [baseColor]，仍按压暗算不透明度
     */
    fun build(
        lines: List<OverlayTextComposer.StyledLine>,
        fullHeight: Boolean,
        baseColor: Int,
        noBackdrop: Boolean,
        currentColor: Int = 0,
        dimPercent: Int = OverlayLegibility.DEFAULT_DIM_PERCENT,
        textSp: Int = SettingsDefaults.OVERLAY_TEXT_SP,
        currentSp: Int = SettingsDefaults.OVERLAY_CURRENT_SP,
        contextSp: Int = SettingsDefaults.OVERLAY_CONTEXT_SP,
        contextColor: Int = 0,
    ): CharSequence {
        if (lines.isEmpty()) return ""
        // v1.2.54：分角色字号是**绝对磅值**（0 = 跟随正文），渲染成 RelativeSizeSpan
        // 需要的是「相对正文的倍率」，所以在这里换算。全屏形态的当前行基线（+13%）叠乘
        // 在最外层 —— 它是形态自带的排版比例，不是用户设置的一部分。
        val baseSp = textSp.coerceAtLeast(SettingsDefaults.OVERLAY_FONT_MIN_SP)
        val currentSpEff = if (currentSp > 0) currentSp else baseSp
        val contextSpEff = if (contextSp > 0) contextSp else baseSp
        /** 当前行是否真的与正文不同号（决定要不要加 span / 能不能走快路径） */
        val currentResized = currentSpEff != baseSp
        val currentScale = currentSpEff.toFloat() / baseSp * (if (fullHeight) FULL_CURRENT_SCALE else 1f)
        val contextScale = contextSpEff.toFloat() / baseSp
        val only = lines.singleOrNull()
        // 单行 && 主角 && 不是全屏模式 && 没有单独设当前行色 / 字号时走纯字符串快路径：
        // 主角**不需要任何 span**（颜色由 TextView 的文字色决定），1 行模式正是
        // 最常见的字幕形态。用户设了「当前行颜色 / 字号」后主角要单独染 / 缩放，快路径作废。
        if (!fullHeight && only != null && only.role == OverlayTextComposer.Role.CURRENT) {
            if (currentColor == 0 && !currentResized) return only.text
            return SpannableStringBuilder().apply {
                append(only.text)
                if (currentColor != 0) {
                    setSpan(
                        ForegroundColorSpan(currentColor),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (currentResized) {
                    setSpan(
                        RelativeSizeSpan(currentScale),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        // 无底板时不能把上下句压到 42%：浅底（泊车影像 / 地图）上那就是一片雾。
        val contextAlpha = OverlayLegibility.contextAlpha(noBackdrop, dimPercent)
        // 单条模式：配角 / 译文与正文**同色相**，只压暗不换色（v1.1.79 修复）。
        // 原先这里写死白色：用户把「悬浮歌词文字颜色」调成橙 / 黄后，上下句仍是白的，
        // 只有顶部歌名（跟随文字色）变了色，看起来就像「颜色染到歌名上去了」。
        // alpha 仍以旧版 150 为基准按压暗百分比等比缩放，默认文字色（白）下视觉不变。
        // v1.2.54：色相可由「其他行颜色」单独指定（0 = 仍跟随正文色），alpha 规则不变。
        val stripContextColor = withAlpha(
            if (contextColor != 0) contextColor else baseColor,
            (STRIP_CONTEXT_ALPHA * dimPercent.coerceIn(0, 100) /
                OverlayLegibility.DEFAULT_DIM_PERCENT.toFloat()).roundToInt()
                .coerceIn(30, 255),
        )
        val fullContextColor = withAlpha(if (contextColor != 0) contextColor else baseColor, contextAlpha)
        return SpannableStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("\n")
                val start = length
                append(line.text)
                val end = length
                when (line.role) {
                    OverlayTextComposer.Role.CURRENT -> {
                        if (fullHeight) {
                            // 播放页的当前行：加粗 + 满不透明（字号走下面的统一缩放）
                            setSpan(
                                StyleSpan(Typeface.BOLD),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        if (currentScale != 1f) {
                            setSpan(
                                RelativeSizeSpan(currentScale),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        // v1.1.52：单独的当前行颜色；0 = 跟随 TextView 文字色，不加 span
                        if (currentColor != 0) {
                            setSpan(
                                ForegroundColorSpan(currentColor),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                    OverlayTextComposer.Role.CONTEXT -> {
                        setSpan(
                            ForegroundColorSpan(if (fullHeight) fullContextColor else stripContextColor),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        if (contextScale != 1f) {
                            setSpan(
                                RelativeSizeSpan(contextScale),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                        if (fullHeight) {
                            setSpan(
                                StyleSpan(Typeface.NORMAL),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                    OverlayTextComposer.Role.TRANSLATION -> {
                        setSpan(
                            ForegroundColorSpan(if (fullHeight) fullContextColor else stripContextColor),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        // 译文跟着当前行走：当前行放大了，译文同比放大才不脱节
                        setSpan(
                            RelativeSizeSpan(
                                (if (fullHeight) FULL_TRANSLATION_SCALE else TRANSLATION_SCALE) *
                                    currentScale,
                            ),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        if (fullHeight) {
                            setSpan(
                                StyleSpan(Typeface.NORMAL),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }
            }
        }
    }
}
