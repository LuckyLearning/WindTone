package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.lyrics.OverlayContentResolver

/**
 * 把 [OverlayContentResolver.Resolved] 组装成「屏幕上要画的第 1..n 行」。
 *
 * 纯函数，**不碰任何 Android 类型**：颜色、字号、描边这些依赖 `resources` 的东西
 * 全部留在 [LyricOverlayService] 里按 [Role] 决定。
 * 这样「显示哪几行、谁是主角、逐字唱到第几个字」是**可测**的，
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
     * @param sungChars 逐字高亮：本行**已唱到的字符数**；`-1` 表示本行不做逐字染色
     */
    data class StyledLine(
        val text: String,
        val role: Role,
        val sungChars: Int = -1,
    )

    /**
     * 组装显示内容。
     *
     * @param multiLine 3 行模式（带上下文行）
     * @param positionMs 播放位置，逐字染色用
     * @param wordByWord 是否启用逐字染色
     * @param showTranslation 是否显示双语副行
     */
    fun compose(
        resolved: OverlayContentResolver.Resolved,
        multiLine: Boolean,
        positionMs: Long,
        wordByWord: Boolean,
        showTranslation: Boolean,
    ): List<StyledLine> {
        if (!resolved.visible) return emptyList()

        val main = StyledLine(
            text = resolved.current,
            // 间奏 / 前奏时主角本身也是「不在唱的」，一并压暗，视线不会被它抢走
            role = if (resolved.idle) Role.CONTEXT else Role.CURRENT,
            sungChars = if (wordByWord && !resolved.idle) {
                sungChars(resolved, positionMs)
            } else {
                -1
            },
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

    /**
     * 算「这一行唱到第几个字了」。
     *
     * 逐字时间轴来自 KRC / YRC / 增强型 LRC；把它摊平就是「已开始的词的文字长度之和」。
     * 两个必须夹住的地方：
     *  - 词的文字可能被 `trim` 过（解析时去过空白），拼起来的长度**未必等于**整行长度，
     *    所以结果一律 `coerceIn(0, current.length)`，宁可少染几个字也不能越界；
     *  - `words` 为空（逐行精度）时返回 -1，表示不做逐字染色。
     */
    fun sungChars(resolved: OverlayContentResolver.Resolved, positionMs: Long): Int {
        if (resolved.words.isEmpty()) return -1
        var count = 0
        for (w in resolved.words) {
            if (w.startMs > positionMs) break
            count += w.text.length
        }
        return count.coerceIn(0, resolved.current.length)
    }
}
