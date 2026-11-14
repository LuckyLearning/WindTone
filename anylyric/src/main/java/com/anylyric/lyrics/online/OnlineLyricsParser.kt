package com.anylyric.lyrics.online

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** 搜索命中的一首歌（还没取词）。 */
data class SongCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long
)

/** 酷狗歌词候选：要 id + accesskey 才能下载。 */
data class KugouLyricCandidate(
    val id: String,
    val accessKey: String,
    val title: String,
    val artist: String,
    val durationSec: Long
)

/**
 * 在线歌词接口的响应解析（纯函数，便于单测）。
 *
 * 接口格式照抄车机上验证过的公开接口：
 *  · 酷狗：搜索 → 按 hash + 时长找歌词 → 下载 base64 的 LRC；
 *  · 网易云：搜索 → 按歌曲 id 取 `lrc.lyric`。
 *
 * 这里只做「从 JSON 里把字段抠出来」，**不做挑选**：挑哪一条由 [com.anylyric.lyrics.LyricMatch]
 * 打分决定。0.2.22 及以前取第一条，结果经常是翻唱 / 剪辑版 / 同名别的歌 ——
 * 屏上滚着别人的词，比没有词更糟。
 */
object OnlineLyricsParser {

    /** 酷狗搜索：全部候选（带 hash、歌名、歌手、时长）。 */
    fun kugouSongs(json: String): List<SongCandidate> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val info = root.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val out = ArrayList<SongCandidate>(info.length())
        for (i in 0 until info.length()) {
            val song = info.optJSONObject(i) ?: continue
            val hash = song.optString("hash").takeIf { it.isNotBlank() } ?: continue
            val name = song.optString("songname").ifBlank { song.optString("filename") }
            out += SongCandidate(
                id = hash,
                title = name,
                artist = song.optString("singername"),
                durationSec = song.optLong("duration", 0L)
            )
        }
        return out
    }

    /** 酷狗歌词搜索：全部候选，`duration` 是毫秒。 */
    fun kugouLyricCandidates(json: String): List<KugouLyricCandidate> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("candidates") ?: return emptyList()
        val out = ArrayList<KugouLyricCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id").takeIf { it.isNotBlank() } ?: continue
            out += KugouLyricCandidate(
                id = id,
                accessKey = c.optString("accesskey"),
                title = c.optString("song"),
                artist = c.optString("singer"),
                durationSec = c.optLong("duration", 0L) / 1000L
            )
        }
        return out
    }

    /** 网易云搜索：全部候选，`duration` 是毫秒。 */
    fun neteaseSongs(json: String): List<SongCandidate> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<SongCandidate>(songs.length())
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val artists = song.optJSONArray("artists")
            val names = ArrayList<String>(2)
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    val name = artists.optJSONObject(j)?.optString("name").orEmpty()
                    if (name.isNotBlank()) names += name
                }
            }
            out += SongCandidate(
                id = id,
                title = song.optString("name"),
                artist = names.joinToString("/"),
                durationSec = song.optLong("duration", 0L) / 1000L
            )
        }
        return out
    }

    // ------------------------------------------------------- 兼容旧调用点/旧测试

    /** 酷狗搜索：第一条（保留给旧调用点，主链路已改用 [kugouSongs] + 打分）。 */
    fun kugouSong(json: String): Pair<String, Long>? {
        val first = kugouSongs(json).firstOrNull() ?: return null
        return first.id to first.durationSec
    }

    /** 酷狗歌词搜索：第一条。 */
    fun kugouCandidate(json: String): Pair<String, String>? {
        val first = kugouLyricCandidates(json).firstOrNull() ?: return null
        return first.id to first.accessKey
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

    /** 网易云搜索：第一首歌的 id。 */
    fun neteaseSongId(json: String): String? = neteaseSongs(json).firstOrNull()?.id

    /** 网易云歌词：`lrc.lyric`。 */
    fun neteaseLyric(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return root.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }

    /** 汽水音乐歌词：`data.lyric.content` → `lyric.content` → `lrc_content`（Cardash 的兜底顺序）。 */
    fun qishuiLyric(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        root.optJSONObject("data")?.optJSONObject("lyric")
            ?.optString("content")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("lyric")
            ?.optString("content")?.takeIf { it.isNotBlank() }?.let { return it }
        return root.optString("lrc_content").takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ 通用候选收集

    private const val MAX_SCAN_DEPTH = 8

    /** 标题键，按优先级取第一个非空（Cardash `R3.k`）。 */
    private val TITLE_KEYS = arrayOf("name", "track_name", "title", "FileName", "songname", "filename")

    /** id 键，按优先级取第一个非空（Cardash `R3.c`）。 */
    private val ID_KEYS = arrayOf("id", "track_id", "FileHash", "filehash", "hash")

    /**
     * 从任意响应里递归收集「像一首歌」的对象（Cardash `R3.c` 的做法）。
     *
     * 为什么不用逐个接口写死结构：三家源的字段名各不相同（`songname` / `name` /
     * `track_name` / `FileName`），同一家换个接口又换一套。Cardash 只用一条规则 ——
     * 「有标题、有 id」就算候选 —— 就同时吃下了搜索、歌词候选与汽水三种结构，
     * 新增一家源不用再写解析器。**这是它取词稳的结构性原因之一。**
     */
    fun candidates(json: String): List<SongCandidate> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SongCandidate>(8)
        collect(root, out, 0)
        return out
    }

    private fun collect(node: Any?, out: MutableList<SongCandidate>, depth: Int) {
        if (depth > MAX_SCAN_DEPTH) return
        when (node) {
            is JSONObject -> {
                val title = firstString(node, TITLE_KEYS)
                val id = firstString(node, ID_KEYS)
                if (title.isNotEmpty() && id.isNotEmpty()) {
                    out += SongCandidate(id, title, artistsOf(node), durationSecOf(node))
                }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, depth + 1)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
            }
        }
    }

    private fun firstString(node: JSONObject, keys: Array<String>): String {
        for (key in keys) {
            val value = node.opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number) return value.toString()
        }
        return ""
    }

    /** 歌手：`artist` / `singername` / `artists[].name` / `ar[].name`，多个用 `/` 连起来。 */
    private fun artistsOf(node: JSONObject): String {
        val parts = ArrayList<String>(4)
        for (key in arrayOf("artist", "singername")) {
            val value = node.optString(key).trim()
            if (value.isNotEmpty()) parts += value
        }
        for (key in arrayOf("artists", "ar")) {
            val arr = node.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) parts += name
            }
        }
        return parts.joinToString("/")
    }

    /** 时长：`duration` / `dt` / `duration_ms`，小于 10000 的按秒算（Cardash 同款量级判断）。 */
    private fun durationSecOf(node: JSONObject): Long {
        val raw = node.optLong(
            "duration",
            node.optLong("dt", node.optLong("duration_ms", 0L))
        )
        return when {
            raw <= 0L -> 0L
            raw < 10_000L -> raw
            else -> raw / 1_000L
        }
    }
}
