package com.anylyric.lyrics.online

import org.json.JSONObject
import java.util.Base64

/**
 * 在线歌词接口的响应解析（纯函数，便于单测）。
 *
 * 接口格式照抄车机上验证过的公开接口：
 *  · 酷狗：搜索 → 按 hash + 时长找歌词 → 下载 base64 的 LRC；
 *  · 网易云：搜索 → 按歌曲 id 取 `lrc.lyric`。
 *
 * 这里只做「从 JSON 里把字段抠出来」，不做网络、不做缓存 —— 那两件事在
 * [OnlineLyricsFetcher] 里，分开放才能让解析逻辑在 JVM 单测里跑。
 */
object OnlineLyricsParser {

    /** 酷狗搜索：取第一个带 hash 的歌曲，返回 hash 与时长（秒）。 */
    fun kugouSong(json: String): Pair<String, Long>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val info = root.optJSONObject("data")?.optJSONArray("info") ?: return null
        for (i in 0 until info.length()) {
            val song = info.optJSONObject(i) ?: continue
            val hash = song.optString("hash").takeIf { it.isNotBlank() } ?: continue
            return hash to song.optLong("duration", 0L)
        }
        return null
    }

    /** 酷狗歌词搜索：取第一个候选，返回 id 与 accesskey。 */
    fun kugouCandidate(json: String): Pair<String, String>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val arr = root.optJSONArray("candidates") ?: return null
        for (i in 0 until arr.length()) {
            val candidate = arr.optJSONObject(i) ?: continue
            val id = candidate.optString("id").takeIf { it.isNotBlank() } ?: continue
            return id to candidate.optString("accesskey")
        }
        return null
    }

    /** 酷狗下载：`content` 是 base64 编码的 LRC 文本。 */
    fun kugouContent(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val content = root.optString("content").takeIf { it.isNotBlank() } ?: return null
        // MIME 解码器会忽略换行等非字母表字符：酷狗偶尔把 base64 折行返回，
        // 标准解码器遇到换行会直接抛异常。
        return runCatching {
            String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
        }.getOrNull()
    }

    /** 网易云搜索：取第一首歌的 id。 */
    fun neteaseSongId(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            return id
        }
        return null
    }

    /** 网易云歌词：`lrc.lyric`。 */
    fun neteaseLyric(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return root.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }
}
