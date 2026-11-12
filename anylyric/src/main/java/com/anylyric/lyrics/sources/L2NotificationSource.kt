package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LrcParser
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsSource
import com.anylyric.lyrics.TrackContext

/**
 * L2：媒体通知里的文本。
 *
 * 两种形态：
 *  1. 通知字段里直接是一整段带时间轴的 LRC（少见，但一旦命中就是完整时间轴）；
 *  2. 通知正文是多行纯文本 —— 只在已判定伪歌名时当作无时间轴的逐行流，
 *     避免把「歌名 / 歌手」这类单行文本误判成歌词。
 */
class L2NotificationSource : LyricsSource {

    override val id: String = "L2"

    override fun tryResolve(ctx: TrackContext): LyricResult {
        if (ctx.notificationTexts.isEmpty()) return LyricResult.Miss(id, "no_notification")

        for (value in ctx.notificationTexts.values) {
            if (!LrcParser.looksLikeLrc(value)) continue
            val lines = LrcParser.parse(value)
            if (lines.size >= 2) return LyricResult.Hit(id, lines)
        }

        if (ctx.pseudoTitle) {
            for (value in ctx.notificationTexts.values) {
                val parts = value.split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    return LyricResult.Hit(id, parts.map { LyricLine(null, it) })
                }
            }
        }
        return LyricResult.Miss(id, "no_lyric_text")
    }
}
