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
import java.util.concurrent.Executors

/**
 * 在线补词：本地通道全 miss 时，按「歌名 + 歌手」去酷狗、网易云、汽水找一份歌词。
 *
 * 0.2.24 起**按 Cardash（`g/R3`）的链路重排**。它取词稳，靠的是四条结构与顺序，
 * 而不是某个接口：
 *
 *  1. **三家串行、优先级固定**（酷狗 → 网易云 → 汽水）。旧实现是酷狗与网易并行、
 *     谁先回来用谁 —— 网络快的那家未必是匹配最准的那家，于是同一首歌会在
 *     「词很准」和「整首差半句」之间反复横跳。顺序固定后行为可预期，
 *     也从根上减少「当前行对不上」。
 *  2. **先筛后排**：候选必须过 [LyricMatch] 的标题判定，再按时长挑最接近的。
 *     没有总分门槛 —— Cardash 的取舍是「宁可时间轴略偏，也不给空屏」。
 *  3. **响应结构不写死**：三家字段名各不相同，统一用
 *     [OnlineLyricsParser.candidates] 的「有标题、有 id 就是候选」规则吃下来。
 *  4. **纯文本也能用**：解析不出时间轴时按 2 秒一句铺开（[LrcParser.spread]），
 *     而不是当「没取到」。
 *
 * 缓存与重试保持原样：先查盘（[LyricStore]）、成功后落盘、失败只挡 45 秒，
 * 上层有 4 次退避重试 + 网络恢复清零。
 */
object OnlineLyricsFetcher {

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000

    /** 一次取词的总预算。三家串行，任何一家超时就换下一家，不会无限等。 */
    private const val MAX_TOTAL_MS = 15_000L

    /** 每家最多试几个关键词：完整「歌名 歌手」+ 纯歌名，再多只是把等待时间拉长。 */
    private const val MAX_QUERIES = 2

    /** 每个关键词最多试几条候选：第一名可能没带词，试到第 3 条还不行为止。 */
    private const val MAX_CANDIDATES = 3

    /**
     * 一首歌最多留几个「版本」供用户手动切换（0.2.26）。
     *
     * 现实问题：在线搜出来的第一条未必是用户想听的那版 —— 同名翻唱、现场版、DJ 版
     * 的时间轴完全不同，一开场就「整首差半句」，而旧实现没有任何补救手段。
     * 三家源 × 最多 3 条候选本来就有多个可用结果，留 3 个足够覆盖「原版 / 翻唱 / 伴奏」。
     */
    const val MAX_VARIANTS = 3

    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_CACHE = 24

    /** 成功结果在内存里留 30 分钟；更久的那份在磁盘上，查盘比联网便宜得多。 */
    private const val OK_TTL_MS = 30 * 60_000L

    /** 失败只挡 45 秒：够挡住「同一秒内反复触发」，又不至于把整首歌判死。 */
    private const val FAIL_TTL_MS = 45_000L

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AnyLyric/1.0"

    /** 汽水接口对 Referer 有校验，缺了会直接返回空。 */
    private const val QISHUI_REFERER = "https://www.qishui.com/"

    private val main = Handler(Looper.getMainLooper())

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anylyric-online").apply { isDaemon = true }
    }

    private class Entry(val variants: List<List<LyricLine>>?, val atMs: Long)

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
     * @param allowNetwork 为 false 时**只查磁盘缓存、不联网**（0.2.25）。关掉在线补词
     *             不等于丢掉已经取到的词：缓存是本地资源，开关只控制「要不要出门找」。
     * @param want 需要几**版**。默认 1；用户点「换一版」时传 2、3 —— 缓存里那版不够时
     *             会继续往候选后面找，而不是把同一份再交一次（见 [resolve]）。
     * @param onResult 在主线程回调，参数是**按优先级排好的版本表**（非空）。
     *                 调用方取第 0 版显示，其余留给「换一版」。
     * @param onMiss   在主线程回调，表示这首歌这一轮没取到（可能已落盘失败缓存）。
     *                 调用方据此安排重试，不要把它当成「永远没有」。
     * @return false 表示**这首歌已经有一次请求在跑**，本次既没发起也不会回调。
     *         调用方必须据此把自己的「请求中」标志复位，否则那首歌再也不会重试。
     */
    fun request(
        title: String,
        artist: String,
        durationMs: Long,
        allowNetwork: Boolean = true,
        want: Int = 1,
        onResult: (List<List<LyricLine>>) -> Unit,
        onMiss: () -> Unit = {}
    ): Boolean {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            main.post(onMiss)
            return true
        }
        val cleanArtist = artist.trim()
        val key = LyricStore.key(cleanTitle, cleanArtist)
        val now = System.currentTimeMillis()
        val target = want.coerceIn(1, MAX_VARIANTS)

        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && now - hit.atMs < if (hit.variants == null) FAIL_TTL_MS else OK_TTL_MS) {
                val variants = hit.variants
                if (variants.isNullOrEmpty()) {
                    main.post(onMiss)
                    return true
                }
                // 缓存里的版本够用就直接给；不够时**继续往下走**去联网补齐 ——
                // 否则「换一版」永远只能拿到同一份。
                if (variants.size >= target) {
                    main.post { onResult(variants) }
                    return true
                }
            }
            // 同一首歌正在取：不重复发起，也不回调 —— 上层据此复位标志后稍后再来。
            if (pendingKey == key) return false
            pendingKey = key
        }

        worker.execute {
            val variants = runCatching {
                resolve(cleanTitle, cleanArtist, key, durationMs, allowNetwork, target)
            }.getOrNull()
            synchronized(cache) {
                // 只查盘失败不算「这首歌没有」：下一次开着网络还要再试。
                if (!variants.isNullOrEmpty() || allowNetwork) {
                    cache[key] = Entry(
                        variants?.takeIf { it.isNotEmpty() },
                        System.currentTimeMillis()
                    )
                }
                if (pendingKey == key) pendingKey = null
            }
            main.post {
                if (variants.isNullOrEmpty()) onMiss() else onResult(variants)
            }
        }
        return true
    }

    /**
     * 只清内存结果，**不动磁盘缓存**。
     *
     * 关掉「在线补词」时用它：那一开关管的是「要不要联网」，不是「要不要保留已有的词」。
     * 连盘一起清会让屏上原本有词的歌立刻变「暂无歌词」，看起来像数据被开关删了。
     */
    fun dropMemoryCache() {
        synchronized(cache) {
            cache.clear()
            pendingKey = null
        }
    }

    /** 彻底清空（内存 + 磁盘）。设置页「清空缓存」用。 */
    fun clear() {
        dropMemoryCache()
        LyricStore.clear()
    }

    // ---------------------------------------------------------------- 取词

    /**
     * 取词主流程：返回**按优先级排好的版本表**（0.2.26 起不再只给一份）。
     *
     * 顺序仍然是「磁盘 → 酷狗 → 网易云 → 汽水」，但收集到 [want] 版就停手：
     *  · `want = 1`（默认）→ 与旧实现完全一致，多一次候选就早退，常态成本不变；
     *  · `want = 2/3`（用户点了「换一版」）→ 继续往后面的候选走，把不同版本带回来。
     *
     * 磁盘里**只存第 0 版**：切到别的版本是临时纠错，重启后回到首选才可预期。
     */
    private fun resolve(
        title: String,
        artist: String,
        key: String,
        durationMs: Long,
        allowNetwork: Boolean,
        want: Int
    ): List<List<LyricLine>> {
        val out = ArrayList<List<LyricLine>>(want)
        // 1. 磁盘：第二次听同一首歌永远走这里，不联网。
        val cached = LyricStore.load(key)
        if (cached != null) {
            val restored = toLines(cached, title, artist)
            if (restored.isNotEmpty()) out += restored
        }
        // 只查缓存（在线补词关着）：到此为止，不出门。
        if (!allowNetwork) return out
        if (out.size >= want) return out

        // 2. 网络：三家按优先级串行（Cardash 的顺序：酷狗 → 网易云 → 汽水）。
        val targetSec = durationMs / 1000L
        val deadline = System.currentTimeMillis() + MAX_TOTAL_MS
        collectKugou(title, artist, targetSec, deadline, out, want)
        if (out.size < want) collectNetease(title, artist, targetSec, deadline, out, want)
        if (out.size < want) collectQishui(title, artist, targetSec, deadline, out, want)

        if (out.isNotEmpty() && cached == null) {
            LyricStore.save(key, LrcParser.build(out[0]))
        }
        return out
    }

    /**
     * 收一版，并挡掉与已有版本**内容相同**的那份。
     *
     * 去重是必须的：切到第 2 版时网络的第一条候选往往就是盘里那一版，
     * 不去重会「点了换一版，画面一个字都没变」。
     */
    private fun addVariant(out: MutableList<List<LyricLine>>, lines: List<LyricLine>) {
        if (lines.isEmpty()) return
        for (existing in out) if (sameLines(existing, lines)) return
        out += lines
    }

    /** 两份行表是不是同一份词：行数、首行、末行、首行时间都一致就认为是。 */
    private fun sameLines(a: List<LyricLine>, b: List<LyricLine>): Boolean {
        if (a.size != b.size || a.isEmpty()) return a.size == b.size
        val first = a[0]
        val otherFirst = b[0]
        return first.text == otherFirst.text && first.timeMs == otherFirst.timeMs &&
            a[a.lastIndex].text == b[b.lastIndex].text
    }

    /**
     * 原始响应 → 行表：有轴按轴，没轴按 2 秒一句铺开，最后剔掉开头的「歌名 - 歌手」行。
     *
     * 三者顺序不能换：先解析才知道有没有轴；先铺开才轮得上剔标题行（纯文本同样有这行）。
     */
    private fun toLines(text: String, title: String, artist: String): List<LyricLine> {
        val parsed = LrcParser.parse(text)
        val lines = parsed.ifEmpty { LrcParser.spread(text) }
        return LrcParser.stripTitleLine(lines, title, artist)
    }

    private fun collectKugou(
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<List<LyricLine>>,
        want: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= want) return
            val search = httpGet(
                "https://mobileservice.kugou.com/api/v3/search/song" +
                    "?format=json&keyword=${encode(query)}&page=1&pagesize=10",
                deadline
            ) ?: continue
            // 结构变了也不怕：认不出专用字段就退回通用候选收集。
            val songs = ranked(
                OnlineLyricsParser.kugouSongs(search)
                    .ifEmpty { OnlineLyricsParser.candidates(search) },
                title, artist, targetSec
            ) { Triple(it.title, it.artist, it.durationSec) }

            for (song in songs.take(MAX_CANDIDATES)) {
                if (out.size >= want) return
                val lrc = kugouLyricFor(song, title, artist, targetSec, deadline) ?: continue
                addVariant(out, toLines(lrc, title, artist))
            }
        }
    }

    /** 一首酷狗候选的取词：歌词搜索 → 按 hash/时长挑条目 → 下载 LRC。 */
    private fun kugouLyricFor(
        song: SongCandidate,
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long
    ): String? {
        val seconds = if (song.durationSec > 0) song.durationSec else targetSec
        val lyricSearch = httpGet(
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
                "&keyword=${encode(song.title.ifBlank { title })}" +
                "&duration=$seconds&hash=${encode(song.id)}",
            deadline
        ) ?: return null
        val candidates = ranked(
            OnlineLyricsParser.kugouLyricCandidates(lyricSearch),
            title, artist, targetSec
        ) { Triple(it.title, it.artist, it.durationSec) }

        for (candidate in candidates.take(MAX_CANDIDATES)) {
            val download = httpGet(
                "https://lyrics.kugou.com/download?ver=1&client=pc" +
                    "&id=${encode(candidate.id)}&accesskey=${encode(candidate.accessKey)}" +
                    "&fmt=lrc&charset=utf8",
                deadline
            ) ?: continue
            val lrc = OnlineLyricsParser.kugouContent(download) ?: continue
            if (toLines(lrc, title, artist).isNotEmpty()) return lrc
        }
        return null
    }

    private fun collectNetease(
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<List<LyricLine>>,
        want: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= want) return
            val search = httpGet(
                "https://music.163.com/api/search/get/web" +
                    "?s=${encode(query)}&type=1&limit=10&offset=0",
                deadline
            ) ?: continue
            val songs = ranked(
                OnlineLyricsParser.neteaseSongs(search)
                    .ifEmpty { OnlineLyricsParser.candidates(search) },
                title, artist, targetSec
            ) { Triple(it.title, it.artist, it.durationSec) }

            // 逐个试：网易大量「XX(治愈版)」候选没有歌词，排第一不代表有词。
            for (song in songs.take(MAX_CANDIDATES)) {
                if (out.size >= want) return
                val lyric = httpGet(
                    "https://music.163.com/api/song/lyric" +
                        "?id=${encode(song.id)}&lv=1&kv=1&tv=-1",
                    deadline
                ) ?: continue
                val text = OnlineLyricsParser.neteaseLyric(lyric) ?: continue
                addVariant(out, toLines(text, title, artist))
            }
        }
    }

    /**
     * 汽水音乐：Cardash 的第三个源。
     *
     * 实测（2026-11-14）：`luna/pc/search/track` 对匿名请求返回 200 + 空体
     * （抖音系接口后来加了签名校验），所以这条链路目前大概率取不到词。
     * 保留它有两个理由：参数一旦恢复可用就直接生效；它排在最后，
     * 只有前两家都失败才会走到，代价是两次空请求。
     */
    private fun collectQishui(
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<List<LyricLine>>,
        want: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= want) return
            val search = httpGet(
                "https://api.qishui.com/luna/pc/search/track" +
                    "?q=${encode(query)}&cursor=0&search_method=input" +
                    "&aid=386088&device_platform=web&channel=pc_web",
                deadline,
                QISHUI_REFERER
            ) ?: continue
            val songs = ranked(
                OnlineLyricsParser.candidates(search), title, artist, targetSec
            ) { Triple(it.title, it.artist, it.durationSec) }

            for (song in songs.take(MAX_CANDIDATES)) {
                if (out.size >= want) return
                val detail = httpGet(
                    "https://api.qishui.com/luna/pc/track_v2" +
                        "?track_id=${encode(song.id)}&media_type=track" +
                        "&aid=386088&device_platform=web&channel=pc_web",
                    deadline,
                    QISHUI_REFERER
                ) ?: continue
                val text = OnlineLyricsParser.qishuiLyric(detail) ?: continue
                addVariant(out, toLines(text, title, artist))
            }
        }
    }

    /**
     * 按 [LyricMatch.score] 降序返回**合格**候选（标题对不上的已剔除）。
     *
     * 返回列表而不是单个最佳：Cardash 只取第一名，因为它的标题清洗很轻、
     * 第一名通常是原版；我们的清洗更激进（去括号），同名候选会并列，
     * 而排在前面的那条未必**带歌词**。逐个试最多 [MAX_CANDIDATES] 条，
     * 既保留排序意图，又不会因为一条空词把整首判死。
     */
    private fun <T> ranked(
        candidates: List<T>,
        title: String,
        artist: String,
        targetSec: Long,
        fields: (T) -> Triple<String, String, Long>
    ): List<T> {
        val scored = ArrayList<Pair<T, Int>>(candidates.size)
        for (candidate in candidates) {
            val (cTitle, cArtist, cDur) = fields(candidate)
            val score = LyricMatch.score(cTitle, cArtist, cDur, title, artist, targetSec)
            if (!LyricMatch.acceptable(score)) continue
            scored += candidate to score
        }
        scored.sortByDescending { it.second }
        return scored.map { it.first }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    /**
     * @param deadline 本次取词的总截止时刻；按剩余预算收紧 connect/read 超时。
     * @param referer  部分源（汽水）要求带 Referer，缺了直接空响应。
     */
    private fun httpGet(url: String, deadline: Long, referer: String? = null): String? {
        val budget = deadline - System.currentTimeMillis()
        if (budget <= 0L) return null
        val timeout = budget.coerceAtMost(READ_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(500)
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
            if (referer != null) c.setRequestProperty("Referer", referer)
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
