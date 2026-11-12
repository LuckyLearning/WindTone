package com.anylyric.overlay

/**
 * 歌词行的排版预算。
 *
 * 存在的理由是一条真机反馈：「副行内容这里有问题，导致只能显示 2 行歌词」。
 * 旧实现里副行是**一条** String，于是无论把行数调到 3 还是 4，屏幕上永远只有
 * 「当前行 + 下一行」两行 —— 预算给多了，却没有文本可填。
 *
 * 现在把「谁占第几行」这件事从绘制代码里提出来：当前行先按预算折行，剩下的预算
 * 逐条铺上下文行，每条上下文行能折几行也受剩余预算限制。纯函数，配一个注入的
 * 折行函数就能在 JVM 上把边界全测掉，不必等车机。
 */
object LinePlan {

    /** 一行最终要画的内容。[secondary] 表示它是上下文行（颜色/字号不同）。 */
    data class Row(val text: String, val secondary: Boolean)

    /**
     * @param primary 当前行原文
     * @param context 上下文行原文（按显示顺序，可空）
     * @param maxLines 总行数预算，含当前行折出来的行
     * @param wrap 折行函数：`(原文, 最多几行, 是否上下文行) → 折好的各行`
     */
    fun build(
        primary: String,
        context: List<String>,
        maxLines: Int,
        wrap: (text: String, lines: Int, secondary: Boolean) -> List<String>
    ): List<Row> {
        val budget = maxLines.coerceAtLeast(1)
        val rows = ArrayList<Row>(budget)

        // 当前行优先：长句折成三行是合理的，不该被上下文挤成一行加省略号
        for (line in wrap(primary, budget, false)) {
            if (rows.size >= budget) break
            rows += Row(line, false)
        }

        for (text in context) {
            if (rows.size >= budget) break
            // 空行（LRC 里常见）不占预算，也不该在界面上留一道空隙
            if (text.isBlank()) continue
            for (line in wrap(text, budget - rows.size, true)) {
                if (rows.size >= budget) break
                rows += Row(line, true)
            }
        }
        return rows
    }
}
