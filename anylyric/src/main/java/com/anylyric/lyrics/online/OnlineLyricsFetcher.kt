package com.anylyric.lyrics.online

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anylyric.lyrics.LrcParser
import com.anylyric.lyrics.LyricLine
import com.anylyric.lyrics.LyricMatch
import com.anylyric.lyrics.LyricStore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 在线补词：本地通道全 miss 时，按「歌名 + 歌手」去酷狗、网易云找一份带时间轴的歌词。
 *
 * 这里决定「歌词稳不稳」，四个决定都是一次真机教训：
 *
 *  1. **先查盘、再联网**（[LyricStore]）：取到过一次就永久可用。只有内存缓存时，
 *     每次冷启动都要重新联网，网络抖一下就是「暂无歌词」。
 *  2. **两家并行**（[net] 两个线程）：串行时最坏要等 12 秒，车机上就是「半天不出词」；
 *     并行取先回来的那份，另一份立刻取消，正常 1–2 秒出词。
 *  3. **按分数挑，不取第一条**（[LyricMatch]）：搜索是关键词匹配，第一条经常是翻唱、
 *     剪辑版或同名别的歌。宁可判「没找到」让上层重试，也不要滚别人的词。
 *  4. **失败只记 45 秒**：以前失败也缓存 30 分钟，一次网络抖动会把整首歌锁成「无词」。
 *
 * 资源约束：单次请求单线程调度（[worker]），并发上限 2 个 HTTP；响应有界；
 * 总预算 [MAX_TOTAL_MS]；内存缓存 LRU 有界。
 */
object OnlineLyricsFetcher {

    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 4_000

    /** 一次取词的总预算：两家并行，谁先回来用谁。 */
    private const val MAX_TOTAL_MS = 9_000L

    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_CACHE = 24

    /** 成功结果在内存里留 30 分钟；更久的那份在磁盘上，查盘比联网便宜得多。 */
    private const val OK_TTL_MS = 30 * 60_000L

    /** 失败只挡 45 秒：够挡住「同一秒内反复触发」，又不至于把整首歌判死。 */
    private const val FAIL_TTL_MS = 45_000L

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AnyLyric/1.0"

    private val main = Handler(Looper.getMainLooper())

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anylyric-online").apply { isDaemon = true }
    }

    private val net = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "anylyric-fetch").apply { isDaemon = true }
    }

    private class Entry(val lines: List<LyricLine>?, val atMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_CACHE
    }

    private var pendingKey: String? = null

    /** 让磁盘缓存拿到 files 目录；SessionHub 初始化时调用一次即可。 */
    fun bind(context: Context) = LyricStore.bind(context)

    /**
     * 请求一首歌的在线歌词。
     *
     * @param onResult 在主线程回调，只在拿到**非空**歌词时调用。
     * @param onMiss   在主线程回调，表示这首歌这一轮没取到（可能已落盘失败缓存）。
     *                 调用方据此安排重试，不要把它当成「永远没有」。
     */
    fun request(
        title: String,
        artist: String,
        durationMs: Long,
        onResult: (List<LyricLine>) -> Unit,
        onMiss: () -> Unit = {}
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            main.post(onMiss)
            return
        }
        val cleanArtist = artist.trim()
        val key = LyricStore.key(cleanTitle, cleanArtist)
        val now = System.currentTimeMillis()

        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && now - hit.atMs < if (hit.lines == null) FAIL_TTL_MS else OK_TTL_MS) {
                val lines = hit.lines
                if (lines != null) main.post { onResult(lines) } else main.post(onMiss)
                return
            }
            // 同一首歌正在取：不重复发起，也不回调 —— 上层下一轮刷新会再进来。
            if (pendingKey == key) return
            pendingKey = key
        }

        worker.execute {
            val lines = runCatching { resolve(cleanTitle, cleanArtist, key, durationMs) }.getOrNull()
            synchronized(cache) {
                cache[key] = Entry(lines, System.currentTimeMillis())
                if (pendingKey == key) pendingKey = null
            }
            main.post {
                if (lines.isNullOrEmpty()) onMiss() else onResult(lines)
            }
        }
    }

    /** 开关关闭时清空内存与磁盘缓存。 */
    fun clear() {
        synchronized(cache) {
            cache.clear()
            pendingKey = null
        }
        LyricStore.clear()
    }

    // ---------------------------------------------------------------- 取词

    private fun resolve(
        title: String,
        artist: String,
        key: String,
        durationMs: Long
    ): List<LyricLine>? {
        // 1. 磁盘：第二次听同一首歌永远走这里，不联网。
        val cached = LyricStore.load(key)
        if (cached != null) {
            val parsed = LrcParser.parse(cached)
            if (parsed.isNotEmpty()) return parsed
        }
        // 2. 网络：两家并行。
        val lines = fetchAll(title, artist, durationMs / 1000L) ?: return null
        if (lines.isNotEmpty()) LyricStore.save(key, LrcParser.build(lines))
        return lines
    }

    private fun fetchAll(title: String, artist: String, targetSec: Long): List<LyricLine>? {
        val completion = ExecutorCompletionService<List<LyricLine>?>(net)
        val kugou = completion.submit(Callable { fetchKugou(title, artist, targetSec) })
        val netease = completion.submit(Callable { fetchNetease(title, artist, targetSec) })
        val deadline = System.currentTimeMillis() + MAX_TOTAL_MS
        var result: List<LyricLine>? = null
        var received = 0
        while (received < 2 && result == null) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            val future = completion.poll(remain, TimeUnit.MILLISECONDS) ?: break
            received++
            val lines = runCatching { future.get() }.getOrNull()
            if (!lines.isNullOrEmpty()) result = lines
        }
        // 已经拿到就不要再等另一家：它的 socket 会自己超时结束。
        kugou.cancel(true)
        netease.cancel(true)
        return result
    }

    private fun fetchKugou(title: String, artist: String, targetSec: Long): List<LyricLine>? {
        for (query in LyricMatch.queries(title, artist)) {
            val search = httpGet(
                "https://mobileservice.kugou.com/api/v3/search/song" +
                    "?format=json&keyword=${encode(query)}&page=1&pagesize=10",
                MAX_TOTAL_MS
            ) ?: continue
            val song = pickBest(
                OnlineLyricsParser.kugouSongs(search), title, artist, targetSec
            ) { Triple(it.title, it.artist, it.durationSec) } ?: continue

            val seconds = if (song.durationSec > 0) song.durationSec else targetSec
            val lyricSearch = httpGet(
                "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                    "&keyword=${encode(song.title.ifBlank { title })}" +
                    "&duration=$seconds&hash=${encode(song.id)}",
                MAX_TOTAL_MS
            ) ?: continue
            val candidate = pickBest(
                OnlineLyricsParser.kugouLyricCandidates(lyricSearch), title, artist, targetSec
            ) { Triple(it.title, it.artist, it.durationSec) } ?: continue

            val download = httpGet(
                "https://lyrics.kugou.com/download?ver=1&client=pc" +
                    "&id=${encode(candidate.id)}&accesskey=${encode(candidate.accessKey)}" +
                    "&fmt=lrc&charset=utf8",
                MAX_TOTAL_MS
            ) ?: continue
            val lrc = OnlineLyricsParser.kugouContent(download) ?: continue
            val lines = LrcParser.parse(lrc)
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    private fun fetchNetease(title: String, artist: String, targetSec: Long): List<LyricLine>? {
        for (query in LyricMatch.queries(title, artist)) {
            val search = httpGet(
                "https://music.163.com/api/search/get/web" +
                    "?s=${encode(query)}&type=1&limit=10&offset=0",
                MAX_TOTAL_MS
            ) ?: continue
            val song = pickBest(
                OnlineLyricsParser.neteaseSongs(search), title, artist, targetSec
            ) { Triple(it.title, it.artist, it.durationSec) } ?: continue

            val lyric = httpGet(
                "https://music.163.com/api/song/lyric?id=${encode(song.id)}&lv=1&kv=1&tv=-1",
                MAX_TOTAL_MS
            ) ?: continue
            val text = OnlineLyricsParser.neteaseLyric(lyric) ?: continue
            val lines = LrcParser.parse(text)
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    /**
     * 在候选里按 [LyricMatch.score] 取最高分，且必须过 [LyricMatch.SCORE_FLOOR]。
     *
     * 注意这里返回 null 的含义是「候选都不像这首歌」，而不是「接口没返回」——
     * 两者在上层都表现为一次 miss，但原因会写进探测日志。
     */
    private fun <T> pickBest(
        candidates: List<T>,
        title: String,
        artist: String,
        targetSec: Long,
        fields: (T) -> Triple<String, String, Long>
    ): T? {
        var best: T? = null
        var bestScore = 0
        for (candidate in candidates) {
            val (cTitle, cArtist, cDur) = fields(candidate)
            val score = LyricMatch.score(cTitle, cArtist, cDur, title, artist, targetSec)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return if (best != null && LyricMatch.acceptable(bestScore)) best else null
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    /**
     * @param budgetMs 本次调用允许消耗的时间；按剩余预算收紧 connect/read 超时。
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
