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
     * @param multiLine 3 行模式（带上下文行）
     * @param showTranslation 是否显示双语副行
     */
    fun compose(
        resolved: OverlayContentResolver.Resolved,
        multiLine: Boolean,
        showTranslation: Boolean,
    ): List<StyledLine> {
        if (!resolved.visible) return emptyList()

        val main = StyledLine(
            text = resolved.current,
            // 间奏 / 前奏时主角本身也是「不在唱的」，一并压暗，视线不会被它抢走
            role = if (resolved.idle) Role.CONTEXT else Role.CURRENT,
        )

        return buildList {
            if (multiLine && resolved.prev.isNotBlank()) {
                add(StyledLine(resolved.prev, Role.CONTEXT))
            }
            add(main)
            // 副行紧跟在主角下面 —— 它是这一句的一部分，不是「另一行歌词」
            if (showTranslation && resolved.translation.isNotBlank()) {
                add(StyledLine(resolved.translation, Role.TRANSLATION))
            }
            if (multiLine && resolved.next.isNotBlank()) {
                add(StyledLine(resolved.next, Role.CONTEXT))
            }
        }
    }
}
