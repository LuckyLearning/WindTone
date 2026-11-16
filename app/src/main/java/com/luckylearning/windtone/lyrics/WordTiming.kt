package com.luckylearning.windtone.lyrics

/**
 * 字级时间的补算与推进（逐字歌词）。
 *
 * 参照 anylyric 0.2.28：在线源 / 本地 LRC 里带 `<mm:ss.xx>` 的只是少数，但用户一旦
 * 打开逐字，**每一句**都该有效果。按行时长与字数均分虽然不是真实演唱时间，视觉上
 * 同样是「一个字一个字亮起来」，比「有的句子逐字、有的句子整句跳」自然得多。
 *
 * 纯函数、不碰 Android 类型：可直接在 JVM 单测里断言。
 */
object WordTiming {

    /** 一行最短 / 最长按字均分的时长：太短闪成噪点，太长最后几个字迟迟不亮。 */
    private const val MIN_SPAN_MS = 400L
    private const val MAX_SPAN_MS = 8_000L
    /** 末行（没有下一行）按 3 秒估。 */
    private const val LAST_LINE_SPAN_MS = 3_000L

    /**
     * 给没有字级轴的歌词补一份「伪字级」。
     *
     * @param enabled 逐字开关；关闭时原样返回（不白算、不产生对象）
     * @return 需要补的行都补上后返回新列表；无需改动时返回**同一实例**（避免 tick 里反复分配）
     */
    fun withDerivedWords(lines: List<LyricLine>, enabled: Boolean): List<LyricLine> {
        if (!enabled || lines.isEmpty()) return lines
        var changed = false
        val out = ArrayList<LyricLine>(lines.size)
        for ((index, line) in lines.withIndex()) {
            val start = line.timeMs
            if (line.hasWords || start < 0L) {
                out += line
                continue
            }
            val next = lines.getOrNull(index + 1)?.timeMs?.takeIf { it > start }
            val span = ((next ?: (start + LAST_LINE_SPAN_MS)) - start).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
            val chars = line.text.length.coerceAtLeast(1)
            val words = ArrayList<LyricWord>(chars)
            for (i in 0 until chars) {
                words += LyricWord(line.text[i].toString(), start + span * i / chars)
            }
            out += line.copy(words = words)
            changed = true
        }
        return if (changed) out else lines
    }

    /**
     * 当前进度下，这一行有几个字已经唱到（0..text.length）。
     *
     * 只切两刀（已唱 / 未唱）而不是逐字 drawText：比例字体下逐字累加宽度有误差，
     * 两段画的接缝用整段 measureText 定位，精确落在字边界。
     *
     * @param words 字级时间轴（已按时间升序）
     * @param progressMs 当前播放进度（毫秒）
     * @param max 字数上限（= 正文纯文本长度，防止 words 与 text 不一致时越界）
     */
    fun sungChars(words: List<LyricWord>, progressMs: Long, max: Int): Int {
        if (progressMs < 0L || max <= 0 || words.isEmpty()) return 0
        var n = 0
        for (word in words) {
            if (word.timeMs <= progressMs) n += word.text.length else break
        }
        return n.coerceIn(0, max)
    }
}
