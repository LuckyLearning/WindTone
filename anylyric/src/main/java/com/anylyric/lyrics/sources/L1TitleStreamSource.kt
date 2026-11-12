package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsSource
import com.anylyric.lyrics.TrackContext

/**
 * L1：播放器把歌词逐行写进 `MediaMetadata.TITLE`（车载／蓝牙歌词的常见做法）。
 *
 * 关键点：
 *  · 只有 [TrackContext.pseudoTitle] 为 true（已判定伪歌名）才输出，否则正常播放时
 *    歌名会被当成歌词行；
 *  · 缓冲一直在累积，判定成立时**历史行一并交出**，因此不会丢掉开头两三句；
 *  · 真换歌时由调用方 [reset]，避免把上一首的行混进来。
 */
class L1TitleStreamSource(private val capacity: Int = 200) : LyricsSource {

    override val id: String = "L1"

    private val lines = ArrayDeque<String>()

    /** 每次 TITLE 变化调用；连续重复行丢弃。 */
    fun onTitleLine(text: String?) {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return
        if (lines.lastOrNull() == normalized) return
        lines.addLast(normalized)
        while (lines.size > capacity) lines.removeFirst()
    }

    fun reset() = lines.clear()

    fun bufferedCount(): Int = lines.size

    override fun tryResolve(ctx: TrackContext): LyricResult {
        if (!ctx.pseudoTitle) return LyricResult.Miss(id, "stable_title")
        if (lines.size < 2) return LyricResult.Miss(id, "too_few_lines")
        return LyricResult.Hit(id, lines.map { LyricLine(null, it) })
    }
}
