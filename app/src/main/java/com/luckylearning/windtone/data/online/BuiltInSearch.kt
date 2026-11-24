package com.luckylearning.windtone.data.online

import android.util.Log
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 内置搜索：对齐洛雪 `api-source` 的实现，只取 search 这一段。
 *
 * 为什么必须有它：v2 自定义源只提供 `musicUrl`（§2.2.1 实测），没有任何一方
 * 能告诉用户「有哪些歌可搜」。洛雪的做法就是客户端内置五家搜索，脚本只换直链，
 * 这里照做。
 *
 * 每条结果都按洛雪的字段名生成 JSON 存进 [OnlineTrack.raw]，取直链时原样
 * 回传给脚本 —— 各家签名参数（hash / strMediaMid / copyrightId …）都在里面，
 * 少一个字段就是「搜得到但播不了」。
 */
object BuiltInSearch {

    private const val TAG = "BuiltInSearch"

    /** 可搜索的源（网易的 eapi 较重，单独排后面） */
    val SOURCES = listOf("kw", "kg", "tx", "mg")

    val SOURCE_NAMES = mapOf(
        "kw" to "酷我音乐", "kg" to "酷狗音乐",
        "tx" to "QQ音乐", "mg" to "咪咕音乐", "wy" to "网易云音乐",
    )

    data class Result(
        val source: String,
        val tracks: List<OnlineTrack>,
        val total: Int = 0,
        val error: String? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 多源并发搜索；单源失败不影响其它源 */
    suspend fun search(
        keyword: String,
        sources: List<String> = SOURCES,
        page: Int = 1,
        limit: Int = 30,
    ): List<Result> = withContext(Dispatchers.IO) {
        val available = sources.filter { it in SOURCES }
        coroutineScope {
            available.map { source ->
                async {
                    runCatching { searchOne(source, keyword, page, limit) }
                        .getOrElse { Result(source, emptyList(), error = it.message ?: "搜索失败") }
                }
            }.awaitAll()
        }
    }

    /**
     * 单个源搜索，带重试。洛雪每家都有自己的 retryNum（kw 2 / kg 3 / tx 5），
     * 这里统一到 3 次：QQ 的 `musics.fcg` 实测会随机返回 `code=2001`（请求签名
     * 有效但服务端限流），重试一次就能拿到数据。不重试的表现是「有时搜得到
     * 有时搜不到」，最难排查。
     */
    private fun searchOne(source: String, keyword: String, page: Int, limit: Int): Result {
        var lastError: String? = null
        repeat(3) { attempt ->
            try {
                val body = when (source) {
                    "kw" -> getKw(keyword, page, limit)
                    "kg" -> getKg(keyword, page, limit)
                    "tx" -> getTx(keyword, page, limit)
                    "mg" -> getMg(keyword, page, limit)
                    else -> return Result(source, emptyList(), error = "不支持的源")
                }
                val parsed = when (source) {
                    "kw" -> parseKw(body, limit)
                    "kg" -> parseKg(body)
                    "tx" -> parseTx(body)
                    "mg" -> parseMg(body)
                    else -> emptyList()
                }
                if (parsed.isNotEmpty()) {
                    Log.i(TAG, "$source 搜索「$keyword」→ ${parsed.size} 首")
                    return Result(source, parsed)
                }
                lastError = "第 ${attempt + 1} 次返回空结果"
            } catch (e: Exception) {
                lastError = e.message ?: "请求失败"
            }
            // 让出 CPU；QQ 的限流是短时的，立刻重试也会撞上
            if (attempt < 2) Thread.sleep(150L * (attempt + 1))
        }
        Log.w(TAG, "$source 搜索「$keyword」失败：$lastError")
        return Result(source, emptyList(), error = lastError)
    }

    // ---------------------------------------------------------------- 酷我
    private fun getKw(keyword: String, page: Int, limit: Int): String {
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${enc(keyword)}" +
            "&pn=${page - 1}&rn=$limit&uid=794762570&ver=kwplayer_ar_9.2.2.1" +
            "&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0" +
            "&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        return httpGet(url)
    }

    /**
     * 容错解析：接口偶尔返回 HTML 错误页 / 空串 / 非 JSON 文本，
     * 直接 `JSONObject(body)` 会抛 JSONException。这里统一转成 null，
     * 让调用方按「本源本次无结果」处理并触发重试，而不是异常穿透。
     */
    private fun rootOf(body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrNull()

    private fun parseKw(body: String, limit: Int): List<OnlineTrack> {
        val root = rootOf(body) ?: return emptyList()
        val list = root.optJSONArray("abslist") ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        val mInfo = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val songId = item.optString("MUSICRID").removePrefix("MUSIC_")
            if (songId.isBlank()) continue
            val title = item.optString("SONGNAME").let(::decodeName)
            val artist = item.optString("ARTIST").let(::decodeName).replace(';', '、')
            val qualities = mutableListOf<Quality>()
            mInfo.findAll(item.optString("N_MINFO")).forEach { m ->
                when (m.groupValues[2]) {
                    "4000" -> qualities += Quality.FLAC24
                    "2000" -> qualities += Quality.FLAC
                    "320" -> qualities += Quality.Q320
                    "128" -> qualities += Quality.Q128
                }
            }
            val raw = JSONObject().apply {
                put("source", "kw")
                put("songmid", songId)
                put("name", title)
                put("singer", artist)
                put("albumId", decodeName(item.optString("ALBUMID")))
                put("albumName", decodeName(item.optString("ALBUM")))
                put("duration", item.optInt("DURATION"))
                put("types", qualities.toTypesJson())
            }
            out += trackOf("kw", songId, title, artist,
                decodeName(item.optString("ALBUM")), item.optInt("DURATION") * 1000L,
                qualities, raw)
            if (out.size >= limit) break
        }
        return out
    }

    // ---------------------------------------------------------------- 酷狗
    private fun getKg(keyword: String, page: Int, limit: Int): String {
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=${enc(keyword)}" +
            "&page=$page&pagesize=$limit&userid=0&clientver=&platform=WebFilter" +
            "&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
        return httpGet(url)
    }

    private fun parseKg(body: String): List<OnlineTrack> {
        val root = rootOf(body) ?: return emptyList()
        if (root.optInt("error_code", -1) != 0) return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            addKgItem(item, out, seen)
            item.optJSONArray("Grp")?.let { grp ->
                for (j in 0 until grp.length()) grp.optJSONObject(j)?.let { addKgItem(it, out, seen) }
            }
        }
        return out
    }

    private fun addKgItem(item: JSONObject, out: MutableList<OnlineTrack>, seen: MutableSet<String>) {
        val audioId = item.optString("Audioid")
        val hash = item.optString("FileHash")
        if (audioId.isBlank() || !seen.add(audioId + hash)) return
        val title = decodeName(item.optString("OriSongName")) +
            (item.optString("Suffix").takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        val artist = item.optJSONArray("Singers")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }.joinToString("、")
        } ?: ""
        val qualities = mutableListOf<Quality>()
        val hashes = JSONObject()
        if (item.optInt("FileSize") != 0) { qualities += Quality.Q128; hashes.put("128k", hash) }
        if (item.optInt("HQFileSize") != 0) {
            qualities += Quality.Q320; hashes.put("320k", item.optString("HQFileHash"))
        }
        if (item.optInt("SQFileSize") != 0) {
            qualities += Quality.FLAC; hashes.put("flac", item.optString("SQFileHash"))
        }
        if (item.optInt("ResFileSize") != 0) {
            qualities += Quality.FLAC24; hashes.put("flac24bit", item.optString("ResFileHash"))
        }
        val raw = JSONObject().apply {
            put("source", "kg")
            put("songmid", audioId)
            put("hash", hash)
            put("name", decodeName(title))
            put("singer", decodeName(artist))
            put("albumName", decodeName(item.optString("AlbumName")))
            put("albumId", item.optString("AlbumID"))
            put("duration", item.optInt("Duration"))
            put("types", qualities.toTypesJson(hashes))
        }
        out += trackOf("kg", audioId, decodeName(title), decodeName(artist),
            decodeName(item.optString("AlbumName")), item.optInt("Duration") * 1000L,
            qualities, raw, kgCover(item.optString("Image")))
    }

    /**
     * 酷狗搜索结果里的 `Image` 是一个**带 `{size}` 占位的完整 URL**
     * （实测 `http://imge.kugou.com/stdmusic/{size}/20250125/xxx.jpg`），
     * 直接当图片地址下载会拿到 404。替换成 480（洛雪的默认档）并升到 https。
     */
    internal fun kgCover(image: String?): String? {
        val raw = image?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withSize = if (raw.contains("{size}")) raw.replace("{size}", "480") else raw
        return withSize.replaceFirst("http://", "https://").takeIf { it.startsWith("http") }
    }

    // ---------------------------------------------------------------- QQ
    private fun getTx(keyword: String, page: Int, limit: Int): String {
        val data = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("_channelid", "0"); put("_os_version", "6.2.9200-2")
                put("ct", "19"); put("cv", "2151")
                put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
                put("patch", "118"); put("psrf_access_token_expiresAt", 0)
                put("psrf_qqaccess_token", ""); put("psrf_qqopenid", "")
                put("psrf_qqunionid", ""); put("tmeAppID", "qqmusic")
                put("tmeLoginType", 0); put("uin", "0")
                put("wid", "7223299733393904640")
            })
            put("music.search.SearchCgiService", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                put("param", JSONObject().apply {
                    put("grp", 1); put("num_per_page", limit)
                    put("page_num", page); put("query", keyword)
                    put("remoteplace", "txt.newclient.top")
                    put("search_type", 0)
                    put("searchid", txSearchId())
                })
            })
        }.toString()
        val sign = zzcSign(data)
        return httpPost(
            "https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sign",
            data,
            mapOf("User-Agent" to "QQMusic 14090508(android 12)"),
        )
    }

    /** 包内可见，供单测钉死 `req.data.body.song.list` 这一层结构 */
    internal fun parseTx(body: String): List<OnlineTrack> {
        val root = rootOf(body) ?: return emptyList()
        if (root.optInt("code", -1) != 0) return emptyList()
        val req = root.optJSONObject("music.search.SearchCgiService") ?: root.optJSONObject("req")
            ?: return emptyList()
        if (req.optInt("code", -1) != 0) return emptyList()
        // 洛雪：musicSearch 返回 req.data，search 里取 body.song.list —— 完整路径是
        // req → data → body → song → list。少一层 body 就是「QQ 永远搜不到」。
        val list = req.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("song")?.optJSONArray("list")
            ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val file = item.optJSONObject("file") ?: continue
            val mid = item.optString("mid")
            if (mid.isBlank()) continue
            val title = item.optString("title")
            val artist = item.optJSONArray("singer")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }.joinToString("、")
            } ?: ""
            val album = item.optJSONObject("album")
            val albumId = album?.optString("mid") ?: ""
            val qualities = mutableListOf<Quality>()
            if (file.optInt("size_128mp3") != 0) qualities += Quality.Q128
            if (file.optInt("size_320mp3") != 0) qualities += Quality.Q320
            if (file.optInt("size_flac") != 0) qualities += Quality.FLAC
            if (file.optInt("size_hires") != 0) qualities += Quality.FLAC24
            val cover = if (albumId.isBlank() || albumId == "空") {
                item.optJSONArray("singer")?.optJSONObject(0)?.optString("mid")?.let {
                    "https://y.gtimg.cn/music/photo_new/T001R500x500M000$it.jpg"
                } ?: ""
            } else "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumId.jpg"

            val raw = JSONObject().apply {
                put("source", "tx")
                put("songmid", mid)
                put("songId", item.opt("id"))
                put("strMediaMid", file.optString("media_mid"))
                put("albumMid", albumId)
                put("name", title)
                put("singer", artist)
                put("albumName", album?.optString("name") ?: "")
                put("interval", item.optInt("interval"))
                put("img", cover)
                put("types", qualities.toTypesJson())
            }
            out += trackOf("tx", mid, title, artist, album?.optString("name"),
                item.optLong("interval") * 1000L, qualities, raw, cover)
        }
        return out
    }

    /**
     * 洛雪 tx/utils/crypto.js 的 zzcSign：SHA1 → 定长取样 → XOR → base64 → 拼 `zzc` 前缀。
     *
     * ⚠️ 索引表里含 **40**，而 SHA-1 hex 只有 40 个字符（下标 0..39）。
     * 洛雪是 JS，`hash[40]` 得到 `undefined`、`[undefined].join('')` 时被吞成空串，
     * 于是 part1 实际只有 7 个字符 —— 这个「少一位」本身就是签名的一部分，
     * 补一个占位字符会让 QQ 返回 `code=2000`（参数错误）。
     * Kotlin 里直接 `hash[40]` 会抛 StringIndexOutOfBoundsException，被搜索重试吞掉后
     * 表现为「QQ 永远搜不到」——实测确认过，所以这里必须按 JS 语义**跳过越界下标**。
     */
    internal fun zzcSign(text: String): String {
        val p1 = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
        val p2 = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
        val scramble = intArrayOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
            186, 123, 120, 64, 242, 133, 143, 161, 121, 179)
        val hash = sha1Hex(text)
        fun pick(indexes: IntArray) = buildString {
            indexes.forEach { if (it in hash.indices) append(hash[it]) }
        }
        val a = pick(p1)
        val b = pick(p2)
        val mixed = ByteArray(scramble.size) { i ->
            (scramble[i] xor hash.substring(i * 2, i * 2 + 2).toInt(16)).toByte()
        }
        val mid = android.util.Base64.encodeToString(mixed, android.util.Base64.NO_WRAP)
            .filter { it != '\\' && it != '/' && it != '+' && it != '=' }
        return ("zzc$a$mid$b").lowercase()
    }

    // ---------------------------------------------------------------- 咪咕
    private fun getMg(keyword: String, page: Int, limit: Int): String {
        val time = System.currentTimeMillis().toString()
        val deviceId = "963B7AA0D21511ED807EE5846EC87D20"
        val sign = md5Hex("$keyword" +
            "6cdc72a439cef99a3418d2a78aa28c73" +
            "yyapp2d16148780a1dcc7408e06336b98cfd50$deviceId$time")
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=0" +
            "&isCopyright=1&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0" +
            "%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0" +
            "%2C%22lyricSong%22%3A0%7D&pageSize=$limit&text=${enc(keyword)}" +
            "&pageNo=$page&sort=0&sid=USS"
        return httpGet(url, mapOf(
            "uiVersion" to "A_music_3.6.1",
            "deviceId" to deviceId,
            "timestamp" to time,
            "sign" to sign,
            "channel" to "0146921",
            "User-Agent" to "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30",
        ))
    }

    private fun parseMg(body: String): List<OnlineTrack> {
        val root = rootOf(body) ?: return emptyList()
        if (root.optString("code") != "000000") return emptyList()
        val resultList = root.optJSONObject("songResultData")?.optJSONArray("resultList")
            ?: return emptyList()
        val out = mutableListOf<OnlineTrack>()
        val seen = HashSet<String>()
        for (i in 0 until resultList.length()) {
            val group = resultList.optJSONArray(i) ?: continue
            for (j in 0 until group.length()) {
                val d = group.optJSONObject(j) ?: continue
                val songId = d.optString("songId")
                val copyrightId = d.optString("copyrightId")
                if (songId.isBlank() || copyrightId.isBlank() || !seen.add(copyrightId)) continue
                val qualities = mutableListOf<Quality>()
                d.optJSONArray("audioFormats")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        when (arr.optJSONObject(k)?.optString("formatType")) {
                            "PQ" -> qualities += Quality.Q128
                            "HQ" -> qualities += Quality.Q320
                            "SQ" -> qualities += Quality.FLAC
                            "ZQ24" -> qualities += Quality.FLAC24
                        }
                    }
                }
                var img = d.optString("img3").ifBlank { d.optString("img2") }
                    .ifBlank { d.optString("img1") }
                if (img.isNotBlank() && !img.startsWith("http")) img = "https://d.musicapp.migu.cn$img"
                // 咪咕部分老条目给的是明文 http，实测只有 https 能取到图（http 直接 403）
                img = img.replaceFirst("http://", "https://")
                val artist = d.optJSONArray("singerList")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                        .filter { it.isNotBlank() }.joinToString("、")
                } ?: ""
                val raw = JSONObject().apply {
                    put("source", "mg")
                    put("songmid", songId)
                    put("copyrightId", copyrightId)
                    put("name", d.optString("name"))
                    put("singer", artist)
                    put("albumName", d.optString("album"))
                    put("albumId", d.optString("albumId"))
                    put("duration", d.optInt("duration"))
                    put("img", img)
                    put("lrcUrl", d.optString("lrcUrl"))
                    put("types", qualities.toTypesJson())
                }
                out += trackOf("mg", songId, d.optString("name"), artist,
                    d.optString("album"), parseDuration(d.opt("duration")),
                    qualities, raw, img)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 公共
    private fun trackOf(
        source: String,
        songId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        qualities: List<Quality>,
        raw: JSONObject,
        cover: String? = null,
    ): OnlineTrack = OnlineTrack(
        sourceId = source,
        songId = songId,
        title = title,
        artist = artist,
        album = album?.takeIf { it.isNotBlank() && it != "空" },
        durationMs = durationMs,
        // 脚本只声明了它支持的音质，实际可用档位取交集由 resolver 做；这里保留曲目自身档位
        qualities = qualities,
        raw = raw.toString(),
        // 空串归一成 null：下游 [OnlineCover] 只认「有地址」或「没地址」两态
        coverUrl = cover?.trim()?.takeIf { it.startsWith("http") },
    )

    private fun List<Quality>.toTypesJson(hashes: JSONObject? = null): JSONArray {
        val arr = JSONArray()
        for (q in this) {
            val o = JSONObject().put("type", q.id)
            if (hashes != null) o.put("hash", hashes.optString(q.id))
            arr.put(o)
        }
        return arr
    }

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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal fun decodeName(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    internal fun parseDuration(value: Any?): Long = when (value) {
        is Number -> value.toLong() * 1000L
        is String -> value.toLongOrNull()?.times(1000L) ?: 0L
        else -> 0L
    }

    private fun txSearchId(): String {
        val guid = (0 until 32).joinToString("") { (0..15).random().toString(16).uppercase() }
        return guid + (0..99999).random().toString().padStart(5, '0')
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
