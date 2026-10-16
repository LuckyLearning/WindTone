package com.luckylearning.windtone.lyrics

/**
 * 逐字时间轴派生（v1.1.5）。
 *
 * **背景（「逐字歌词没效果」的真正根因）**：逐字染色的数据源只有三种真货 ——
 * `.krc` / `.yrc` / 增强型 LRC（`<mm:ss.xx>`），三者的解析与渲染链路本身没问题。
 * 但本地音乐库里**绝大多数歌词是普通 LRC**（只有行时间轴），于是
 * `LyricLine.hasWords == false` → 渲染层退回整行变色 → 用户眼里就是
 * 「逐字功能完全没有效果」。这不是 bug，是**数据不存在**。
 *
 * 这里的处理：给「没有逐字数据的同步行」补一条**派生**时间轴 —— 把该行的演唱窗口
 * 按字符权重摊到每个字/词上。视觉上与真逐字一致；**真数据永远优先**，
 * 已经带 `words` 的行原样保留。
 *
 * 两处消费方共用同一份结论（改这里必须同时想两边）：
 *  - 播放页 `KaraokeText`：按 `lineDurationMs` 归一化 + 词区间染色
 *  - 悬浮窗 `OverlayTextComposer.sungChars`：按词计数累加
 */
object WordTimingSynth {

    /** 每个「权重单位」折算的演唱时长（ms）。中文 1 字 = 1 权重 → 约 2.4 字/秒。 */
    private const val MS_PER_WEIGHT = 420.0

    /** 派生行的时长上限：长句 + 长间奏时不至于把扫过拉得慢到看不出动 */
    private const val MAX_LINE_MS = 12_000L

    /**
     * 「间隔还算合理」的倍数阈值：间隔 ≤ 估算时长 × 此值时，认为这一行本来就唱满整个间隔
     * （普通歌词的行距 = 句长 + 一两百毫秒的停顿），直接按间隔走，视觉上不早停。
     */
    private const val GAP_FILL_RATIO = 2.5

    /** 派生行的时长下限（下一行来得很快时也不做 0ms 的闪现） */
    private const val MIN_LINE_MS = 400L

    private const val W_CJK = 1.0
    private const val W_LATIN = 0.55
    private const val W_SPACE = 0.12
    private const val W_PUNCT = 0.25

    /**
     * 给整份歌词补齐逐字时间轴。
     *
     * 幂等：已有 `words` 的行不会再派生一次（所以重复调用安全）。
     * 纯文本歌词（`synced == false`）不做任何事 —— 没有行时间就无从摊派。
     */
    fun Lyrics.withDerivedWordTiming(): Lyrics {
        if (!synced || lines.isEmpty()) return this
        var changed = false
        val out = ArrayList<LyricLine>(lines.size)
        for (i in lines.indices) {
            val line = lines[i]
            val filled = fillLine(line, lines.getOrNull(i + 1)?.timeMs)
            if (filled !== line) changed = true
            out.add(filled)
        }
        return if (changed) copy(lines = out) else this
    }

    /** @param nextStartMs 下一行起点；null = 最后一行 */
    private fun fillLine(line: LyricLine, nextStartMs: Long?): LyricLine {
        if (line.timeMs < 0) return line // 无时间轴的行

        if (line.words.isNotEmpty()) {
            // 真逐字数据：只兜一种脏数据 —— 行时长缺失、或比逐字末端还短
            // （YRC 里 `[start,0]`、或解析把行时长写成 0），此时归一化分母会失真，
            // 整行要么瞬间全亮、要么永远染不满。
            val derived = (lastWordEnd(line.words) - line.timeMs).coerceAtLeast(1L)
            return if (line.lineDurationMs >= derived) line else line.copy(lineDurationMs = derived)
        }

        val text = line.text
        if (text.isBlank()) return line

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return line
        val weights = tokens.map { weight(it) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return line

        // 演唱窗口 = 与「到下一行的间隔」对账后的结果：
        //  - 间隔 ≤ 估算 × 2.5 → 用间隔：一句扫完正好接上下一句，不会唱完了还干等；
        //  - 间隔远大于估算（后面是间奏）→ 用估算：否则末几字被拖到十几秒才亮。
        val gap = nextStartMs?.let { (it - line.timeMs).coerceAtLeast(1L) } ?: MAX_LINE_MS
        val estimate = (totalWeight * MS_PER_WEIGHT).toLong().coerceAtLeast(1L)
        val window = if (gap <= (estimate * GAP_FILL_RATIO).toLong()) gap else estimate
        var budget = minOf(window, MAX_LINE_MS)
        if (budget < MIN_LINE_MS && gap >= MIN_LINE_MS) budget = MIN_LINE_MS

        val words = ArrayList<LyricWord>(tokens.size)
        var cursor = 0.0
        for (k in tokens.indices) {
            val startMs = line.timeMs + (budget * cursor / totalWeight).toLong()
            cursor += weights[k]
            val endMs = line.timeMs + (budget * cursor / totalWeight).toLong()
            words.add(LyricWord(startMs, (endMs - startMs).coerceAtLeast(1L), tokens[k]))
        }
        // 末字唱完时刻必须与 lineDurationMs 严格对齐：渲染层的归一化分母就是它，
        // 差一点末字就染不满（这是 a6c0711 在同一处踩过的坑）
        return line.copy(words = words, lineDurationMs = budget)
    }

    private fun lastWordEnd(words: List<LyricWord>): Long =
        words.maxOf { it.startMs + it.durationMs.coerceAtLeast(1L) }

    /**
     * 切成「一个字」或「一段西文/符号」：
     * 中日韩文字逐字成 token（逐字染色的目标场景），连续的西文 / 空白 / 标点合成一段，
     * 段内由渲染层按字符细分。token 顺序拼接**必须还原原文**。
     */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        val sb = StringBuilder()
        for (ch in text) {
            if (isCjk(ch)) {
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                out.add(ch.toString())
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun weight(token: String): Double {
        var sum = 0.0
        for (ch in token) {
            sum += when {
                isCjk(ch) -> W_CJK
                ch.isLetterOrDigit() -> W_LATIN
                ch.isWhitespace() -> W_SPACE
                else -> W_PUNCT
            }
        }
        return sum
    }

    /** 中日韩文字（含假名、韩文音节、以及扩展区）—— 这些语言习惯逐字计时 */
    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x3040..0x30FF || // 平假名 / 片假名
            c in 0x3400..0x4DBF || // CJK 扩展 A
            c in 0x4E00..0x9FFF || // CJK 基本区
            c in 0xF900..0xFAFF || // CJK 兼容
            c in 0xAC00..0xD7AF || // 韩文音节
            c in 0xFF66..0xFF9D // 半角片假名
    }
}
