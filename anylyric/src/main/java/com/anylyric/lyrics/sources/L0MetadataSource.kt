package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LrcParser
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsSource
import com.anylyric.lyrics.TrackContext

/**
 * L0：从 `MediaMetadata` 的自定义键里取歌词。
 *
 * 判定用「键名白名单」+「内容特征」双判：
 *  · 键名像歌词（lyric / lyrics / lrc / 歌词，忽略大小写）；
 *  · 或值本身长得像 LRC（`[mm:ss.xx]`）。
 * 双判后仍要求解析出 >= 2 行才采纳，避免把描述类文本误当歌词。
 *
 * 覆盖极低，但成本为零，且是唯一「播放器主动提供整段带时间轴歌词」的通道。
 */
class L0MetadataSource : LyricsSource {

    override val id: String = "L0"

    override fun tryResolve(ctx: TrackContext): LyricResult {
        if (ctx.metadata.isEmpty()) return LyricResult.Miss(id, "no_metadata")

        var sawStringValue = false
        for ((key, value) in ctx.metadata) {
            val text = value as? String ?: continue
            sawStringValue = true
            if (!isLyricKey(key) && !LrcParser.looksLikeLrc(text)) continue

            val lines = LrcParser.parse(text)
            if (lines.size >= 2) return LyricResult.Hit(id, lines)
        }
        return LyricResult.Miss(id, if (sawStringValue) "no_lyric_key" else "no_string_value")
    }

    private fun isLyricKey(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("lyric") || k.contains("lrc") || k.contains("歌词")
    }
}
