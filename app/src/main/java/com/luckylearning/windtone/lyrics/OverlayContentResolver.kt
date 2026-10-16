package com.luckylearning.windtone.lyrics

/**
 * 悬浮歌词「这一刻该显示什么」的纯函数解析器。
 *
 * 为什么单独抽出来：
 *  1. **可测**：这是整个悬浮歌词里最绕的一段决策（前奏 / 间奏空行 / 上下文回溯 / 回落歌名），
 *     原先埋在 `PlaybackEngine.publishLyricToOverlay` 里——那段代码没法写一行测试，
 *     而它恰恰是「字幕会莫名其妙消失」这类缺陷的高发区；
 *  2. **两侧共用**：引擎与悬浮窗服务必须对同一个播放位置算出**完全一致**的结果，
 *     自检报告也要引用同一份结论；
 *  3. 它不依赖任何 Android 类型（只吃 [Lyrics] 数据类），因此能在 JVM 单测里直接跑。
 *
 * ## 三个容易踩空的情形（全部在这里被兜住）
 *
 * - **前奏**：`indexAt` 返回 -1（还没到第一句）。原先取到 `null.orEmpty()` = 空串，
 *   `Content.visible` 判空不通过 → 整个悬浮窗隐藏，用户以为功能坏了。
 *   现在回落显示**歌名**，并把第一句作为「下一句」预告（3 行模式下看得见）。
 * - **间奏空行**：LRC 里 `[01:20.00]` 单独一行是「间奏标记」的常见写法，
 *   解析后会生成 `LyricLine(text = "")`。原先同样导致整窗消失、下一句才回来（闪一下）。
 *   现在**回溯到最近的非空行**并标记 `idle`，窗口位置不动、内容压暗。
 * - **双语歌词**：`translation` 此前完全没有进入悬浮窗，现在随当前行一起带出。
 */
object OverlayContentResolver {

    /**
     * 一次解析的结果。
     *
     * @param current 主角位置的文字（歌词行，或前奏 / 无歌词时回落成歌名）
     * @param translation 当前行的双语副行（无则空串）
     * @param prev / next 主角的上下句（3 行模式用），已跳过空行
     * @param idle 当前**没有正在演唱的歌词**（前奏、间奏、无歌词）——渲染侧据此压暗主角
     */
    data class Resolved(
        val current: String = "",
        val translation: String = "",
        val prev: String = "",
        val next: String = "",
        val idle: Boolean = false,
    ) {
        /** 有没有东西可显示；空则表示窗口应当隐藏 */
        val visible: Boolean get() = current.isNotBlank()
    }

    /**
     * 解析「某播放位置应当显示什么」。
     *
     * @param positionMs 播放器当前位置（毫秒）
     * @param offsetMs 本曲的歌词偏移（设置页可调），调用方无需自己加减
     * @param title 歌名，作为前奏 / 无歌词时的回落内容
     */
    fun resolve(
        lyrics: Lyrics,
        positionMs: Long,
        offsetMs: Int,
        title: String,
    ): Resolved {
        // 没有时间轴歌词：跟不了节拍，显示歌名比显示一句静止的歌词有用
        if (lyrics.isEmpty || !lyrics.synced) return titleOnly(title)

        val lines = lyrics.lines
        val i = lyrics.indexAt(positionMs + offsetMs)

        // 前奏：还没到第一句。显示歌名，并把第一句当作「下一句」预告
        if (i < 0) {
            val first = indexOfNonBlank(lines, 0, 1)
            val fallback = titleOnly(title)
            return fallback.copy(next = first?.let { lines[it].text }.orEmpty())
        }

        val anchor = if (lines[i].text.isNotBlank()) i else indexOfNonBlank(lines, i, -1)
        // 整首歌词都是空行（或全被过滤）：只能回落歌名
        if (anchor == null || anchor < 0) return titleOnly(title)

        val line = lines[anchor]
        return Resolved(
            current = line.text,
            translation = line.translation.orEmpty(),
            prev = indexOfNonBlank(lines, anchor - 1, -1)?.let { lines[it].text }.orEmpty(),
            // 下一句要从「时间轴上当前位置之后」开始找：间奏时 i 停在空行上，
            // 真正的下一句在空行之后，而不是 anchor 之后。
            next = indexOfNonBlank(lines, maxOf(i, anchor) + 1, 1)?.let { lines[it].text }.orEmpty(),
            idle = anchor != i,
        )
    }

    private fun titleOnly(title: String): Resolved =
        if (title.isBlank()) Resolved() else Resolved(current = title, idle = true)

    /**
     * 从 [from] 出发、按 [step] 方向找第一条**非空**歌词行，返回行号；找不到返回 null。
     *
     * 存在的意义：LRC 允许空文本行（间奏标记），而空行显示出来就是「字幕突然没了」。
     * 上下文行与回溯都必须跳过它们。
     */
    private fun indexOfNonBlank(lines: List<LyricLine>, from: Int, step: Int): Int? {
        var i = from
        while (i in lines.indices) {
            if (lines[i].text.isNotBlank()) return i
            i += step
        }
        return null
    }
}
