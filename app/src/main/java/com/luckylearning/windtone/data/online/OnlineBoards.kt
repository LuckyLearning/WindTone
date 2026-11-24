package com.luckylearning.windtone.data.online

import android.util.Log
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 内置排行榜，对齐洛雪 `src/utils/musicSdk/{kw,kg,tx,mg}/leaderboard.js`。
 *
 * 与自定义音源脚本的关系：榜单是**发现歌曲**的入口，和搜索同层，所以由客户端内置实现；
 * 脚本仍然只负责把 `raw` 换成直链（洛雪 mobile 的 preload 里 `supportActions`
 * 对 kw/kg/tx/wy/mg 只放行 `musicUrl`，脚本根本没有 search/list 能力）。
 *
 * 榜单清单照洛雪硬编码：各家真正稳定的只有榜单 id，走「先请求榜单列表接口」会多一次
 * 失败点，而洛雪同样是写死的常量表。id 与名称一一对应，改这里等于改界面。
 */
object OnlineBoards {

    private const val TAG = "OnlineBoards"

    data class Board(val source: String, val id: String, val name: String)

    data class Group(val source: String, val name: String, val boards: List<Board>)

    val GROUPS: List<Group> = listOf(
        Group(
            "kw", "酷我音乐",
            listOf(
                Board("kw", "93", "飙升榜"),
                Board("kw", "16", "热歌榜"),
                Board("kw", "17", "新歌榜"),
                Board("kw", "158", "抖音热歌榜"),
                Board("kw", "145", "会员畅听榜"),
                Board("kw", "187", "流行趋势榜"),
                Board("kw", "26", "经典怀旧榜"),
                Board("kw", "104", "华语榜"),
                Board("kw", "182", "粤语榜"),
                Board("kw", "22", "欧美榜"),
                Board("kw", "184", "韩语榜"),
                Board("kw", "183", "日语榜"),
                Board("kw", "284", "热评榜"),
                Board("kw", "279", "冬日暖心榜"),
                Board("kw", "278", "古风音乐榜"),
                Board("kw", "242", "电音榜"),
            ),
        ),
        Group(
            "kg", "酷狗音乐",
            listOf(
                Board("kg", "8888", "TOP500"),
                Board("kg", "6666", "飙升榜"),
                Board("kg", "59703", "蜂鸟流行音乐榜"),
                Board("kg", "52144", "抖音热歌榜"),
                Board("kg", "52767", "快手热歌榜"),
                Board("kg", "24971", "DJ热歌榜"),
                Board("kg", "23784", "网络红歌榜"),
                Board("kg", "44412", "说唱先锋榜"),
                Board("kg", "31308", "内地榜"),
                Board("kg", "33160", "电音榜"),
                Board("kg", "33165", "粤语金曲榜"),
                Board("kg", "33166", "欧美金曲榜"),
                Board("kg", "33163", "影视金曲榜"),
                Board("kg", "59895", "R&B榜"),
                Board("kg", "59896", "摇滚榜"),
                Board("kg", "65234", "儿歌榜"),
            ),
        ),
        Group(
            "tx", "QQ音乐",
            listOf(
                Board("tx", "4", "流行指数榜"),
                Board("tx", "26", "热歌榜"),
                Board("tx", "27", "新歌榜"),
                Board("tx", "62", "飙升榜"),
                Board("tx", "58", "说唱榜"),
                Board("tx", "57", "喜力电音榜"),
                Board("tx", "28", "网络歌曲榜"),
                Board("tx", "5", "内地榜"),
                Board("tx", "3", "欧美榜"),
                Board("tx", "59", "香港地区榜"),
                Board("tx", "16", "韩国榜"),
                Board("tx", "60", "抖快榜"),
                Board("tx", "29", "影视金曲榜"),
                Board("tx", "17", "日本榜"),
                Board("tx", "61", "台湾地区榜"),
                Board("tx", "64", "综艺新歌榜"),
            ),
        ),
        Group(
            "mg", "咪咕音乐",
            listOf(
                Board("mg", "27553319", "尖叫新歌榜"),
                Board("mg", "27186466", "尖叫热歌榜"),
                Board("mg", "27553408", "尖叫原创榜"),
                Board("mg", "23189800", "港台榜"),
                Board("mg", "23189399", "内地榜"),
                Board("mg", "19190036", "欧美榜"),
                Board("mg", "23189813", "日韩榜"),
                Board("mg", "15140045", "KTV榜"),
                Board("mg", "15140034", "网络榜"),
            ),
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 拉一个榜单的歌曲。
     *
     * 失败返回空列表 + error（不抛异常）：榜单页要能显示「这个榜暂时取不到」，
     * 而不是整页崩掉或一直转圈。
     */
    suspend fun fetch(board: Board, page: Int = 1, limit: Int = 100): BuiltInSearch.Result =
        withContext(Dispatchers.IO) {
            val tracks = runCatching {
                when (board.source) {
                    "kw" -> kwTracks(board.id, page, limit)
                    "kg" -> kgTracks(board.id, page, limit)
                    "tx" -> txTracks(board.id, limit)
                    "mg" -> mgTracks(board.id)
                    else -> emptyList()
                }
            }.onFailure { Log.w(TAG, "榜单 ${board.name} 取不到：${it.message}") }
                .getOrElse { emptyList() }
            BuiltInSearch.Result(
                source = board.source,
                tracks = tracks,
                total = tracks.size,
                error = if (tracks.isEmpty()) "榜单暂无数据" else null,
            )
        }

    // ---------------------------------------------------------------- 酷我

    private fun kwTracks(boardId: String, page: Int, limit: Int): List<OnlineTrack> {
        val url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=${page - 1}&rn=$limit" +
            "&type=bang&data=content&id=$boardId&show_copyright_off=0&pcmp4=1&isbang=1"
        return parseKwBoard(httpGet(url))
    }

    /** 包内可见，供单测钉死酷我榜单结构（`musiclist[]`，字段名与搜索接口不同） */
    internal fun parseKwBoard(text: String): List<OnlineTrack> {
        val root = rootOf(text) ?: return emptyList()
        val list = root.optJSONArray("musiclist") ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val title = BuiltInSearch.decodeName(item.optString("name"))
            val artist = BuiltInSearch.decodeName(item.optString("artist"))
            // 酷我榜单不返回封面字段，但封面接口只认 rid，播放页与行内缩略图都用得上
            val cover = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$id"
            val qualities = listOf(Quality.Q320, Quality.Q128)
            val raw = JSONObject().apply {
                put("source", "kw")
                put("songmid", id)
                put("name", title)
                put("singer", artist)
                put("albumId", item.optString("albumid"))
                put("albumName", BuiltInSearch.decodeName(item.optString("album")))
                put("duration", item.optInt("duration"))
                put("types", JSONArray().apply {
                    put(JSONObject().put("type", Quality.Q320.id))
                    put(JSONObject().put("type", Quality.Q128.id))
                })
            }
            out += OnlineTrack(
                sourceId = "kw",
                songId = id,
                title = title,
                artist = artist,
                album = BuiltInSearch.decodeName(item.optString("album")).takeIf { it.isNotBlank() },
                durationMs = item.optLong("duration") * 1000L,
                qualities = qualities,
                raw = raw.toString(),
                coverUrl = cover,
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 酷狗

    private fun kgTracks(boardId: String, page: Int, limit: Int): List<OnlineTrack> {
        val url = "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=1&plat=0" +
            "&pagesize=$limit&area_code=1&page=$page&rankid=$boardId&with_res_tag=0&show_portrait_mv=1"
        return parseKgBoard(httpGet(url))
    }

    /** 包内可见，供单测钉死酷狗榜单结构（`data.info[]`，每档位一个 hash） */
    internal fun parseKgBoard(text: String): List<OnlineTrack> {
        val data = rootOf(text)?.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("info") ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val hash = item.optString("hash")
            if (hash.isBlank()) continue
            val audioId = item.optString("audio_id")
            val title = BuiltInSearch.decodeName(item.optString("songname"))
            // 榜单的 singername 经常为空，洛雪用的是 authors[].author_name
            val artist = BuiltInSearch.decodeName(item.optString("singername")).ifBlank {
                item.optJSONArray("authors")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("author_name") }
                        .filter { it.isNotBlank() }.joinToString("、")
                }.orEmpty()
            }
            val qualities = mutableListOf<Quality>()
            val types = JSONArray()
            // 酷狗每个档位有自己的 hash，脚本按 info.type 取对应 hash —— 只给平铺 hash 会报 missing
            if (item.optLong("filesize") > 0) {
                qualities += Quality.Q128
                types.put(JSONObject().put("type", Quality.Q128.id).put("hash", hash))
            }
            val hash320 = item.optString("320hash")
            if (item.optLong("320filesize") > 0 && hash320.isNotBlank()) {
                qualities += Quality.Q320
                types.put(JSONObject().put("type", Quality.Q320.id).put("hash", hash320))
            }
            val sqHash = item.optString("sqhash")
            if (item.optLong("sqfilesize") > 0 && sqHash.isNotBlank()) {
                qualities += Quality.FLAC
                types.put(JSONObject().put("type", Quality.FLAC.id).put("hash", sqHash))
            }
            val superHash = item.optString("hash_super")
            if (item.optLong("filesize_super") > 0 && superHash.isNotBlank()) {
                qualities += Quality.FLAC24
                types.put(JSONObject().put("type", Quality.FLAC24.id).put("hash", superHash))
            }
            if (qualities.isEmpty()) {
                qualities += Quality.Q128
                types.put(JSONObject().put("type", Quality.Q128.id).put("hash", hash))
            }
            val raw = JSONObject().apply {
                put("source", "kg")
                put("songmid", audioId)
                put("hash", hash)
                put("name", title)
                put("singer", artist)
                put("albumId", item.optString("album_id"))
                put("albumName", BuiltInSearch.decodeName(item.optString("remark")))
                put("duration", item.optInt("duration"))
                put("types", types)
            }
            out += OnlineTrack(
                sourceId = "kg",
                songId = audioId.ifBlank { hash },
                title = title,
                artist = artist,
                album = BuiltInSearch.decodeName(item.optString("remark")).takeIf { it.isNotBlank() },
                durationMs = item.optLong("duration") * 1000L,
                qualities = qualities,
                raw = raw.toString(),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- QQ

    private fun txTracks(boardId: String, limit: Int): List<OnlineTrack> {
        val body = JSONObject().apply {
            put("toplist", JSONObject().apply {
                put("module", "musicToplist.ToplistInfoServer")
                put("method", "GetDetail")
                put("param", JSONObject().apply {
                    put("topid", boardId.toIntOrNull() ?: 0)
                    put("num", limit)
                    put("period", "")
                })
            })
            put("comm", JSONObject().apply {
                put("uin", 0); put("format", "json"); put("ct", 20); put("cv", 1859)
            })
        }
        val text = httpPost(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body.toString(),
            mapOf(
                "User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)",
                "Referer" to "https://y.qq.com/",
            ),
        )
        return parseTxBoard(text)
    }

    /**
     * 包内可见，供单测钉死 QQ 榜单结构。
     * 榜单走 `toplist.data.songInfoList`，与搜索的 `req.data.body.song.list` 不同层。
     */
    internal fun parseTxBoard(text: String): List<OnlineTrack> {
        val root = rootOf(text) ?: return emptyList()
        val toplist = root.optJSONObject("toplist") ?: return emptyList()
        if (toplist.optInt("code", -1) != 0) return emptyList()
        val list = toplist.optJSONObject("data")?.optJSONArray("songInfoList") ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val mid = item.optString("mid")
            if (mid.isBlank()) continue
            val file = item.optJSONObject("file") ?: JSONObject()
            val album = item.optJSONObject("album") ?: JSONObject()
            val albumMid = album.optString("mid")
            val title = item.optString("name").ifBlank { item.optString("title") }
            val artist = item.optJSONArray("singer")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }.joinToString("、")
            }.orEmpty()
            val cover = if (albumMid.isNotBlank()) {
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            } else ""
            val qualities = mutableListOf<Quality>()
            if (file.optLong("size_128mp3") > 0) qualities += Quality.Q128
            if (file.optLong("size_320mp3") > 0) qualities += Quality.Q320
            if (file.optLong("size_flac") > 0) qualities += Quality.FLAC
            if (file.optLong("size_hires") > 0) qualities += Quality.FLAC24
            if (qualities.isEmpty()) qualities += Quality.Q128
            val raw = JSONObject().apply {
                put("source", "tx")
                put("songmid", mid)
                put("songId", item.opt("id"))
                put("strMediaMid", file.optString("media_mid").ifBlank { mid })
                put("albumMid", albumMid)
                put("name", title)
                put("singer", artist)
                put("albumName", album.optString("name"))
                put("interval", item.optInt("interval"))
                put("img", cover)
                put("types", JSONArray().apply {
                    qualities.sortedBy { it.ordinal }.forEach { put(JSONObject().put("type", it.id)) }
                })
            }
            out += OnlineTrack(
                sourceId = "tx",
                songId = mid,
                title = title,
                artist = artist,
                album = album.optString("name").takeIf { it.isNotBlank() },
                durationMs = item.optLong("interval") * 1000L,
                qualities = qualities,
                raw = raw.toString(),
                coverUrl = cover,
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 咪咕

    private fun mgTracks(boardId: String): List<OnlineTrack> {
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do" +
            "?columnId=$boardId&needAll=0"
        val text = httpGet(
            url,
            mapOf(
                "Referer" to "https://app.c.nf.migu.cn/",
                "channel" to "0146921",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            ),
        )
        return parseMgBoard(text)
    }

    /** 包内可见，供单测钉死咪咕榜单结构（`columnInfo.contents[].objectInfo`） */
    internal fun parseMgBoard(text: String): List<OnlineTrack> {
        val root = rootOf(text) ?: return emptyList()
        if (root.optString("code") != "000000") return emptyList()
        val contents = root.optJSONObject("columnInfo")?.optJSONArray("contents") ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        val seen = HashSet<String>()
        for (i in 0 until contents.length()) {
            val d = contents.optJSONObject(i)?.optJSONObject("objectInfo") ?: continue
            val songId = d.optString("songId")
            val copyrightId = d.optString("copyrightId")
            if (songId.isBlank() || copyrightId.isBlank() || !seen.add(copyrightId)) continue
            val title = d.optString("songName")
            val artist = d.optString("singer")
            val cover = mgCover(d)
            val qualities = listOf(Quality.Q320, Quality.Q128)
            val raw = JSONObject().apply {
                put("source", "mg")
                put("songmid", songId)
                put("copyrightId", copyrightId)
                put("name", title)
                put("singer", artist)
                put("albumName", d.optString("album"))
                put("albumId", d.optString("albumId"))
                put("duration", mgDurationSeconds(d.optString("length")))
                put("img", cover)
                put("lrcUrl", d.optString("lrcUrl"))
                put("types", JSONArray().apply {
                    put(JSONObject().put("type", Quality.Q320.id))
                    put(JSONObject().put("type", Quality.Q128.id))
                })
            }
            out += OnlineTrack(
                sourceId = "mg",
                songId = songId,
                title = title,
                artist = artist,
                album = d.optString("album").takeIf { it.isNotBlank() },
                durationMs = mgDurationSeconds(d.optString("length")) * 1000L,
                qualities = qualities,
                raw = raw.toString(),
                coverUrl = cover,
            )
        }
        return out
    }

    private fun mgCover(d: JSONObject): String {
        d.optJSONArray("albumImgs")?.let { arr ->
            var best = ""
            for (i in 0 until arr.length()) {
                val img = arr.optJSONObject(i)?.optString("img").orEmpty()
                // 咪咕部分条目给 webp：能解码但耗电、且和 jpg 混排时容易取到小图，优先跳过
                if (img.isBlank() || img.endsWith(".webp", ignoreCase = true)) continue
                if (img.length > best.length) best = img
            }
            if (best.isNotBlank()) return best.replaceFirst("http://", "https://")
        }
        val img3 = d.optString("img3")
        if (img3.isBlank()) return ""
        val full = if (img3.startsWith("http")) img3 else "https://d.musicapp.migu.cn$img3"
        return full.replaceFirst("http://", "https://")
    }

    /** 咪咕榜单给的是 `mm:ss` 或 `hh:mm:ss` 文本 */
    private fun mgDurationSeconds(text: String): Long {
        if (text.isBlank()) return 0L
        val parts = text.split(':')
        return when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
            3 -> (parts[0].toLongOrNull() ?: 0L) * 3600 +
                (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
            else -> text.toLongOrNull() ?: 0L
        }
    }

    /**
     * 容错解析：榜单接口被限流 / 被墙时常见返回 HTML 错误页或无 BOM 的纯文本，
     * 直接 `JSONObject(text)` 会抛异常。帖子层已经有 `runCatching`，
     * 但解析函数自己也不能崩 —— 单测里就是用 `<html>502</html>` 钉的这条。
     */
    private fun rootOf(text: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        return client.newCall(b.build()).execute().use { it.body?.string().orEmpty() }
    }

    private fun httpPost(url: String, body: String, headers: Map<String, String>): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        return client.newCall(
            b.post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())).build(),
        ).execute().use { it.body?.string().orEmpty() }
    }
}
