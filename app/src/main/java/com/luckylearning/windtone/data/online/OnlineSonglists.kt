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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 平台在线歌单（洛雪 `nav_songlist`）：按分类浏览各家公开歌单，点进去拿歌单内的歌。
 *
 * 与「我的列表」的区别（**不要混**）：
 * - 这里是**别人做的歌单**，只能看与播放，用户想留下就「收进我的列表」；
 * - 「我的列表」是本机的 `Playlist` 表（我喜欢的 / 最近播放 / 自建），在 `LibraryLists` 里。
 *
 * 与自定义音源脚本的关系和榜单一样：脚本只放行 `musicUrl`，没有 list 能力，
 * 所以歌单的三层接口（分类 / 歌单列表 / 歌单详情）必须客户端内置。
 * 接口实现照洛雪 `src/utils/musicSdk/{kw,tx,kg,mg}/songlist.js`，
 * 但四家都做过直连实测（2026-11-24），不是照抄。
 */
object OnlineSonglists {

    private const val TAG = "OnlineSonglists"

    /** 一页歌单数。洛雪 kw 是 36，其余对齐同一观感 */
    private const val LIST_PAGE = 36

    /** 歌单详情一次取多少首。洛雪 kw/mg 支持 100，tx 一次性给全 */
    private const val DETAIL_PAGE = 100

    val SOURCES: List<String> = listOf("kw", "tx", "kg", "mg")

    data class Tag(val id: String, val name: String)

    data class TagGroup(val name: String, val tags: List<Tag>)

    data class Sheet(
        val source: String,
        val id: String,
        val name: String,
        val cover: String = "",
        val author: String = "",
        /** 已格式化成「1.2万 / 134.6万」，各家给的是不同量纲的裸数字 */
        val playCount: String = "",
        val total: Int = 0,
        val desc: String = "",
    ) {
        /** 列表 key（与曲目 stableKey 不同域，前缀 `sheet:` 避免撞车） */
        val key: String get() = "sheet:$source:$id"
    }

    data class SheetPage(val items: List<Sheet>, val page: Int, val hasMore: Boolean)

    data class Detail(val sheet: Sheet?, val tracks: List<OnlineTrack>)

    // 分类只请求一次：各家分类都是静态的，来回切平台不该每次重打接口
    private val tagCache = mutableMapOf<String, List<TagGroup>>()

    /**
     * 分类标签。空列表 = 这家暂时取不到（界面降级成「全部」一档）。
     */
    suspend fun tags(source: String): List<TagGroup> = withContext(Dispatchers.IO) {
        tagCache[source]?.let { return@withContext it }
        val groups = runCatching { fetchTags(source) }
            .onFailure { Log.w(TAG, "分类失败 $source: ${it.message}") }
            .getOrDefault(emptyList())
        if (groups.isNotEmpty()) tagCache[source] = groups
        groups
    }

    suspend fun list(source: String, tagId: String, page: Int): SheetPage = withContext(Dispatchers.IO) {
        runCatching { fetchList(source, tagId, page) }
            .onFailure { Log.w(TAG, "歌单列表失败 $source/$tagId#$page: ${it.message}") }
            .getOrDefault(SheetPage(emptyList(), page, false))
    }

    suspend fun detail(source: String, id: String, page: Int = 1): Detail = withContext(Dispatchers.IO) {
        runCatching { fetchDetail(source, id, page) }
            .onFailure { Log.w(TAG, "歌单详情失败 $source/$id: ${it.message}") }
            .getOrDefault(Detail(null, emptyList()))
    }

    // ---------------------------------------------------------------- 分类

    private fun fetchTags(source: String): List<TagGroup> = when (source) {
        "kw" -> parseKwTags(
            httpGet(KW_TAGS_URL),
        )
        "tx" -> parseTxTags(
            httpGet(txUrl("""{"tags":{"method":"get_all_categories","param":{"qq":""},"module":"playlist.PlaylistAllCategoriesServer"}}""")),
        )
        "kg" -> parseKgTags(httpGet(KG_TAGS_URL))
        "mg" -> parseMgTags(httpGet(MG_TAGS_URL, MG_HEADERS))
        else -> emptyList()
    }

    private const val KW_TAGS_URL =
        "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0" +
            "&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576"

    private const val KG_TAGS_URL = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&"

    private const val MG_TAGS_URL =
        "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release"

    /** 酷我：`data[]` 是分组，分组里 `data[]` 才是标签；tagId 形如 `173-10000`（id-digest） */
    internal fun parseKwTags(text: String): List<TagGroup> {
        val root = rootOf(text) ?: return emptyList()
        if (root.optInt("code") != 200) return emptyList()
        val groups = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<TagGroup>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val items = g.optJSONArray("data") ?: continue
            val tags = mutableListOf<Tag>()
            for (j in 0 until items.length()) {
                val t = items.optJSONObject(j) ?: continue
                val id = t.optString("id")
                val digest = t.optString("digest")
                // digest=43 是「专区」，走的是另一个接口（er.s），详情拿不到，
                // 留在界面上只会让用户点进去看空白，直接不收
                if (id.isBlank() || digest == "43") continue
                val tagId = if (digest.isBlank()) id else "$id-$digest"
                tags += Tag(tagId, BuiltInSearch.decodeName(t.optString("name")))
            }
            if (tags.isNotEmpty()) out += TagGroup(g.optString("name"), tags)
        }
        return out
    }

    /** QQ：`tags.data.v_group[]`，每组的 `v_item[]` 是标签 */
    internal fun parseTxTags(text: String): List<TagGroup> {
        val root = rootOf(text) ?: return emptyList()
        val groups = root.optJSONObject("tags")?.optJSONObject("data")?.optJSONArray("v_group")
            ?: return emptyList()
        val out = mutableListOf<TagGroup>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val items = g.optJSONArray("v_item") ?: continue
            val tags = mutableListOf<Tag>()
            for (j in 0 until items.length()) {
                val t = items.optJSONObject(j) ?: continue
                val id = t.optString("id")
                // id=0 是「全部」，category_id 传 0 会返回 2001，必须跳过
                if (id.isBlank() || id == "0" || id == "null") continue
                tags += Tag(id, BuiltInSearch.decodeName(t.optString("name")))
            }
            if (tags.isNotEmpty()) out += TagGroup(g.optString("group_name"), tags)
        }
        return out
    }

    /** 酷狗：`data.tagids` 是 {分组名: {id, data[]}}，标签 id 直接能用 */
    internal fun parseKgTags(text: String): List<TagGroup> {
        val tagids = rootOf(text)?.optJSONObject("data")?.optJSONObject("tagids") ?: return emptyList()
        val out = mutableListOf<TagGroup>()
        val names = tagids.keys()
        while (names.hasNext()) {
            val groupName = names.next()
            val items = tagids.optJSONObject(groupName)?.optJSONArray("data") ?: continue
            val tags = mutableListOf<Tag>()
            for (j in 0 until items.length()) {
                val t = items.optJSONObject(j) ?: continue
                val id = t.optString("id")
                if (id.isBlank()) continue
                tags += Tag(id, BuiltInSearch.decodeName(t.optString("name")))
            }
            if (tags.isNotEmpty()) out += TagGroup(groupName, tags)
        }
        return out
    }

    /** 咪咕：只有一个平铺列表，取 `texts[]` 的第 0 个名字 / 第 1 个 id */
    internal fun parseMgTags(text: String): List<TagGroup> {
        val root = rootOf(text) ?: return emptyList()
        if (root.optString("code") != "000000") return emptyList()
        val groups = root.optJSONArray("data") ?: return emptyList()
        val tags = mutableListOf<Tag>()
        for (i in 0 until groups.length()) {
            val items = groups.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until items.length()) {
                val texts = items.optJSONObject(j)?.optJSONArray("texts") ?: continue
                if (texts.length() < 2) continue
                val name = texts.optString(0)
                val id = texts.optString(1)
                if (id.isBlank()) continue
                tags += Tag(id, BuiltInSearch.decodeName(name))
            }
        }
        return if (tags.isEmpty()) emptyList() else listOf(TagGroup("分类", tags))
    }

    // ---------------------------------------------------------------- 歌单列表

    private fun fetchList(source: String, tagId: String, page: Int): SheetPage = when (source) {
        "kw" -> {
            // tagId 形如 `173-10000`，接口只要前半段 id
            val id = tagId.substringBefore('-')
            parseKwSheets(
                httpGet(
                    "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?loginUid=0&loginSid=0" +
                        "&appUid=76039576&pn=$page&id=$id&rn=$LIST_PAGE",
                ),
                page,
            )
        }
        "tx" -> {
            val body = """{"comm":{"cv":1602,"ct":20},"playlist":{"method":"get_category_content",""" +
                """"param":{"titleid":${tagId.toIntOrNull() ?: 0},"caller":"0","category_id":${tagId.toIntOrNull() ?: 0},""" +
                """"size":$LIST_PAGE,"page":${page - 1},"use_page":1},"module":"playlist.PlayListCategoryServer"}}"""
            parseTxSheets(httpGet(txUrl(body)), page)
        }
        "kg" -> parseKgSheets(
            httpGet("http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=5&c=$tagId&p=$page"),
            page,
        )
        "mg" -> parseMgSheets(
            httpGet(
                "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-listbytag/release" +
                    "?pageNumber=$page&templateVersion=2&tagId=$tagId",
                MG_HEADERS,
            ),
            page,
        )
        else -> SheetPage(emptyList(), page, false)
    }

    internal fun parseKwSheets(text: String, page: Int): SheetPage {
        val root = rootOf(text) ?: return SheetPage(emptyList(), page, false)
        if (root.optInt("code") != 200) return SheetPage(emptyList(), page, false)
        val data = root.optJSONObject("data")
        val list = data?.optJSONArray("data") ?: return SheetPage(emptyList(), page, false)
        val total = data.optInt("total")
        val out = mutableListOf<Sheet>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val digest = item.optString("digest")
            // digest=8 是平台歌单，详情走 pl.svc；5/13 是用户列表 / 专辑，详情要另找接口
            if (id.isBlank() || (digest.isNotBlank() && digest != "8")) continue
            out += Sheet(
                source = "kw",
                id = id,
                name = BuiltInSearch.decodeName(item.optString("name")),
                // 协议按图床主机归一：kwcdn 必须留在 http，img*.kuwo.cn 升到 https
                cover = OnlineCoverUrls.normalizeImage(item.optString("img")).orEmpty(),
                author = BuiltInSearch.decodeName(item.optString("uname")),
                playCount = formatCount(item.optLong("listencnt")),
                total = item.optInt("total"),
                desc = BuiltInSearch.decodeName(item.optString("desc")),
            )
        }
        val hasMore = total > 0 && page * LIST_PAGE < total
        return SheetPage(out, page, hasMore)
    }

    internal fun parseTxSheets(text: String, page: Int): SheetPage {
        val root = rootOf(text) ?: return SheetPage(emptyList(), page, false)
        val content = root.optJSONObject("playlist")?.optJSONObject("data")?.optJSONObject("content")
            ?: return SheetPage(emptyList(), page, false)
        val total = content.optInt("total_cnt")
        val list = content.optJSONArray("v_item") ?: return SheetPage(emptyList(), page, false)
        val out = mutableListOf<Sheet>()
        for (i in 0 until list.length()) {
            val basic = list.optJSONObject(i)?.optJSONObject("basic") ?: continue
            val id = basic.optString("tid")
            if (id.isBlank()) continue
            val cover = basic.optJSONObject("cover")
            out += Sheet(
                source = "tx",
                id = id,
                name = BuiltInSearch.decodeName(basic.optString("title")),
                cover = cover?.optString("medium_url").orEmpty()
                    .ifBlank { cover?.optString("default_url").orEmpty() }
                    .ifBlank { cover?.optString("big_url").orEmpty() },
                author = BuiltInSearch.decodeName(
                    basic.optJSONObject("creator")?.optString("nick").orEmpty(),
                ),
                playCount = formatCount(basic.optLong("play_cnt")),
                desc = BuiltInSearch.decodeName(basic.optString("desc")).replace("<br>", "\n"),
            )
        }
        val hasMore = total > 0 && page * LIST_PAGE < total
        return SheetPage(out, page, hasMore)
    }

    internal fun parseKgSheets(text: String, page: Int): SheetPage {
        val root = rootOf(text) ?: return SheetPage(emptyList(), page, false)
        if (root.optInt("status") != 1) return SheetPage(emptyList(), page, false)
        val list = root.optJSONArray("special_db") ?: return SheetPage(emptyList(), page, false)
        val out = mutableListOf<Sheet>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("specialid")
            if (id.isBlank()) continue
            out += Sheet(
                source = "kg",
                id = id,
                name = BuiltInSearch.decodeName(item.optString("specialname")),
                // 酷狗封面模板里带 {size} 占位，不替换会拿到 0 字节图
                cover = item.optString("img").replace("{size}", "480")
                    .replaceFirst("http://", "https://"),
                author = BuiltInSearch.decodeName(item.optString("nickname")),
                playCount = item.optString("total_play_count"),
                total = item.optInt("song_count"),
                desc = BuiltInSearch.decodeName(item.optString("intro")),
            )
        }
        // 酷狗不给总数，返回整页就当作还有下一页
        return SheetPage(out, page, out.size >= 10)
    }

    internal fun parseMgSheets(text: String, page: Int): SheetPage {
        val root = rootOf(text) ?: return SheetPage(emptyList(), page, false)
        if (root.optString("code") != "000000") return SheetPage(emptyList(), page, false)
        val blocks = root.optJSONObject("data")?.optJSONArray("contentItemList")
            ?: return SheetPage(emptyList(), page, false)
        val out = mutableListOf<Sheet>()
        for (i in 0 until blocks.length()) {
            val items = blocks.optJSONObject(i)?.optJSONArray("itemList") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                // actionUrl 形如 mgmusic://song-list-info?id=211267917&logId=...
                val id = Regex("""[?&]id=(\d+)""").find(item.optString("actionUrl"))
                    ?.groupValues?.get(1).orEmpty()
                if (id.isBlank()) continue
                out += Sheet(
                    source = "mg",
                    id = id,
                    name = BuiltInSearch.decodeName(item.optString("title")),
                    cover = item.optString("imageUrl").replaceFirst("http://", "https://"),
                    author = BuiltInSearch.decodeName(item.optString("subTitle")),
                    playCount = item.optJSONArray("barList")?.optJSONObject(0)?.optString("title").orEmpty(),
                )
            }
        }
        val hasMore = root.optJSONObject("data")?.optString("nextPageUrl")?.isNotBlank() == true
        return SheetPage(out, page, hasMore)
    }

    // ---------------------------------------------------------------- 歌单详情

    private fun fetchDetail(source: String, id: String, page: Int): Detail = when (source) {
        "kw" -> parseKwDetail(
            httpGet(
                "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$id&pn=${page - 1}&rn=$DETAIL_PAGE" +
                    "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1",
            ),
        )
        "tx" -> parseTxDetail(
            httpGet(
                "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1" +
                    "&onlysong=0&new_format=1&disstid=$id&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
                    "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
                mapOf("Referer" to "https://y.qq.com/"),
            ),
        )
        "kg" -> parseKgDetail(
            httpGet("http://www2.kugou.kugou.com/yueku/v9/special/single/$id-5-9999.html"),
        )
        "mg" -> parseMgDetail(
            httpGet(
                "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0" +
                    "?pageNo=$page&pageSize=$DETAIL_PAGE&playlistId=$id",
                MG_HEADERS,
            ),
        )
        else -> Detail(null, emptyList())
    }

    internal fun parseKwDetail(text: String): Detail {
        val root = rootOf(text) ?: return Detail(null, emptyList())
        if (root.optString("result") != "ok") return Detail(null, emptyList())
        val info = Sheet(
            source = "kw",
            id = root.optString("id"),
            name = BuiltInSearch.decodeName(root.optString("title")),
            cover = root.optString("pic").replaceFirst("http://", "https://"),
            author = BuiltInSearch.decodeName(root.optString("uname")),
            playCount = formatCount(root.optLong("playnum")),
            total = root.optInt("total"),
            desc = BuiltInSearch.decodeName(root.optString("info")),
        )
        val list = root.optJSONArray("musiclist") ?: return Detail(info, emptyList())
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val songId = item.optString("id")
            if (songId.isBlank()) continue
            val title = BuiltInSearch.decodeName(item.optString("name"))
            val artist = BuiltInSearch.decodeName(item.optString("artist"))
            val album = BuiltInSearch.decodeName(item.optString("album"))
            val qualities = kwQualities(item.optString("N_MINFO"))
            val types = JSONArray()
            qualities.forEach { types.put(JSONObject().put("type", it.id)) }
            val raw = JSONObject().apply {
                put("source", "kw")
                put("songmid", songId)
                put("name", title)
                put("singer", artist)
                put("albumId", item.optString("albumid"))
                put("albumName", album)
                put("duration", item.optInt("duration"))
                put("types", types)
            }
            out += OnlineTrack(
                sourceId = "kw",
                songId = songId,
                title = title,
                artist = artist,
                album = album.takeIf { it.isNotBlank() && it != "null" },
                durationMs = item.optLong("duration") * 1000L,
                qualities = qualities,
                raw = raw.toString(),
                coverUrl = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$songId",
            )
        }
        return Detail(info, out)
    }

    /** 酷我 `N_MINFO`：`level:p,bitrate:320,format:mp3,size:9.1Mb;...`，按 bitrate 定档 */
    private fun kwQualities(minfo: String): List<Quality> {
        if (minfo.isBlank()) return listOf(Quality.Q128)
        val found = LinkedHashSet<Quality>()
        for (seg in minfo.split(';')) {
            val bitrate = Regex("""bitrate:(\d+)""").find(seg)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            when (bitrate) {
                4000 -> found += Quality.FLAC24
                2000, 1000 -> found += Quality.FLAC
                320 -> found += Quality.Q320
                128 -> found += Quality.Q128
            }
        }
        // 只报 ogg/特殊码率的曲目（bitrate 192/100 之类）也要能播，兜底给 128k
        if (found.isEmpty()) found += Quality.Q128
        return found.toList()
    }

    internal fun parseTxDetail(text: String): Detail {
        val root = rootOf(text) ?: return Detail(null, emptyList())
        val cd = root.optJSONArray("cdlist")?.optJSONObject(0) ?: return Detail(null, emptyList())
        val info = Sheet(
            source = "tx",
            id = cd.optString("disstid"),
            name = BuiltInSearch.decodeName(cd.optString("dissname")),
            cover = cd.optString("logo").replaceFirst("http://", "https://"),
            author = BuiltInSearch.decodeName(cd.optString("nickname")),
            playCount = formatCount(cd.optLong("visitnum")),
            total = cd.optInt("songnum"),
            desc = BuiltInSearch.decodeName(cd.optString("desc")).replace("<br>", "\n"),
        )
        val list = cd.optJSONArray("songlist") ?: return Detail(info, emptyList())
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val mid = item.optString("mid")
            if (mid.isBlank()) continue
            val title = BuiltInSearch.decodeName(item.optString("name"))
            val singerArr = item.optJSONArray("singer")
            val artist = (0 until (singerArr?.length() ?: 0))
                .mapNotNull { singerArr?.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }
                .joinToString("、")
            val albumObj = item.optJSONObject("album")
            val album = BuiltInSearch.decodeName(albumObj?.optString("name").orEmpty())
            // 洛雪对 QQ 只用 128k/320k/flac 三档，这里保持同样顺序让脚本按档取
            val qualities = listOf(Quality.Q320, Quality.Q128)
            val raw = JSONObject().apply {
                put("source", "tx")
                put("songmid", mid)
                put("name", title)
                put("singer", artist)
                put("albumId", albumObj?.optString("id").orEmpty())
                put("albumMid", albumObj?.optString("mid").orEmpty())
                put("albumName", album)
                put("duration", item.optInt("interval"))
                put("types", JSONArray().apply {
                    qualities.forEach { put(JSONObject().put("type", it.id)) }
                })
            }
            out += OnlineTrack(
                sourceId = "tx",
                songId = mid,
                title = title,
                artist = artist,
                album = album.takeIf { it.isNotBlank() },
                durationMs = item.optLong("interval") * 1000L,
                qualities = qualities,
                raw = raw.toString(),
                coverUrl = albumObj?.optString("mid").orEmpty().let { albumMid ->
                    if (albumMid.isBlank()) "" else
                        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
                },
            )
        }
        return Detail(info, out)
    }

    internal fun parseKgDetail(text: String): Detail {
        // 酷狗歌单详情是 HTML，歌单数据以 `global.data = [...]` 内嵌在页面里（洛雪同样用正则取）
        val dataMatch = Regex("""global\.data = (\[[\s\S]+?\]);""").find(text)
            ?: return Detail(null, emptyList())
        val list = runCatching { JSONArray(dataMatch.groupValues[1]) }.getOrNull()
            ?: return Detail(null, emptyList())
        val infoMatch = Regex("""global = \{[\s\S]+?name: "(.+)"[\s\S]+?pic: "(.+)"[\s\S]+?\};""")
            .find(text)
        val info = Sheet(
            source = "kg",
            id = "",
            name = BuiltInSearch.decodeName(infoMatch?.groupValues?.get(1).orEmpty()),
            cover = infoMatch?.groupValues?.get(2).orEmpty().replaceFirst("http://", "https://"),
            total = list.length(),
        )
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val hash = item.optString("hash")
            if (hash.isBlank()) continue
            val title = BuiltInSearch.decodeName(item.optString("songname"))
            val artist = BuiltInSearch.decodeName(item.optString("singername"))
            val album = BuiltInSearch.decodeName(item.optString("album_name"))
            val qualities = mutableListOf<Quality>()
            val types = JSONArray()
            if (item.optLong("filesize") > 0) {
                qualities += Quality.Q128
                types.put(JSONObject().put("type", Quality.Q128.id).put("hash", hash))
            }
            val hash320 = item.optString("hash_320")
            if (item.optLong("filesize_320") > 0 && hash320.isNotBlank()) {
                qualities += Quality.Q320
                types.put(JSONObject().put("type", Quality.Q320.id).put("hash", hash320))
            }
            val flacHash = item.optString("hash_flac")
            if (item.optLong("filesize_flac") > 0 && flacHash.isNotBlank()) {
                qualities += Quality.FLAC
                types.put(JSONObject().put("type", Quality.FLAC.id).put("hash", flacHash))
            }
            if (qualities.isEmpty()) continue
            val raw = JSONObject().apply {
                put("source", "kg")
                put("hash", hash)
                put("name", title)
                put("singer", artist)
                put("albumId", item.optString("album_id"))
                put("albumName", album)
                // 酷狗 duration 是毫秒，脚本按洛雪习惯乘以 1000 会差 1000 倍，这里统一给秒
                put("duration", item.optLong("duration") / 1000L)
                put("types", types)
            }
            out += OnlineTrack(
                sourceId = "kg",
                songId = hash,
                title = title,
                artist = artist,
                album = album.takeIf { it.isNotBlank() },
                durationMs = item.optLong("duration"),
                qualities = qualities,
                raw = raw.toString(),
            )
        }
        return Detail(info, out)
    }

    internal fun parseMgDetail(text: String): Detail {
        val root = rootOf(text) ?: return Detail(null, emptyList())
        if (root.optString("code") != "000000") return Detail(null, emptyList())
        val list = root.optJSONObject("data")?.optJSONArray("songList")
            ?: return Detail(null, emptyList())
        val out = mutableListOf<OnlineTrack>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val copyrightId = item.optString("copyrightId")
            if (copyrightId.isBlank()) continue
            val title = BuiltInSearch.decodeName(item.optString("songName"))
            val singerArr = item.optJSONArray("singerList")
            val artist = (0 until (singerArr?.length() ?: 0))
                .mapNotNull { singerArr?.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }
                .joinToString("、")
            val album = BuiltInSearch.decodeName(item.optString("album"))
            val img = item.optString("img3").let {
                if (it.isBlank()) "" else "https://d.musicapp.migu.cn${it.replaceFirst("http://", "")}"
                    .replace("https://d.musicapp.migu.cnhttps://", "https://")
            }
            val qualities = listOf(Quality.Q320, Quality.Q128)
            val raw = JSONObject().apply {
                put("source", "mg")
                put("copyrightId", copyrightId)
                put("name", title)
                put("singer", artist)
                put("albumName", album)
                put("duration", item.optLong("duration"))
                put("lrcUrl", item.optString("lrcUrl"))
                put("types", JSONArray().apply {
                    qualities.forEach { put(JSONObject().put("type", it.id)) }
                })
            }
            out += OnlineTrack(
                sourceId = "mg",
                songId = copyrightId,
                title = title,
                artist = artist,
                album = album.takeIf { it.isNotBlank() },
                durationMs = item.optLong("duration") * 1000L,
                qualities = qualities,
                raw = raw.toString(),
                coverUrl = img,
            )
        }
        return Detail(Sheet(source = "mg", id = "", name = "", total = out.size), out)
    }

    // ---------------------------------------------------------------- 工具

    /** QQ 的接口要求 `data` 是一段 URL 编码后的 JSON，且签名/参数要紧凑无空格 */
    private fun txUrl(dataJson: String): String =
        "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8" +
            "&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=" +
            URLEncoder.encode(dataJson, "UTF-8")

    /** 「1.2万 / 134.6万 / 1.3亿」，四家给的量纲不同，统一成中文习惯 */
    internal fun formatCount(num: Long): String = when {
        num <= 0 -> ""
        num >= 100_000_000 -> "%.1f亿".format(num / 100_000_000.0).replace(".0亿", "亿")
        num >= 10_000 -> "%.1f万".format(num / 10_000.0).replace(".0万", "万")
        else -> num.toString()
    }

    private fun rootOf(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

    private val MG_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1",
        "Referer" to "https://m.music.migu.cn/",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.body?.string().orEmpty() }
    }

    /** 咪咕歌单详情走 POST 时用（当前实现只用 GET，保留给后续分页） */
    @Suppress("unused")
    private fun httpPost(url: String, body: String, headers: Map<String, String>): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(
            b.post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())).build(),
        ).execute().use { it.body?.string().orEmpty() }
    }
}
