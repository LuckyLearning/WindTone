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
 * 在线补词：本地通道全 miss 时，按「歌名 + 歌手」去几个在线库找歌词。
 *
 * 链路结构沿用 Cardash（`g/R3`）的四条做法 —— 它是取词最稳的一份实现：
 *
 *  1. **串行、优先级固定**。旧实现是酷狗与网易并行、谁先回来用谁 —— 网络快的那家
 *     未必是匹配最准的那家，于是同一首歌会在「词很准」和「整首差半句」之间反复横跳。
 *     0.2.27 起顺序**由用户决定**（见 [OnlineSource]）：手动指定时只跑那一家，
 *     自动时按用户排的顺序依次尝试。
 *  2. **先筛后排**：候选必须过 [LyricMatch] 的标题判定，再按时长挑最接近的。
 *     没有总分门槛 —— Cardash 的取舍是「宁可时间轴略偏，也不给空屏」。
 *  3. **响应结构不写死**：各家字段名各不相同，统一用
 *     [OnlineLyricsParser.candidates] 的「有标题、有 id 就是候选」规则吃下来。
 *  4. **纯文本也能用**：解析不出时间轴时按 2 秒一句铺开（[LrcParser.spread]），
 *     而不是当「没取到」。lyrics.ovh 与 LRCLIB 的 plainLyrics 都只有纯文本。
 *
 * 缓存与重试保持原样：先查盘（[LyricStore]）、成功后落盘、失败只挡 45 秒，
 * 上层有 4 次退避重试 + 网络恢复清零。
 */
object OnlineLyricsFetcher {

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * 一次取词的总预算。
     *
     * 0.2.27 起最多五家串行，比原来的三家需要更多余量（15s → 18s）。
     * 任何一家超时就换下一家，不会无限等；预算用尽后剩余源直接跳过。
     */
    private const val MAX_TOTAL_MS = 18_000L

    /** 每家最多试几个关键词：完整「歌名 歌手」+ 纯歌名，再多只是把等待时间拉长。 */
    private const val MAX_QUERIES = 2

    /** 每个关键词最多试几条候选：第一名可能没带词，试到第 3 条还不行为止。 */
    private const val MAX_CANDIDATES = 3

    /**
     * **每个源**最多贡献几版。
     *
     * 不设这个上限的话，排第一的那家只要有词就能把 `want` 吃满，
     * 用户点开版本列表看到的永远是同一家的三个翻唱 —— 换源等于白换。
     */
    private const val MAX_PER_SOURCE = 2

    /**
     * 首次取几版（0.2.27）。
     *
     * 用户原话：「最开始只请求3个，更多时可以获取更多」。3 版正好覆盖
     * 「原版 / 翻唱 / 伴奏」这三种常见情况，又不会一上来就连跑五家。
     */
    const val FIRST_WANT = 3

    /**
     * 版本上限（点「获取更多」时的天花板）。
     *
     * 现实问题：在线搜出来的第一条未必是用户想听的那版 —— 同名翻唱、现场版、DJ 版
     * 的时间轴完全不同，一开场就「整首差半句」，而旧实现没有任何补救手段。
     */
    const val MAX_VARIANTS = 6

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

    private class Entry(val variants: List<OnlineVariant>?, val atMs: Long)

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
     * @param want 需要几**版**。首次 [FIRST_WANT]，点「获取更多」时递增到 [MAX_VARIANTS]。
     * @param sources 按顺序尝试的源。**只有一个元素 = 用户手动指定**，此时缓存里
     *             来自别家的词不再优先（否则「指定源」对已缓存的歌等于没生效）。
     * @param onResult 在主线程回调，参数是**按优先级排好的版本表**（非空）。
     * @param onMiss   在主线程回调，表示这首歌这一轮没取到（可能已落盘失败缓存）。
     * @return false 表示**这首歌已经有一次请求在跑**，本次既没发起也不会回调。
     */
    fun request(
        title: String,
        artist: String,
        durationMs: Long,
        allowNetwork: Boolean = true,
        want: Int = FIRST_WANT,
        sources: List<OnlineSource> = OnlineSource.DEFAULT_ORDER,
        onResult: (List<OnlineVariant>) -> Unit,
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
        val plan = sources.ifEmpty { OnlineSource.DEFAULT_ORDER }
        val pinned = plan.size == 1

        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && now - hit.atMs < if (hit.variants == null) FAIL_TTL_MS else OK_TTL_MS) {
                val variants = usingSource(hit.variants, plan)
                if (variants.isNullOrEmpty()) {
                    // 缓存里有词、但来源被用户排除了：不能直接判 miss，
                    // 要走下去按新指定的源重取（见 resolve 的同一判定）。
                    if (hit.variants.isNullOrEmpty()) {
                        main.post(onMiss)
                        return true
                    }
                } else if (variants.size >= target) {
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
                resolve(cleanTitle, cleanArtist, key, durationMs, allowNetwork, target, plan)
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
     * 把用户选中的那一版记成「下次首选」（0.2.27）。
     *
     * 用户原话：「切换后的歌词才是正确的需要保存的」。行为上就是**覆盖写盘**：
     * 下次放同一首歌，[resolve] 第一步读盘拿到的就是这一版，顺序与来源都跟着回来。
     *
     * 为什么必须在工作线程：写盘 + 序列化一行几 KB，主线程上做完手会抖一下。
     */
    fun remember(title: String, artist: String, variant: OnlineVariant) {
        if (variant.lines.isEmpty()) return
        val key = LyricStore.key(title.trim(), artist.trim())
        if (key.isBlank()) return
        val text = LrcParser.build(variant.lines)
        if (text.isBlank()) return
        worker.execute {
            LyricStore.save(key, text, variant.source?.id)
            synchronized(cache) {
                val hit = cache[key] ?: return@synchronized
                val rest = hit.variants.orEmpty().filter { !sameLines(it.lines, variant.lines) }
                // 选中那版挪到第一位：后续「还要几版」的判断按首位口径算，不会重复取。
                cache[key] = Entry(
                    listOf(variant.copy(fromCache = true)) + rest,
                    System.currentTimeMillis()
                )
            }
        }
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
     * 取词主流程：返回**按优先级排好的版本表**。
     *
     * 顺序是「磁盘 → 用户指定的源（或自动顺序）」：
     *  · 磁盘命中且够 [want] 版 → 直接返回，一次网络都不发；
     *  · `want = FIRST_WANT`（默认）→ 凑够 3 版就停，常态只跑前一家；
     *  · 点「获取更多」→ want 变大，继续往后走后面的源与候选。
     *
     * 磁盘里存的是**第 0 版**（或者用户手动选中的那一版，见 [remember]）。
     */
    private fun resolve(
        title: String,
        artist: String,
        key: String,
        durationMs: Long,
        allowNetwork: Boolean,
        want: Int,
        plan: List<OnlineSource>
    ): List<OnlineVariant> {
        val out = ArrayList<OnlineVariant>(want)
        val pinned = plan.size == 1

        // 1. 磁盘：第二次听同一首歌永远走这里，不联网。
        val cached = LyricStore.load(key)
        if (cached != null) {
            val restored = toLines(cached, title, artist)
            if (restored.isNotEmpty()) {
                val source = OnlineSource.of(LyricStore.sourceOf(key))
                // 手动指定了源：盘上那份若明确来自别家，就不再拿来顶 ——
                // 否则「指定只用 LRCLIB」对已经缓存过的歌完全没效果。
                // 旧版本写的缓存没有来源标记（source == null），仍然沿用，避免升级后全量重取。
                if (!pinned || source == null || source == plan[0]) {
                    out += OnlineVariant(restored, source, fromCache = true)
                }
            }
        }
        // 只查缓存（在线补词关着）：到此为止，不出门。
        if (!allowNetwork) return out
        if (out.size >= want) return out

        // 2. 网络：按用户给的顺序串行，凑够 [want] 版就停。
        val targetSec = durationMs / 1000L
        val deadline = System.currentTimeMillis() + MAX_TOTAL_MS
        for (source in plan) {
            if (out.size >= want) break
            if (System.currentTimeMillis() >= deadline) break
            runCatching { collect(source, title, artist, targetSec, deadline, out, want) }
        }

        // 磁盘只存第 0 版：它才是「下次直接能用」的那份。已经是盘里那份就不必重写。
        val first = out.firstOrNull()
        val alreadyOnDisk = first != null && first.fromCache
        if (first != null && !alreadyOnDisk) {
            LyricStore.save(key, LrcParser.build(first.lines), first.source?.id)
        }
        return out
    }

    /** 一次取词里每个源最多贡献 [MAX_PER_SOURCE] 版。 */
    private fun collect(
        source: OnlineSource,
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<OnlineVariant>,
        want: Int
    ) {
        val limit = minOf(want, out.size + MAX_PER_SOURCE)
        when (source) {
            OnlineSource.KUGOU ->
                collectKugou(title, artist, targetSec, deadline, out, limit)

            OnlineSource.NETEASE ->
                collectNetease(title, artist, targetSec, deadline, out, limit)

            OnlineSource.LRCLIB ->
                collectLrclib(title, artist, targetSec, deadline, out, limit)

            OnlineSource.LYRICS_OVH ->
                collectLyricsOvh(title, artist, deadline, out, limit)

            OnlineSource.QISHUI ->
                collectQishui(title, artist, targetSec, deadline, out, limit)
        }
    }

    private fun collectKugou(
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<OnlineVariant>,
        limit: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= limit) return
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
                if (out.size >= limit) return
                val lrc = kugouLyricFor(song, title, artist, targetSec, deadline) ?: continue
                addVariant(out, toLines(lrc, title, artist), OnlineSource.KUGOU)
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
        out: MutableList<OnlineVariant>,
        limit: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= limit) return
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
                if (out.size >= limit) return
                val lyric = httpGet(
                    "https://music.163.com/api/song/lyric" +
                        "?id=${encode(song.id)}&lv=1&kv=1&tv=-1",
                    deadline
                ) ?: continue
                val text = OnlineLyricsParser.neteaseLyric(lyric) ?: continue
                addVariant(out, toLines(text, title, artist), OnlineSource.NETEASE)
            }
        }
    }

    /**
     * LRCLIB：开放 REST API，无需密钥。
     *
     * 与另几家最大的不同：`/api/search` 的**每条结果里就带词**
     * （`syncedLyrics` / `plainLyrics`），不需要「搜索 → 再按 id 取一次」。
     * 所以它是所有源里请求数最少的一家 —— 一次请求拿回候选和词。
     */
    private fun collectLrclib(
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<OnlineVariant>,
        limit: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= limit) return
            val search = httpGet(
                "https://lrclib.net/api/search?q=${encode(query)}",
                deadline
            ) ?: continue
            val tracks = OnlineLyricsParser.lrclibTracks(search).filter { it.lyric != null }
            if (tracks.isEmpty()) continue
            val sorted = ranked(tracks, title, artist, targetSec) {
                Triple(it.candidate.title, it.candidate.artist, it.candidate.durationSec)
            }
            for (track in sorted.take(MAX_CANDIDATES)) {
                if (out.size >= limit) return
                val text = track.lyric ?: continue
                addVariant(out, toLines(text, title, artist), OnlineSource.LRCLIB)
            }
        }
    }

    /**
     * lyrics.ovh：开放 REST API，无需密钥，路径是 `/v1/{artist}/{title}`。
     *
     * 只有**纯文本**（无时间轴），按 2 秒一句铺开 —— 比没词好，但比不上有轴的。
     * 歌手是路径的一部分，空歌手时这家直接跳过（否则路径少一段必然 404）。
     */
    private fun collectLyricsOvh(
        title: String,
        artist: String,
        deadline: Long,
        out: MutableList<OnlineVariant>,
        limit: Int
    ) {
        if (artist.isBlank()) return
        // 关键词换不换都不影响结果（路径用的是原始歌名/歌手），所以只走一趟。
        if (out.size >= limit) return
        val body = httpGet(
            "https://api.lyrics.ovh/v1/${encodePath(artist)}/${encodePath(title)}",
            deadline
        ) ?: return
        val text = OnlineLyricsParser.lyricsOvh(body) ?: return
        addVariant(out, toLines(text, title, artist), OnlineSource.LYRICS_OVH)
    }

    /**
     * 汽水音乐：Cardash 的第三个源。
     *
     * 实测（2026-11-14）：`luna/pc/search/track` 对匿名请求返回 200 + 空体
     * （抖音系接口后来加了签名校验），所以这条链路目前大概率取不到词。
     * 保留它有两个理由：参数一旦恢复可用就直接生效；它出厂排在最后，
     * 只有前面的源都失败才会走到，代价是两次空请求。
     */
    private fun collectQishui(
        title: String,
        artist: String,
        targetSec: Long,
        deadline: Long,
        out: MutableList<OnlineVariant>,
        limit: Int
    ) {
        for (query in LyricMatch.queries(title, artist).take(MAX_QUERIES)) {
            if (out.size >= limit) return
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
                if (out.size >= limit) return
                val detail = httpGet(
                    "https://api.qishui.com/luna/pc/track_v2" +
                        "?track_id=${encode(song.id)}&media_type=track" +
                        "&aid=386088&device_platform=web&channel=pc_web",
                    deadline,
                    QISHUI_REFERER
                ) ?: continue
                val text = OnlineLyricsParser.qishuiLyric(detail) ?: continue
                addVariant(out, toLines(text, title, artist), OnlineSource.QISHUI)
            }
        }
    }

    /**
     * 收一版，并挡掉与已有版本**内容相同**的那份。
     *
     * 去重是必须的：换源 / 要更多版本时，别的源的第一条候选往往就是盘里那一版，
     * 不去重会「点了换一版，画面一个字都没变」。
     */
    private fun addVariant(
        out: MutableList<OnlineVariant>,
        lines: List<LyricLine>,
        source: OnlineSource
    ) {
        if (lines.isEmpty()) return
        for (existing in out) if (sameLines(existing.lines, lines)) return
        out += OnlineVariant(lines, source)
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
     * 缓存里的版本表，按用户当前指定的源过滤。
     *
     * 只有一个源（手动指定）时，来自别家的旧版本一律不认；自动模式下原样返回。
     */
    private fun usingSource(
        variants: List<OnlineVariant>?,
        plan: List<OnlineSource>
    ): List<OnlineVariant>? {
        if (variants == null) return null
        if (plan.size != 1) return variants
        val pinned = plan[0]
        return variants.filter { it.source == null || it.source == pinned }
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

    /** 路径段编码：`+` 在 URL 路径里是加号而不是空格，必须换成 `%20`。 */
    private fun encodePath(text: String): String =
        URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")

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
