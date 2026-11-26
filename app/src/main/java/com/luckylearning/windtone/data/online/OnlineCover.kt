package com.luckylearning.windtone.data.online

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.isActive
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 在线封面：把一首在线歌 / 一张歌单封面换成「可下载的图片地址」，再下载成字节。
 *
 * 分两步是因为**地址有时要现场换**：QQ / 咪咕 / 酷狗在搜索结果里就给了图
 * （`OnlineTrack.coverUrl`），酷我则必须再请求一次 `artistpicserver` 才能拿到真实地址
 * （它返回的是纯文本 URL，不是图片本身 —— 直接当封面地址下载会得到一段文字）。
 *
 * 结果只做**进程内**缓存：封面是派生数据，`CoverRepository` 已经有磁盘二级缓存
 * （`cacheDir/covers/<id>-<边长>.jpg`），这里再落一份盘等于重复占用。
 *
 * ## 2026-11-25 的四条稳定性约定
 * 1. **歌单封面也要认酷我解析端点**：`thumbUrl` 以前只做 `normalizeImage`，
 *    而酷我歌单列表给的 `img` 正是 `pic.web?...rid=`。不解析就永远占位 ——
 *    这是「歌单里一部分封面出不来」的主因。
 * 2. **同步预读**：列表行重新进入组合时先查一次缓存（[peekThumb] / [peekTrack]），
 *    命中就直接画图，不再「先占位、等一帧 LaunchedEffect、再换图」地闪一下。
 * 3. **同地址单飞**：一屏几十个卡片里同一张图被点两次只发一个请求；
 *    失败请求做 90 秒冷却，避免滑回同一行时反复打网络。
 * 4. **缓存上限挂 `Runtime.maxMemory()`**：不写死条数，低内存车机自动收紧。
 */
object OnlineCover {

    private const val TAG = "OnlineCover"

    private const val TIMEOUT_MS = 7_000L
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** 地址缓存条数：地址比字节便宜，但同样没必要每次播放都问一遍 artistpicserver */
    private const val URL_CACHE_SIZE = 128

    /** 下载失败后的冷却：同一张图 90 秒内不再重试（列表滑回时不至于又排一轮队） */
    private const val FAIL_COOLDOWN_MS = 90_000L

    /** 下载失败重试的退避基数（300 / 600ms），只对网络类失败生效 */
    private const val RETRY_BASE_MS = 300L

    private val urlCache = object : LruCache<String, String>(URL_CACHE_SIZE) {}

    /** 原始封面地址（含 `pic.web`）→ 真实图片地址。酷我歌单封面靠它二次命中。 */
    private val resolvedUrlCache = object : LruCache<String, String>(URL_CACHE_SIZE) {}

    /** 下载失败时间戳：key 是缓存键（含目标边长） */
    private val failedAt = object : LruCache<String, Long>(128) {}

    /**
     * 已经下到的图片字节。
     *
     * 解码后的位图按张占几百 KB，滑过一两屏就会被 LRU 挤掉。
     * 只留位图的话，滚回来要重新联网；这次请求一旦被滑出屏幕取消，
     * 或者赶上失败冷却，封面就停在占位图。字节小得多，滚回来可以直接再解码。
     */
    private val byteCache = object : LruCache<String, ByteArray>(BYTE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** 正在飞行的地址 → 共享结果。同一张图并发请求只发一次。 */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /**
     * 缩略图缓存：键是「稳定键 / url:地址 + @ 目标边长」。
     *
     * 上限按 `maxMemory()/8` 估算，再夹在 8–48MB：300px 图约 360KB，
     * 低内存车机（128MB）约 44 张，手机（256MB+）约 90 张，够覆盖一两屏。
     */
    private val thumbCache = object : LruCache<String, Bitmap>(thumbCacheBudget()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun thumbCacheBudget(): Int {
        val budget = Runtime.getRuntime().maxMemory() / 8
        return budget.coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()
    }

    /**
     * 封面客户端单独抬高「同一主机并发」。
     * 搜索一屏几十张图如果挤在默认的每主机 5 连接里，排在后面的请求会在排队期间被超时掐掉，
     * 表现就是滑得快的那几行永远是占位、滑慢或重进才有图。
     * 真正的超时从拿到名额之后才开始算，见 [fetchBytes]。
     */
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 同时真正打出去的封面请求。多出来的在信号量上挂起，不消耗超时。 */
    private val slots = Semaphore(6)

    /** 封面地址（在线歌才有；非在线 / 取不到返回 null） */
    suspend fun urlFor(context: Context, song: Song): String? {
        if (!song.isOnline) return null
        val track = runCatching { OnlineLibrary(context).trackOf(song.stableKey) }.getOrNull()
            ?: return null
        return imageUrl(track)
    }

    /**
     * 这首歌最终能下到的图片地址。
     *
     * 直接地址（搜索结果里的 https 图、展开过 `{size}` 的酷狗图）立刻返回。
     * 酷我只有 `pic.web` 或干脆没有 coverUrl 时，再请求一次换出真实地址并缓存。
     * 缓存里只放归一后的图片地址，不放解析端点。
     */
    internal suspend fun imageUrl(track: OnlineTrack): String? {
        synchronized(urlCache) { urlCache.get(track.stableKey) }?.let { return it }
        OnlineCoverUrls.directImage(track.coverUrl, rawImg(track))?.let { direct ->
            synchronized(urlCache) { urlCache.put(track.stableKey, direct) }
            return direct
        }
        if (track.sourceId != "kw" && !OnlineCoverUrls.isKuwoResolver(track.coverUrl)) return null
        val resolved = resolveImageUrl(track.coverUrl, track.songId) ?: return null
        synchronized(urlCache) { urlCache.put(track.stableKey, resolved) }
        return resolved
    }

    /** 下载封面字节（受 [MAX_BYTES] 限制，超限视为失败 —— 播放页封面不值得为它爆堆） */
    suspend fun bytes(context: Context, song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val url = urlFor(context, song) ?: return@withContext null
        fetchBytesShared(url)
    }

    // ---------------------------------------------------------------- 缩略图（列表 / 歌单）

    /** 同步预读：列表行重新进入组合时先看缓存，命中就不用等一帧协程再换图。 */
    fun peekTrack(stableKey: String, targetPx: Int): Bitmap? {
        val key = "$stableKey@$targetPx"
        val url = synchronized(urlCache) { urlCache.get(stableKey) }
        if (url == null) {
            return synchronized(thumbCache) { liveBitmap(key) }
        }
        return bitmapOrDecode(key, url, targetPx)
    }

    /**
     * 同步预读歌单封面。`url` 可能是酷我 `pic.web`，此时查「解析后的真实地址」再查图；
     * 解析本身是网络请求，这里只能命中已经解析过的那部分，未解析的仍走异步。
     */
    fun peekThumb(url: String?, targetPx: Int): Bitmap? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val real = OnlineCoverUrls.normalizeImage(raw)
            ?: synchronized(resolvedUrlCache) { resolvedUrlCache.get(raw) }
            ?: return null
        return bitmapOrDecode("url:$real@$targetPx", real, targetPx)
    }

    /**
     * 给搜索结果行用的缩略图。
     *
     * 入参是 [OnlineTrack] 而不是 [Song]：搜索结果**还没入库**（用户只是看了一眼），
     * 不能为了显示一张图就往曲库里插一行。所以这里绕过 `OnlineLibrary.trackOf`，
     * 直接用曲目自带的 raw / coverUrl 取图。
     */
    suspend fun thumb(context: Context, track: OnlineTrack, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${track.stableKey}@$targetPx"
            synchronized(thumbCache) { thumbCache.get(key) }?.let { return@withContext it }
            val url = imageUrl(track) ?: return@withContext null
            loadBitmap(url, targetPx, key)
        }

    /**
     * 按**图片地址**直接取缩略图（歌单封面用）。
     *
     * 歌单不是曲目，没有 `OnlineTrack` 也没有 raw，不能走 [thumb] 那条链。
     * 与曲目缩略图共用同一张 LRU —— 两者都是「列表里的小图」，
     * 分成两个缓存只会在两处各占一半额度。
     */
    suspend fun thumbUrl(url: String?, targetPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val real = resolveImageUrl(url, null) ?: return@withContext null
        val key = "url:$real@$targetPx"
        synchronized(thumbCache) { thumbCache.get(key) }?.let { return@withContext it }
        loadBitmap(real, targetPx, key)
    }

    /**
     * 原始地址 → 可下载的图片地址。
     * 顺序：直接归一 → 已解析缓存 → 酷我 `pic.web` 二次请求。
     */
    private suspend fun resolveImageUrl(url: String?, knownSongId: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        OnlineCoverUrls.normalizeImage(raw)?.let { return it }
        if (!OnlineCoverUrls.isKuwoResolver(raw)) return null
        synchronized(resolvedUrlCache) { resolvedUrlCache.get(raw) }?.let { return it }
        val rid = OnlineCoverUrls.kuwoRid(raw) ?: knownSongId?.takeIf { it.isNotBlank() } ?: return null
        val real = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { resolveKuwo(rid) }.getOrNull()
        } ?: return null
        synchronized(resolvedUrlCache) { resolvedUrlCache.put(raw, real) }
        return real
    }

    /**
     * 从网络到 Bitmap 的最后一公里：缓存 → 冷却 → 单飞下载 → 解码 → 写缓存。
     * 重试放在这一层（而不是每个调用点各写一份），重试时不再叠加 UI 的延迟。
     */
    private suspend fun loadBitmap(realUrl: String, targetPx: Int, cacheKey: String): Bitmap? {
        bitmapOrDecode(cacheKey, realUrl, targetPx)?.let { return it }
        val failed = synchronized(failedAt) { failedAt.get(cacheKey) }
        if (failed != null && System.currentTimeMillis() - failed < FAIL_COOLDOWN_MS) return null

        var attempt = 0
        while (coroutineContext.isActive) {
            val bytes = try {
                fetchBytesShared(realUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CoverInterrupted) {
                // 生产者被滑出屏幕取消。这次不算失败，也不写 90 秒冷却。
                return null
            } catch (_: Exception) {
                null
            }
            if (bytes != null) {
                rememberBytes(realUrl, bytes)
                val bmp = runCatching { CoverRepository.decodeScaled(bytes, targetPx) }.getOrNull()
                if (bmp != null) {
                    synchronized(thumbCache) { thumbCache.put(cacheKey, bmp) }
                    synchronized(failedAt) { failedAt.remove(cacheKey) }
                    return bmp
                }
            }
            if (attempt >= 2) break
            attempt++
            delay(RETRY_BASE_MS * attempt)
        }
        if (coroutineContext.isActive) {
            synchronized(failedAt) { failedAt.put(cacheKey, System.currentTimeMillis()) }
        }
        return null
    }

    /**
     * 位图还在就直接用。被挤掉之后，用已经下过的字节再解码，不再发请求。
     * 已 recycle 的位图不能再画，丢掉后走字节。
     */
    private fun bitmapOrDecode(cacheKey: String, realUrl: String, targetPx: Int): Bitmap? {
        synchronized(thumbCache) { liveBitmap(cacheKey) }?.let { return it }
        val bytes = synchronized(byteCache) { byteCache.get(byteKey(realUrl)) } ?: return null
        val bmp = runCatching { CoverRepository.decodeScaled(bytes, targetPx) }.getOrNull() ?: return null
        synchronized(thumbCache) { thumbCache.put(cacheKey, bmp) }
        return bmp
    }

    private fun liveBitmap(cacheKey: String): Bitmap? {
        val bmp = thumbCache.get(cacheKey) ?: return null
        if (!bmp.isRecycled) return bmp
        thumbCache.remove(cacheKey)
        return null
    }

    private fun rememberBytes(realUrl: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(byteCache) { byteCache.put(byteKey(realUrl), bytes) }
    }

    private fun byteKey(realUrl: String): String =
        OnlineCoverUrls.normalizeImage(realUrl) ?: realUrl

    /** 测试：只丢掉解码后的位图，字节还在。用来模拟滑出屏幕后 LRU 把位图挤掉。 */
    internal fun dropDecodedThumbsForTest() {
        synchronized(thumbCache) { thumbCache.evictAll() }
    }

    /** 测试之间清掉进程内缓存，避免上一例的冷却或字节串到下一例。 */
    internal fun clearForTest() {
        synchronized(thumbCache) { thumbCache.evictAll() }
        synchronized(byteCache) { byteCache.evictAll() }
        synchronized(failedAt) { failedAt.evictAll() }
        synchronized(urlCache) { urlCache.evictAll() }
        synchronized(resolvedUrlCache) { resolvedUrlCache.evictAll() }
        inFlight.clear()
    }

    // ---------------------------------------------------------------- 网络

    /**
     * 同一地址并发只下载一次：等待者共享 [CompletableDeferred] 的结果。
     * 失败时也 complete(null)，等待者不会挂在取消的生产者上。
     */
    private suspend fun fetchBytesShared(url: String, missed: Int = 0): ByteArray? {
        val normalized = OnlineCoverUrls.normalizeImage(url) ?: return null
        val created = CompletableDeferred<ByteArray?>()
        val existing = inFlight.putIfAbsent(normalized, created)
        if (existing != null) {
            return try {
                existing.await()
            } catch (_: SharedCoverMiss) {
                // 生产方被滑出屏幕取消了。等它的人再试，不要把 null 记成 90 秒失败。
                if (missed >= 2) throw CoverInterrupted() else fetchBytesShared(url, missed + 1)
            }
        }
        try {
            val bytes = fetchBytes(normalized)
            created.complete(bytes)
            return bytes
        } catch (cancelled: CancellationException) {
            // 先摘掉飞行记录再唤醒等待者，否则等待者会拿到同一个已取消的 deferred 死循环。
            inFlight.remove(normalized, created)
            if (!created.isCompleted) created.completeExceptionally(SharedCoverMiss())
            throw cancelled
        } catch (t: Throwable) {
            created.complete(null)
            throw t
        } finally {
            inFlight.remove(normalized, created)
        }
    }

    /** 单飞的生产方被取消。等待者应重试，而不是把它当成「这张图下载失败」。 */
    private class SharedCoverMiss : Exception()

    /** 重试几次仍然只遇到被取消的生产者。调用方不要写失败冷却。 */
    private class CoverInterrupted : Exception()

    private suspend fun fetchBytes(url: String): ByteArray? {
        val normalized = OnlineCoverUrls.normalizeImage(url) ?: return null
        slots.acquire()
        try {
            return withTimeoutOrNull(TIMEOUT_MS + 5_000L) { downloadImage(normalized) }
        } finally {
            slots.release()
        }
    }

    /**
     * 下图片。若正文是一行 URL（酷我 `pic.web`，或某个我们没认出的解析端点），再跟一次。
     * 只跟一次，避免错误页互相指。
     */
    private suspend fun downloadImage(url: String, followed: Boolean = false): ByteArray? {
        val bytes = download(url) ?: return null
        if (OnlineCoverUrls.looksLikeImage(bytes)) return bytes
        if (followed) return null
        val next = OnlineCoverUrls.textUrl(bytes) ?: return null
        return downloadImage(next, followed = true)
    }

    /**
     * 滑出屏幕时取消正在进行的请求。
     * 否则 OkHttp 会把信号量占到自然结束，后面滚回来的封面一直排队，看起来像又变回占位图。
     * 取消造成的 IO 异常不当成下载失败。
     */
    private suspend fun download(url: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        OnlineCoverUrls.refererFor(url)?.let { builder.header("Referer", it) }
        val call = client.newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        try {
            val bytes = call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "封面下载失败 ${response.code}：$url")
                    null
                } else {
                    val length = response.body?.contentLength() ?: -1L
                    if (length > MAX_BYTES) {
                        Log.w(TAG, "封面过大（$length）：$url")
                        null
                    } else {
                        response.body?.bytes()?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
                    }
                }
            }
            if (cont.isActive) cont.resume(bytes)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    // ---------------------------------------------------------------- 分派

    private fun rawImg(track: OnlineTrack): String? =
        runCatching { JSONObject(track.raw).optString("img") }.getOrNull()

    /** `pic.web` 返回图片 URL 文本，再归一（https / `{size}`）。 */
    private fun resolveKuwo(songId: String): String? {
        if (songId.isBlank()) return null
        val url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic" +
            "&pictype=500&size=500&rid=$songId"
        val request = Request.Builder().url(url).header("User-Agent", UA)
            .header("Referer", "http://www.kuwo.cn/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            OnlineCoverUrls.normalizeImage(response.body?.string())
        }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    /** 压缩后的封面字节。320px JPEG 大约几十 KB，8MB 够滑出再滑回一整屏歌单。 */
    private const val BYTE_CACHE_BYTES = 8 * 1024 * 1024
}
