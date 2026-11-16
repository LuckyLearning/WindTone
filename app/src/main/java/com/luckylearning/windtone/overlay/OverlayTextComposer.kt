package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.lyrics.OverlayContentResolver

/**
 * 把 [OverlayContentResolver.Resolved] 组装成「屏幕上要画的第 1..n 行」。
 *
 * 纯函数，**不碰任何 Android 类型**：颜色、字号、描边这些依赖 `resources` 的东西
 * 全部留在 [LyricOverlayService] 里按 [Role] 决定。
 * 这样「显示哪几行、谁是主角」是**可测**的，
 * 而「多少号字、什么颜色」是**可调**的，两件事互不干扰。
 */
object OverlayTextComposer {

    /**
     * 「全屏高度」模式的**参考行数**（v1.1.19 引入；v1.1.50 起仅供测试与文档引用）。
     *
     * 上 5 + 当前 + 下 5 是「一屏铺满、当前行居中」的经典排布。行数改「按高度
     * 自适应」后，实际画几行由 [OverlayLineFit] 反推、上限放宽到
     * [OverlayLineFit.MAX_AUTO_LINES]，这个 11 只是历史档位，不再是上限。
     */
    const val FULL_HEIGHT_LINES = 11

    /** 一行的视觉角色（渲染侧据此取色 / 字号） */
    enum class Role {
        /** 主角：正在演唱的那一行，最亮、最粗 */
        CURRENT,

        /** 配角：上下句，或「没有在唱」时的主角（间奏 / 前奏），压暗 */
        CONTEXT,

        /** 双语副行：附在主角下方，字号更小、同样压暗 */
        TRANSLATION,
    }

    /**
     * @param text 该行文字
     * @param role 视觉角色
     * @param words 字级时间轴（逐字歌词）：**只有当前行且未折行**时才非空；
     *              其余行一律 null，渲染侧只对主角做分段染色。
     * @param lineStartMs 当前行起点（毫秒），与 words 配套
     */
    data class StyledLine(
        val text: String,
        val role: Role,
        val words: List<com.luckylearning.windtone.lyrics.LyricWord>? = null,
        val lineStartMs: Long = -1L,
    )

    /**
     * 组装显示内容。
     *
     * ⚠️ **不再有字级信息**（v1.1.8 起）：主角与配角的区分**只靠 [Role]**
     * （渲染侧把主角画得最亮最粗、其余压暗），不依赖「唱到第几个字」。
     * 逐字染色所需的位置参数已随之移除 —— 别再往这里加回 `positionMs`，
     * 那会让整个悬浮窗重新变成每 200ms 重组一次。
     *
     * @param lineCount 歌词行数：1 = 只显示当前行；3 = 上 1 + 当前 + 下 1；5 = 上 2 + 当前 + 下 2。
     *                  偶数行时下方多一行（2 = 当前 + 下 1），保证当前行尽量靠中。
     *                  **双语副行不占这个额度**，它永远紧跟当前行。
     * @param showTranslation 是否显示双语副行
     */
    fun compose(
        resolved: OverlayContentResolver.Resolved,
        lineCount: Int,
        showTranslation: Boolean,
    ): List<StyledLine> {
        if (!resolved.visible) return emptyList()

        val total = lineCount.coerceAtLeast(1)
        val before = (total - 1) / 2
        val after = total - 1 - before

        val main = StyledLine(
            text = resolved.current,
            // 间奏 / 前奏时主角本身也是「不在唱的」，一并压暗，视线不会被它抢走
            role = if (resolved.idle) Role.CONTEXT else Role.CURRENT,
            // 逐字轴只挂在「正在演唱的唯一主角」上；一行模式才逐字，
            // 多行模式下主角可能折行，跨行算字偏移代价与收益不成比例。
            words = resolved.currentWords?.takeIf { !resolved.idle && lineCount == 1 },
            lineStartMs = resolved.lineStartMs,
        )

        return buildList {
            // prevLines 已是显示顺序（远端 → 最近），只取离当前行最近的 before 条
            resolved.prevLines.takeLast(before).forEach {
                add(StyledLine(it, Role.CONTEXT))
            }
            add(main)
            // 副行紧跟在主角下面 —— 它是这一句的一部分，不是「另一行歌词」
            if (showTranslation && resolved.translation.isNotBlank()) {
                add(StyledLine(resolved.translation, Role.TRANSLATION))
            }
            resolved.nextLines.take(after).forEach {
                add(StyledLine(it, Role.CONTEXT))
            }
        }
    }
}
