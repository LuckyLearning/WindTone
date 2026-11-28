package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.lyrics.LrcParser
import com.luckylearning.windtone.lyrics.LyricSource
import com.luckylearning.windtone.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「搜索歌词」：复用内置在线搜索找候选，再按候选的源内信息取词。
 *
 * 与自动取词的区别只在入口：自动取词只按当前曲的身份找；这里是用户输入关键词、
 * 从多个平台里挑一个版本。结果一旦应用，会写进 [com.luckylearning.windtone.data.LyricsOverrideStore]，
 * 下次播放同一首优先用它，不会再被自动取词覆盖。
 */
object LyricsSearch {

    data class Candidate(
        val track: OnlineTrack,
        val sourceLabel: String,
    ) {
        val key: String get() = track.stableKey
    }

    /** 多源并发搜索；单源失败不影响其它源。每源取少量，够用户选即可。 */
    suspend fun search(
        keyword: String,
        limitPerSource: Int = 8,
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.length < 1) return@withContext emptyList()
        BuiltInSearch.search(q, limit = limitPerSource)
            .flatMap { result ->
                val label = BuiltInSearch.SOURCE_NAMES[result.source] ?: result.source
                result.tracks.map { Candidate(it, label) }
            }
            .distinctBy { it.key }
            .take(40)
    }

    suspend fun fetchText(track: OnlineTrack): String? = OnlineLyrics.fetchTextFor(track)

    /** 统一的正文解析口径：有时间轴按 LRC，无时间轴按纯文本，都标记为「手动选择」。 */
    fun parse(text: String): Lyrics {
        val body = text.trim().removePrefix("\uFEFF")
        if (body.isEmpty()) return Lyrics.EMPTY
        return if (LrcParser.hasTimeline(body)) {
            LrcParser.parse(body, LyricSource.MANUAL)
        } else {
            LrcParser.plainText(body, LyricSource.MANUAL)
        }
    }
}
