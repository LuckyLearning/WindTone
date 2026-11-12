package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsSource
import com.anylyric.lyrics.TitleTimeline
import com.anylyric.lyrics.TrackContext

/**
 * L1：播放器把歌词逐行写进 `MediaMetadata.TITLE`（车载／蓝牙歌词的常见做法）。
 *
 * 关键点：
 *  · **缓冲与判定解耦**：调用方必须在每次 TITLE 变化时就调 [onTitleLine]，不等
 *    判定成立。这样 [TrackContext.pseudoTitle] 一变 true，判定前已经看到的那几句
 *    可以立刻整体交出，而不是从零开始攒。
 *  · 只有 [TrackContext.pseudoTitle] 为 true 才输出，否则正常播放时歌名会被当成
 *    歌词行；
 *  · 真换歌时由调用方 [reset]，避免把上一首的行混进来。
 */
class L1TitleStreamSource(private val capacity: Int = 200) : LyricsSource {

    override val id: String = "L1"

    private val lines = ArrayDeque<String>()

    /** 每次 TITLE 变化调用；重复行与「后缀变体」（歌名 → 歌名 - 播放中）丢弃。 */
    fun onTitleLine(text: String?) {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return
        val last = lines.lastOrNull()
        if (last != null && TitleTimeline.isTrivialVariant(last, normalized)) return
        lines.addLast(normalized)
        while (lines.size > capacity) lines.removeFirst()
    }

    fun reset() = lines.clear()

    fun bufferedCount(): Int = lines.size

    override fun tryResolve(ctx: TrackContext): LyricResult {
        if (!ctx.pseudoTitle) return LyricResult.Miss(id, "stable_title")
        if (lines.size < 2) return LyricResult.Miss(id, "too_few_lines")
        // 缓冲是「历史行 + 当前行在末尾」：后面还没有可看的行。
        return LyricResult.Hit(id, lines.map { LyricLine(null, it) }, currentLineIndex = lines.lastIndex)
    }
}
