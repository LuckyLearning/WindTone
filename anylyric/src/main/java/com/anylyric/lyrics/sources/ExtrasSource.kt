package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LrcParser
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.LyricsJson
import com.anylyric.lyrics.LyricsSource
import com.anylyric.lyrics.TrackContext

/**
 * 播放状态扩展字段（`PlaybackState.extras`）取词。
 *
 * 为什么单独开一条通道：部分车机 / 国产播放器**不把歌词放进 MediaMetadata**，而是放进
 * 播放状态的 extras，键名是它们的私有约定。真机里最常见的是 BYD 车机：
 *
 *  · `lyrics` —— 整段 LRC；
 *  · `lrc_line_1` / `lrc_line_2` / `lrc_line_3` —— 滚动窗口，**第 1 行才是当前句**。
 *
 * 旧实现只读 `MediaMetadata`，这两处在任何播放器上都看不到，于是明明有歌词却一路 miss
 * 到标题/通知兜底，最后显示「暂无歌词」或只有两行。
 *
 * 解析缓存与 L0 同构：整段 LRC 只按「键名 + 长度 + hashCode」解析一次。滚动窗口不解析，
 * 直接按行号锚定（`currentLineIndex = 0`），逐行变化时零解析成本。
 */
class ExtrasSource : LyricsSource {

    override val id: String = "L0b"

    private var cachedKey: String? = null
    private var cachedLines: List<LyricLine> = emptyList()

    /** 真正调用过解析器的次数。只为单测证明缓存生效，不参与业务判断。 */
    internal var parseCount: Int = 0
        private set

    override fun tryResolve(ctx: TrackContext): LyricResult {
        if (ctx.playbackExtras.isEmpty()) return LyricResult.Miss(id, "no_extras")

        // 1) 整段歌词：能解析出 2 行以上就当作完整时间轴。
        val full = firstFullKey(ctx)
        val fullLines = full?.let { parseCached(it.first, it.second) }.orEmpty()
        if (fullLines.size >= 2) return LyricResult.Hit(id, fullLines)

        // 2) 滚动窗口：lrc_line_1 是当前句，2/3 是后续行。
        //    窗口比「只有一行的整段歌词」信息更多，所以优先窗口。
        val window = ArrayList<LyricLine>(LINE_KEYS.size)
        for (key in LINE_KEYS) {
            val text = ctx.playbackExtras[key]?.trim()
            if (!text.isNullOrEmpty()) window += LyricLine(null, text)
        }
        if (window.isNotEmpty()) {
            return LyricResult.Hit(id, window, currentLineIndex = 0)
        }

        // 3) 整段歌词只有一行：当作当前句。
        if (fullLines.size == 1) return LyricResult.Hit(id, fullLines, currentLineIndex = 0)

        return LyricResult.Miss(id, "no_lyric_field")
    }

    /** 依次尝试整段歌词键，返回「键名 → 原文」（解包留到缓存里做）。 */
    private fun firstFullKey(ctx: TrackContext): Pair<String, String>? {
        for (key in FULL_KEYS) {
            val raw = ctx.playbackExtras[key] ?: continue
            if (raw.isNotBlank()) return key to raw
        }
        return null
    }

    private fun parseCached(key: String, text: String): List<LyricLine> {
        val cacheKey = buildString(key.length + 16) {
            append(key).append('#').append(text.length).append('#').append(text.hashCode())
        }
        if (cacheKey == cachedKey) return cachedLines
        val raw = LyricsJson.unwrap(text) ?: ""
        val lines = LrcParser.parse(raw)
        parseCount++
        cachedKey = cacheKey
        cachedLines = lines
        return lines
    }

    private companion object {
        /** 整段歌词的键名。 */
        val FULL_KEYS = arrayOf("lyrics", "lyric", "lrc")

        /** 滚动窗口的三个键，顺序即显示顺序（第 1 个是当前句）。 */
        val LINE_KEYS = arrayOf("lrc_line_1", "lrc_line_2", "lrc_line_3")
    }
}
