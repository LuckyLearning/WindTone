package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LrcParser
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsJson
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
 *
 * **解析缓存**：同一段文本的结果会被记住。真机上元数据回调一秒能来好几次，
 * 每次都把几 KB 的歌词重新过一遍正则、重新排序纯属浪费；换歌时文本必然变，
 * 用「键名 + 长度 + hashCode」判等就够（`String.hashCode` 首次算完即缓存，之后 O(1)）。
 */
class L0MetadataSource : LyricsSource {

    override val id: String = "L0"

    private var cachedKey: String? = null
    private var cachedLines: List<LyricLine> = emptyList()

    /** 真正调用过解析器的次数。只为单测证明缓存生效，不参与任何业务判断。 */
    internal var parseCount: Int = 0
        private set

    override fun tryResolve(ctx: TrackContext): LyricResult {
        if (ctx.metadata.isEmpty()) return LyricResult.Miss(id, "no_metadata")

        var sawStringValue = false
        for ((key, value) in ctx.metadata) {
            // 只吃字符串：位图（封面）对 L0 没有意义，也不该为它多分配一次
            val text = value as? String ?: continue
            sawStringValue = true
            if (!isLyricKey(key) && !LrcParser.looksLikeLrc(text)) continue

            // 缓存键用**原文**：同一段文本反复回调时，连「是不是 JSON」都不用再判。
            val lines = parseCached(key, text)
            if (lines.size >= 2) return LyricResult.Hit(id, lines)
        }
        return LyricResult.Miss(id, if (sawStringValue) "no_lyric_key" else "no_string_value")
    }

    private fun parseCached(key: String, text: String): List<LyricLine> {
        val cacheKey = buildString(key.length + 16) {
            append(key).append('#').append(text.length).append('#').append(text.hashCode())
        }
        if (cacheKey == cachedKey) return cachedLines
        // 少数播放器把歌词包在 JSON 里（{"lyrics": "..."}）；解包后仍是原文缓存。
        val raw = LyricsJson.unwrap(text) ?: ""
        val lines = LrcParser.parse(raw)
        parseCount++
        cachedKey = cacheKey
        cachedLines = lines
        return lines
    }

    private fun isLyricKey(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("lyric") || k.contains("lrc") || k.contains("歌词")
    }
}
