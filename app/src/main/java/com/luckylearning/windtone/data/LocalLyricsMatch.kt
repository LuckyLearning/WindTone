package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource

/**
 * 在线曲目找不到云盘旁路 / 内嵌歌词时，回落到本机曲库的「同名匹配」。
 *
 * 产品口径（与规划 l1 一致）：**不做网络搜词**。只在用户本机已经有同名本地歌、
 * 且那首歌带侧车或内嵌歌词时，把歌词借过来显示。匹配不到就诚实显示空态提示。
 *
 * 规则故意偏严：先比规范化标题；两边都有歌手时再比歌手；优先带侧车歌词的那首。
 */
object LocalLyricsMatch {

    fun find(library: List<Song>, remote: Song): Song? {
        if (remote.source != SongSource.REMOTE && !isHttp(remote)) return null
        val title = normalize(remote.title)
        if (title.isEmpty()) return null
        val artist = normalize(remote.artist)
        val locals = library.filter { local ->
            local.source != SongSource.REMOTE &&
                !isHttp(local) &&
                normalize(local.title) == title
        }
        if (locals.isEmpty()) return null
        if (artist.isNotEmpty()) {
            locals.firstOrNull { normalize(it.artist) == artist }?.let { return it }
        }
        return locals.firstOrNull { it.hasSidecarLyric } ?: locals.firstOrNull()
    }

    fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.replace(WHITESPACE, " ").orEmpty()

    private fun isHttp(song: Song): Boolean {
        val scheme = song.uri.scheme
        return scheme.equals("http", true) || scheme.equals("https", true)
    }

    private val WHITESPACE = Regex("\\s+")
}