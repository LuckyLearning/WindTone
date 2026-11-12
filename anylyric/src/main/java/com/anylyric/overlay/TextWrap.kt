package com.anylyric.overlay

/**
 * 中英混排的折行与省略。**纯逻辑**：宽度由外部 `measure` 提供，因此不依赖 Android，
 * 可以用假测量器跑 JVM 单测。
 *
 * 不用 `StaticLayout`：它每行都要建 Spannable 与 Layout 对象，而悬浮层是事件驱动、
 * 每 3~5 秒换一句，缓存自己的折行结果比反复建对象划算得多。
 */
object TextWrap {

    private const val ELLIPSIS = "…"

    /** 断点回看的最大距离：超过这个距离就不再往前找空格/标点，直接硬断。 */
    private const val BREAK_LOOKBACK = 8

    private val BREAKABLE = charArrayOf(
        ' ', '\t', '-', '/', '，', '。', '、', '；', '：', '！', '？',
        ',', '.', '）', ')', '」', '”', '·'
    )

    /**
     * 按最大宽度折行，最多 [maxLines] 行；最后一行放不下时省略号收尾。
     *
     * 返回值保证非空（输入非空时），避免调用方再判一次空列表。
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        measure: (String) -> Float
    ): List<String> {
        val source = text.trim()
        if (source.isEmpty() || maxWidth <= 0f || maxLines <= 0) return emptyList()
        if (measure(source) <= maxWidth) return listOf(source)

        val out = ArrayList<String>(maxLines)
        var start = 0
        val n = source.length

        while (start < n && out.size < maxLines) {
            // 最后一行：剩下的一口气塞进去，塞不下就省略 —— 不再递归换行。
            if (out.size == maxLines - 1) {
                out.add(ellipsize(source.substring(start).trim(), maxWidth, measure))
                start = n
                break
            }

            var end = start
            var lastBreak = -1
            while (end < n) {
                if (measure(source.substring(start, end + 1)) > maxWidth) break
                if (BREAKABLE.contains(source[end])) lastBreak = end
                end++
            }

            if (end >= n) {
                out.add(source.substring(start).trim())
                start = n
                break
            }
            // 单个字符就超宽（超大字号 + 极窄屏）：硬吞一个字符，否则会原地死循环。
            if (end == start) end = start + 1

            val cut = if (lastBreak > start && end - lastBreak <= BREAK_LOOKBACK) {
                lastBreak + 1
            } else {
                end
            }
            out.add(source.substring(start, cut).trim())
            start = cut
        }

        return out.filter { it.isNotEmpty() }.ifEmpty { listOf(ELLIPSIS) }
    }

    /** 单行省略：从尾部逐字退，直到「文本 + …」能放下。 */
    fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (text.isEmpty()) return text
        if (measure(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && measure(text.substring(0, end) + ELLIPSIS) > maxWidth) {
            end--
        }
        return if (end <= 0) ELLIPSIS else text.substring(0, end) + ELLIPSIS
    }
}
