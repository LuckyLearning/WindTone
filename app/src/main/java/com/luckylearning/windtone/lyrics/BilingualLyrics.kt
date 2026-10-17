package com.luckylearning.windtone.lyrics

/**
 * 双语歌词的显示模式（v1.1.18）。
 *
 * 起因：带翻译的 LRC 会同时给出原文与译文（同一时间戳的两行，解析时合成
 * `LyricLine.text` + `LyricLine.translation`）。两侧都显示固然信息最全，但
 * 车机上字幕块本来就窄，两行挤在一起反而更难扫读；学外语的人也可能只想留一种。
 *
 * 三种模式的差别只在**显示**：歌词文件、导出、解析结果都不改。
 */
enum class BilingualMode(val index: Int, val label: String, val shortLabel: String) {
    /** 默认：原文一行 + 译文一行，与历史行为完全一致 */
    BOTH(0, "中英都显示", "都显示"),

    /** 只留中文：原文是外语时改显示中文译文；本来就是中文则只留原文 */
    CHINESE_ONLY(1, "只显示中文", "只中文"),

    /** 只留英文：原文是中文时改显示英文译文；本来就是英文则只留原文 */
    ENGLISH_ONLY(2, "只显示英文", "只英文"),
    ;

    companion object {
        /** 从持久化的下标还原；非法值（旧数据 / 手改）回落到 [BOTH] */
        fun of(index: Int): BilingualMode = entries.firstOrNull { it.index == index } ?: BOTH
    }
}

/**
 * 一行歌词「是什么语言」。
 *
 * 为什么需要五种而不是「中文 / 非中文」：日文假名与韩文谚文都**不是**中文，
 * 却也不是英文；把它们算进任何一边都会在「日文歌 + 中文翻译」这类最常见的
 * 双语场景里选错行。混合行（`Yeah 我是`）两种模式都接受，因为它是用户自己
 * 的语言混写，换掉反而丢信息。
 */
internal enum class LineScript {
    /** 只有汉字：中文（或纯汉字日文标题，极少见，按中文处理） */
    HAN,

    /** 只有拉丁字母：英文（含拼音 / 罗马音，这正是「只显示英文」的常见用法） */
    LATIN,

    /** 两者都有：中文 + 英文混写 */
    MIXED,

    /** 有假名 / 谚文：日文或韩文，既不算中文也不算英文 */
    OTHER,

    /** 一个字母都没有：`♪`、`...`、纯数字，这类行不该被当语言判断 */
    BLANK,
}

/**
 * 双语歌词的显示裁剪（纯函数，可单测）。
 *
 * 单一入口 [apply]，被 `PlaybackEngine.loadLyrics` 调用一次：过滤后的结果放进
 * `PlayerUiState.lyrics`，于是播放页歌词、桌面悬浮歌词、迷你条读到的都是同一份
 * ——**绝不能**在各个渲染处各过滤一遍，那种写法迟早会出现「悬浮窗按设置过滤了、
 * 播放页没有」的错位。
 *
 * 判定顺序（每一行）：
 *  1. 没有副行 → 原样返回（单语歌词，没有可选的另一行）；
 *  2. 主行已经是目标语言 → 只去掉副行（把外语译文藏起来）；
 *  3. 副行是目标语言 → **把副行提到主行**，副行清空；
 *  4. 两边都不是目标语言（日文主行 + 罗马音副行，用户却选了「只显示中文」）
 *     → 保留主行、去掉副行。宁可显示"不是想要的语言的原文"，
 *     也不能因为找不到中文就整行消失。
 */
object BilingualLyrics {

    fun apply(lyrics: Lyrics, mode: BilingualMode): Lyrics {
        if (mode == BilingualMode.BOTH || lyrics.isEmpty) return lyrics
        val want = when (mode) {
            BilingualMode.BOTH -> return lyrics
            BilingualMode.CHINESE_ONLY -> LineScript.HAN
            BilingualMode.ENGLISH_ONLY -> LineScript.LATIN
        }
        return lyrics.copy(lines = lyrics.lines.map { pick(it, want) })
    }

    /** 只按一行的两种文字决定这一行最终显示什么 */
    private fun pick(line: LyricLine, want: LineScript): LyricLine {
        val other = line.translation?.takeIf { it.isNotBlank() } ?: return line
        // 空行是间奏标记，必须原样留下去 —— 上一版「双语合并」也正是靠它判断空档
        if (line.text.isBlank()) return line

        return when {
            // 主行已经是目标语言（含中英混写）：留下主行，藏起译文
            matches(scriptOf(line.text), want) -> line.copy(translation = null)

            // 主行不是、译文是：把译文提上来当唯一一行
            matches(scriptOf(other), want) -> LyricLine(line.timeMs, other)

            // 都不是：主语种不在选项里，只能留主行
            else -> line.copy(translation = null)
        }
    }

    private fun matches(script: LineScript, want: LineScript): Boolean =
        script == want || script == LineScript.MIXED

    internal fun scriptOf(text: String): LineScript {
        var han = 0
        var latin = 0
        var foreign = 0
        for (c in text) {
            when {
                isHan(c) -> han++
                isLatin(c) -> latin++
                isKana(c) || isHangul(c) -> foreign++
            }
        }
        return when {
            foreign > 0 -> LineScript.OTHER
            han > 0 && latin > 0 -> LineScript.MIXED
            han > 0 -> LineScript.HAN
            latin > 0 -> LineScript.LATIN
            else -> LineScript.BLANK
        }
    }

    private fun isHan(c: Char): Boolean {
        val v = c.code
        return v in 0x4E00..0x9FFF || v in 0x3400..0x4DBF || v in 0xF900..0xFAFF
    }

    private fun isKana(c: Char): Boolean {
        val v = c.code
        return v in 0x3040..0x30FF || v in 0x31F0..0x31FF
    }

    private fun isHangul(c: Char): Boolean {
        val v = c.code
        return v in 0xAC00..0xD7A3 || v in 0x1100..0x11FF
    }

    private fun isLatin(c: Char): Boolean {
        val v = c.code
        return c in 'a'..'z' || c in 'A'..'Z' ||
            v in 0x00C0..0x024F || // 带变音符号的拉丁字母（café / naïve）
            v in 0xFF21..0xFF3A || v in 0xFF41..0xFF5A // 全角字母
    }
}
