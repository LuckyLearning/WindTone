package com.luckylearning.windtone.data.online

import android.content.Context
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.lyrics.LrcParser
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.lyrics.LyricSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 在线歌词：按音源分派到各家接口，统一产出 [Lyrics]。
 *
 * ## 为什么歌词由客户端取，而不是脚本
 * 洛雪 v2 自定义源的 `actions` 只有 `musicUrl`（实测 fish v1.9.0 五源全如此，见规划 §2.2.1），
 * 脚本**根本没有** `lyric` 动作。所以歌词必须和搜索一样由宿主内置实现 —— 这是「A 路线」的
 * 必然结果，也是它的好处：**脚本挂掉，歌词仍然能取**（脚本只负责直链）。
 *
 * ## 四家实现（2026-11-24 全部实测通过）
 *
 * | 源 | 接口 | 返回形态 |
 * |---|---|---|
 * | kw | `m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=` | JSON `data.lrclist[]`，time 是**秒** |
 * | kg | `lyrics.kugou.com/search` → `/download?fmt=lrc` | `content` 为 base64 LRC |
 * | tx | `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg` | JSON `lyric`（HTML 实体转义） |
 * | mg | 搜索结果里的 `lrcUrl` | 明文 LRC |
 *
 * 洛雪 kw 的活跃实现走 `mlyric.kuwo.cn/mobi.s` 并自行解密（`kw/util.js` 的 decodeLyric），
 * 这里改走新版的 JSON 接口：**明文、无加密、字段稳定**，省掉一整套解密链路。
 * 代价是它同时返回翻译行（同 time 多行），[kwLines] 已按洛雪的 `sortLrcArr` 语义
 * 取「同 time 的后者为主行、前者降为翻译」，不会出现两行同时高亮。
 *
 * ## 不做的事
 * - 不做字级（逐字）时间轴：全应用歌词精度只有逐行（红线 5），在线歌不破例。
 * - 不做歌词搜索/手选：取不到就是无歌词，交给播放页的「无歌词」态。
 */
object OnlineLyrics {

    private const val TAG = "OnlineLyrics"

    /** 单次取词上限。歌词属于「有更好、没有也能播」，不值得让用户等 */
    private const val TIMEOUT_MS = 6_000L
    /** 脚本取词的独立预算：脚本是可选路径，不能挤掉内置接口 */
    private const val SCRIPT_TIMEOUT_MS = 1_500L
    /** 首次落空后的快速重试间隔 */
    private const val RETRY_DELAY_MS = 400L
    /** 刚入库时另一个 store 实例可能还没看到文件落盘 */
    private const val TRACK_RECHECK_MS = 180L

    /** 内存缓存条目数。切歌来回切是常态，8 条足够覆盖一轮；只缓存**成功**结果 */
    private const val CACHE_SIZE = 8

    private val cache = object : LinkedHashMap<String, Lyrics>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>) =
            size > CACHE_SIZE
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 取一首在线歌的歌词。非在线歌一律返回 null（由 [com.luckylearning.windtone.data.LyricsLoader]
     * 走原有的侧车 / 内嵌链路）。
     */
    suspend fun fetch(context: Context, song: Song): Lyrics? {
        if (!song.isOnline) return null
        val key = song.stableKey
        synchronized(cache) { cache[key] }?.let { return it }

        val track = trackOfWithRetry(context, key) ?: return null
        val text = fetchTextWithRetry(track) ?: return null
        val lyrics = parse(text)
        if (lyrics.lines.isEmpty()) return null

        synchronized(cache) { cache[key] = lyrics }
        return lyrics
    }

    /**
     * 取源内原始信息。刚入库的那一瞬，另一个 [OnlineTrackStore] 实例可能还没看到
     * 文件落盘 —— 一次 180ms 的复查比把「暂时没有」当成「永远没有」便宜得多。
     */
    private suspend fun trackOfWithRetry(context: Context, key: String): OnlineTrack? {
        runCatching { OnlineLibrary(context).trackOf(key) }.getOrNull()?.let { return it }
        delay(TRACK_RECHECK_MS)
        return runCatching { OnlineLibrary(context).trackOf(key) }.getOrNull()
    }

    /**
     * 取词并做一次快速重试。
     *
     * 首次进入 / 切歌时冷启动的 DNS、TLS、脚本加载都可能让第一次落空；
     * 旧实现只取一次，于是 UI 停在「无歌词」，直到用户切走再切回。
     * 成功结果仍会进 [cache]，重试不会把失败也缓存住。
     */
    private suspend fun fetchTextWithRetry(track: OnlineTrack): String? {
        withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { fetchText(track) }.getOrNull()
        }?.takeIf { it.isNotBlank() }?.let { return it }
        delay(RETRY_DELAY_MS)
        return withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { fetchText(track) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    /** 供单测与诊断使用：清空缓存（切音源、改歌词偏移调试时） */
    fun clearCache() = synchronized(cache) { cache.clear() }

    // ---------------------------------------------------------------- 分派

    /**
     * 取词顺序：**先问脚本，再走内置**。
     *
     * 脚本若声明了 `lyric` 能力（v2 规范允许，虽然 fish 这类只做直链的源没有），
     * 它给的词通常和音源侧最贴（同一版本、同一时间轴）；脚本没声明该能力时
     * [com.luckylearning.windtone.data.online.OnlineResolver.lyric] 立即返回 null，
     * 不产生任何网络请求，所以这条前置检查是零成本的。
     */
    private suspend fun fetchText(track: OnlineTrack): String? {
        // 脚本只有声明 lyric 能力时才会真执行；给它 1.5s 预算，
        // 慢/卡不能把后面的内置接口一起拖过总超时（首次无词的另一条来路）。
        withTimeoutOrNull(SCRIPT_TIMEOUT_MS) { OnlineResolver.lyric(track) }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return when (track.sourceId) {
            "kw" -> getKw(track.songId)
            "kg" -> getKg(track)
            "tx" -> getTx(track.songId)
            "mg" -> getMg(track)
            else -> null
        }
    }

    /** 原始文本 → [Lyrics]。纯文本（无时间轴）也算有效，只是不能跟唱 */
    private fun parse(text: String): Lyrics {
        val body = text.trim().removePrefix("\uFEFF")
        if (body.isEmpty()) return Lyrics.EMPTY
        return if (LrcParser.hasTimeline(body)) {
            LrcParser.parse(body, LyricSource.ONLINE)
        } else {
            LrcParser.plainText(body, LyricSource.ONLINE)
        }
    }

    // ---------------------------------------------------------------- 酷我

    /**
     * `lrclist` 的 `time` 是秒（字符串，可带小数），需要自己拼成 LRC。
     * 同一时间出现两行是「原文 + 翻译」，洛雪的做法是：后者留作主行，前者顺延到下一个时间点当翻译。
     * 这里只保留主行 —— 本产品的歌词渲染只吃单行 + 可选 translation，
     * 而酷我这个接口的翻译质量不稳定（经常把版权信息也塞进来），留原文更稳。
     */
    private fun getKw(songId: String): String? {
        val url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$songId&httpsStatus=1"
        val body = httpGet(url) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = root.optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        val out = StringBuilder()
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val lyric = item.optString("lineLyric")
            if (lyric.isBlank()) continue
            val seconds = item.optString("time").toDoubleOrNull() ?: continue
            val stamp = formatStamp(seconds)
            // 同时间点只留第一行（sortLrcArr 的语义：后行覆盖前行，剩下的是翻译）
            if (!seen.add(stamp)) continue
            out.append(stamp).append(lyric).append('\n')
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    internal fun formatStamp(seconds: Double): String {
        val total = if (seconds < 0) 0.0 else seconds
        val m = (total / 60).toInt()
        val s = total - m * 60
        // 必须锁 Locale.US：默认区域在阿拉伯语/印地语环境会输出本地数字，
        // LRC 解析器只认 ASCII 数字，整份歌词会一行都定位不到
        return "[%02d:%05.2f]".format(java.util.Locale.US, m, s)
    }

    // ---------------------------------------------------------------- 酷狗

    /**
     * 两段式：`search` 拿候选（id + accesskey），`download` 拿 base64 LRC。
     * 候选可能几十条（同名不同版本），取第一条 —— 排序本身就是按 hash 精确匹配优先的。
     */
    private fun getKg(track: OnlineTrack): String? {
        val hash = runCatching { JSONObject(track.raw).optString("hash") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val keyword = URLEncoder.encode(track.title, "UTF-8")
        val duration = track.durationMs.takeIf { it > 0 } ?: 0L
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=$keyword&hash=$hash&timelength=$duration&lrctxt=1"
        val body = httpGet(searchUrl, KG_HEADERS) ?: return null
        val cands = runCatching { JSONObject(body).optJSONArray("candidates") }.getOrNull()
            ?: return null
        if (cands.length() == 0) return null
        val cand = cands.optJSONObject(0) ?: return null
        val id = cand.optString("id")
        val accessKey = cand.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return null
        val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val dl = httpGet(dlUrl, KG_HEADERS) ?: return null
        val content = runCatching { JSONObject(dl).optString("content") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    /** 酷狗歌词接口对 UA / 风控头很敏感，缺了会直接返回空 candidates */
    private val KG_HEADERS = mapOf(
        "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        "KG-RC" to "1",
        "KG-THash" to "expand_search_manager.cpp:852736169:451",
    )

    // ---------------------------------------------------------------- QQ

    /**
     * 经典 `fcg_query_lyric_new` 接口。`nobase64=1` 让它直接给明文 LRC，
     * 代价是内容按 HTML 实体转义（`&#58;` / `&apos;`），必须反转义后再交给解析器，
     * 否则一行里出现「&#58;」这种字面量。
     */
    private fun getTx(songMid: String): String? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$songMid&format=json&nobase64=1&g_tk=5381" +
            "&loginUin=0&hostUin=0&platform=yqq&needNewCode=0"
        val body = httpGet(url, mapOf("Referer" to "https://y.qq.com/")) ?: return null
        val lyric = runCatching { JSONObject(body).optString("lyric") }.getOrNull()
        if (lyric.isNullOrBlank()) return null
        return unescapeHtml(lyric)
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }

    // ---------------------------------------------------------------- 咪咕

    /**
     * 咪咕的搜索结果里直接带 `lrcUrl`（明文 LRC，实测可用），优先用它；
     * 老条目没有该字段时退回 `getLyric` 接口。
     */
    private fun getMg(track: OnlineTrack): String? {
        val raw = runCatching { JSONObject(track.raw) }.getOrNull()
        val lrcUrl = raw?.optString("lrcUrl")?.takeIf { it.startsWith("http") }
        if (lrcUrl != null) {
            httpGet(lrcUrl, MG_HEADERS)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val copyrightId = raw?.optString("copyrightId").orEmpty().ifBlank { track.songId }
        val url = "https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=$copyrightId"
        val body = httpGet(url, MG_HEADERS) ?: return null
        return runCatching { JSONObject(body).optString("lyrics") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private val MG_HEADERS = mapOf(
        "Referer" to "https://music.migu.cn/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36",
        "channel" to "0146921",
    )

    // ---------------------------------------------------------------- 网络

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "歌词请求失败 ${response.code}：$url")
                return@use null
            }
            response.body?.string()
        }
    }.onFailure { Log.w(TAG, "歌词请求异常：$url", it) }.getOrNull()

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
}
