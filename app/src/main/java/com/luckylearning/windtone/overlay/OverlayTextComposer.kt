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
     * 「全屏高度」模式的**行数上限**（v1.1.19，v1.1.49 起变成上限而非固定值）。
     *
     * 上 5 + 当前 + 下 5：一屏铺满、当前行居中、上下都是可读的上下文。
     * 奇数行保证当前行真正落在正中；实际画几行由 [OverlayLineFit] 按面板高度反推，
     * 面板被调矮（30%~）时自动减少，不会被窗口裁掉最后几行。
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
     */
    data class StyledLine(
        val text: String,
        val role: Role,
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
