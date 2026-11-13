package com.anylyric.lyrics.online

import android.os.Handler
import android.os.Looper
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LrcParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * 在线补词（**默认关闭**，只有用户在设置里打开才调用）。
 *
 * 本地三个通道（元数据 / 播放状态扩展 / 标题流 / 通知）全 miss 时，按「歌名 + 歌手」
 * 去酷狗、网易云找一份带时间轴的歌词。链路里多一次联网，所以这里把它做成
 * 「可选、异步、有界」的旁路，绝不阻塞主线程或刷新链路。
 *
 * 资源与长跑约束（这条链路最容易失控，硬性写死）：
 *  · **单线程**：同一时刻只有一个请求，不会因为频繁换歌堆出并发连接；
 *  · **总超时** [MAX_TOTAL_MS]：酷狗要三次 HTTP，任何一步超时前都检查总预算；
 *  · **缓存有界**：LRU [MAX_CACHE] 首，成功与失败都缓存 [CACHE_TTL_MS]，避免反复打同一首；
 *  · **响应有界**：单次响应超过 [MAX_RESPONSE_BYTES] 直接丢弃；
 *  · **可清空**：开关关闭时 [clear] 释放缓存，不留下常驻内存。
 */
object OnlineLyricsFetcher {

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000

    /** 一首歌的抓取总预算：两个平台加起来不超过这个数。 */
    private const val MAX_TOTAL_MS = 12_000L

    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_CACHE = 64

    /** 成功/失败都缓存 30 分钟：失败也缓存是为了避免对同一首反复请求。 */
    private const val CACHE_TTL_MS = 30 * 60_000L

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AnyLyric/1.0"

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anylyric-online").apply { isDaemon = true }
    }

    private class Entry(val lines: List<LyricLine>?, val atMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_CACHE
    }

    private var pendingKey: String? = null

    /**
     * 请求一首歌的在线歌词。
     *
     * @param callback 在主线程回调；只有成功才回调。调用方必须自己确认「还是同一首歌」。
     */
    fun request(
        title: String,
        artist: String,
        durationMs: Long,
        callback: (List<LyricLine>) -> Unit
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val cleanArtist = artist.trim()
        val key = "$cleanTitle\u0000$cleanArtist\u0000${durationMs / 1000}"

        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && System.currentTimeMillis() - hit.atMs < CACHE_TTL_MS) {
                hit.lines?.let { deliver(it, callback) }
                return
            }
            if (pendingKey == key) return
            pendingKey = key
        }

        executor.execute {
            val lines = runCatching { fetch(cleanTitle, cleanArtist, durationMs) }.getOrNull()
            synchronized(cache) {
                cache[key] = Entry(lines, System.currentTimeMillis())
                if (pendingKey == key) pendingKey = null
            }
            if (!lines.isNullOrEmpty()) deliver(lines, callback)
        }
    }

    /** 开关关闭时清空缓存，释放可能常驻的歌词对象。 */
    fun clear() {
        synchronized(cache) {
            cache.clear()
            pendingKey = null
        }
    }

    private fun deliver(lines: List<LyricLine>, callback: (List<LyricLine>) -> Unit) {
        main.post { callback(lines) }
    }

    private fun fetch(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val deadline = System.currentTimeMillis() + MAX_TOTAL_MS
        val keyword = if (artist.isEmpty()) title else "$title $artist"
        fetchKugou(keyword, title, durationMs, deadline)?.let { return it }
        fetchNetease(keyword, deadline)?.let { return it }
        return null
    }

    private fun fetchKugou(
        keyword: String,
        title: String,
        durationMs: Long,
        deadline: Long
    ): List<LyricLine>? {
        val search = httpGet(
            "https://mobileservice.kugou.com/api/v3/search/song" +
                "?format=json&keyword=${encode(keyword)}&page=1&pagesize=8",
            deadline - System.currentTimeMillis()
        ) ?: return null
        val (hash, durationSec) = OnlineLyricsParser.kugouSong(search) ?: return null

        val seconds = if (durationSec > 0) durationSec else durationMs / 1000
        val lyricSearch = httpGet(
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                "&keyword=${encode(title)}&duration=$seconds&hash=${encode(hash)}",
            deadline - System.currentTimeMillis()
        ) ?: return null
        val (id, accessKey) = OnlineLyricsParser.kugouCandidate(lyricSearch) ?: return null

        val download = httpGet(
            "https://lyrics.kugou.com/download?ver=1&client=pc" +
                "&id=${encode(id)}&accesskey=${encode(accessKey)}&fmt=lrc&charset=utf8",
            deadline - System.currentTimeMillis()
        ) ?: return null
        val lrc = OnlineLyricsParser.kugouContent(download) ?: return null
        return LrcParser.parse(lrc).ifEmpty { null }
    }

    private fun fetchNetease(keyword: String, deadline: Long): List<LyricLine>? {
        val search = httpGet(
            "https://music.163.com/api/search/get/web?s=${encode(keyword)}&type=1&limit=8&offset=0",
            deadline - System.currentTimeMillis()
        ) ?: return null
        val id = OnlineLyricsParser.neteaseSongId(search) ?: return null

        val lyric = httpGet(
            "https://music.163.com/api/song/lyric?id=${encode(id)}&lv=1&kv=1&tv=-1",
            deadline - System.currentTimeMillis()
        ) ?: return null
        val text = OnlineLyricsParser.neteaseLyric(lyric) ?: return null
        return LrcParser.parse(text).ifEmpty { null }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    /**
     * @param budgetMs 本次调用允许消耗的时间；按剩余预算收紧 connect/read 超时，
     *                 保证整首歌的抓取不会超过 [MAX_TOTAL_MS]（单次调用也只是消耗其中一段）。
     */
    private fun httpGet(url: String, budgetMs: Long): String? {
        if (budgetMs <= 0L) return null
        val timeout = budgetMs.coerceAtMost(READ_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(500)
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = minOf(CONNECT_TIMEOUT_MS, timeout)
            c.readTimeout = timeout
            c.requestMethod = "GET"
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream(16 * 1024)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    // 正常响应都是几十 KB；超过上限只可能是异常数据，直接丢弃。
                    if (total > MAX_RESPONSE_BYTES) return null
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
