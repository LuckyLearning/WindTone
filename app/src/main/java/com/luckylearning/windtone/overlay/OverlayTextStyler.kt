package com.luckylearning.windtone.overlay

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
     */
    fun build(
        lines: List<OverlayTextComposer.StyledLine>,
        fullHeight: Boolean,
        baseColor: Int,
        noBackdrop: Boolean,
        currentColor: Int = 0,
        dimPercent: Int = OverlayLegibility.DEFAULT_DIM_PERCENT,
    ): CharSequence {
        if (lines.isEmpty()) return ""
        val only = lines.singleOrNull()
        // 单行 && 主角 && 不是全屏模式 && 没有单独设当前行色时走纯字符串快路径：
        // 主角**不需要任何 span**（颜色由 TextView 的文字色决定），1 行模式正是
        // 最常见的字幕形态。用户设了「当前行颜色」后主角要单独染色，快路径作废。
        if (!fullHeight && only != null && only.role == OverlayTextComposer.Role.CURRENT) {
            if (currentColor == 0) return only.text
            return SpannableStringBuilder().apply {
                append(only.text)
                setSpan(
                    ForegroundColorSpan(currentColor),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        // 无底板时不能把上下句压到 42%：浅底（泊车影像 / 地图）上那就是一片雾。
        val contextAlpha = OverlayLegibility.contextAlpha(noBackdrop, dimPercent)
        val translationAlpha = OverlayLegibility.translationAlpha(noBackdrop, dimPercent)
        // 单条模式：配角是固定白，以旧版 150 为基准按压暗百分比等比缩放
        val stripContextColor = withAlpha(
            0x00FFFFFF,
            (STRIP_CONTEXT_ALPHA * dimPercent.coerceIn(0, 100) /
                OverlayLegibility.DEFAULT_DIM_PERCENT.toFloat()).roundToInt()
                .coerceIn(30, 255),
        )
        return SpannableStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("\n")
                val start = length
                append(line.text)
                val end = length
                when (line.role) {
                    OverlayTextComposer.Role.CURRENT -> {
                        if (fullHeight) {
                            // 播放页的当前行：字号放大一档 + 加粗 + 满不透明
                            setSpan(
                                RelativeSizeSpan(FULL_CURRENT_SCALE),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            setSpan(
                                StyleSpan(Typeface.BOLD),
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
                            ForegroundColorSpan(
                                if (fullHeight) {
                                    withAlpha(baseColor, contextAlpha)
                                } else {
                                    stripContextColor
                                },
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
                    OverlayTextComposer.Role.TRANSLATION -> {
                        setSpan(
                            ForegroundColorSpan(
                                if (fullHeight) {
                                    withAlpha(baseColor, translationAlpha)
                                } else {
                                    stripContextColor
                                },
                            ),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        setSpan(
                            RelativeSizeSpan(
                                if (fullHeight) FULL_TRANSLATION_SCALE else TRANSLATION_SCALE,
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
