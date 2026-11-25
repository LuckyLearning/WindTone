package com.luckylearning.windtone.data.online

import android.content.Context
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 在线封面：把一首在线歌换成「可下载的图片地址」，再下载成字节。
 *
 * 分两步是因为**地址有时要现场换**：QQ / 咪咕 / 酷狗在搜索结果里就给了图
 * （`OnlineTrack.coverUrl`），酷我则必须再请求一次 `artistpicserver` 才能拿到真实地址
 * （它返回的是纯文本 URL，不是图片本身 —— 直接当封面地址下载会得到一段文字）。
 *
 * 结果只做**进程内**缓存：封面是派生数据，`CoverRepository` 已经有磁盘二级缓存
 * （`cacheDir/covers/<id>-<边长>.jpg`），这里再落一份盘等于重复占用。
 */
object OnlineCover {

    private const val TAG = "OnlineCover"

    private const val TIMEOUT_MS = 7_000L
    private const val MAX_BYTES = 4 * 1024 * 1024
    /** 地址缓存条数：地址比字节便宜，但同样没必要每次播放都问一遍 artistpicserver */
    private const val CACHE_SIZE = 64

    private val urlCache = object : LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) =
            size > CACHE_SIZE
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
    private val slots = Semaphore(8)

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
        synchronized(urlCache) { urlCache[track.stableKey] }?.let { return it }
        OnlineCoverUrls.directImage(track.coverUrl, rawImg(track))?.let { direct ->
            synchronized(urlCache) { urlCache[track.stableKey] = direct }
            return direct
        }
        if (track.sourceId != "kw" && !OnlineCoverUrls.isKuwoResolver(track.coverUrl)) return null
        val resolved = withTimeoutOrNull(TIMEOUT_MS) { runCatching { resolveKuwo(track.songId) }.getOrNull() }
            ?: return null
        synchronized(urlCache) { urlCache[track.stableKey] = resolved }
        return resolved
    }

    /** 下载封面字节（受 [MAX_BYTES] 限制，超限视为失败 —— 播放页封面不值得为它爆堆） */
    suspend fun bytes(context: Context, song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val url = urlFor(context, song) ?: return@withContext null
        fetchBytes(url)
    }

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
    private fun downloadImage(url: String, followed: Boolean = false): ByteArray? {
        val bytes = download(url) ?: return null
        if (OnlineCoverUrls.looksLikeImage(bytes)) return bytes
        if (followed) return null
        val next = OnlineCoverUrls.textUrl(bytes) ?: return null
        return downloadImage(next, followed = true)
    }

    private fun download(url: String): ByteArray? {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        OnlineCoverUrls.refererFor(url)?.let { builder.header("Referer", it) }
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "封面下载失败 ${response.code}：$url")
                return@use null
            }
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_BYTES) {
                Log.w(TAG, "封面过大（$length）：$url")
                return@use null
            }
            response.body?.bytes()?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        }
    }

    // ---------------------------------------------------------------- 搜索结果缩略图

    /**
     * 缩略图缓存。搜索结果每滚一屏就会请求几十张 40dp 图，
     * 不解码复用等于每一帧都在做网络 + 解码 —— 这是列表卡顿的经典成因。
     * 键是「稳定键 + 目标边长」：同一首歌在列表（40dp）和播放页（大图）目标不同。
     */
    private val thumbCache = object : LinkedHashMap<String, android.graphics.Bitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>) =
            size > 48
    }

    /**
     * 给搜索结果行用的缩略图。
     *
     * 入参是 [OnlineTrack] 而不是 [Song]：搜索结果**还没入库**（用户只是看了一眼），
     * 不能为了显示一张图就往曲库里插一行。所以这里绕过 `OnlineLibrary.trackOf`，
     * 直接用曲目自带的 raw / coverUrl 取图。
     */
    suspend fun thumb(context: Context, track: OnlineTrack, targetPx: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${track.stableKey}@$targetPx"
            synchronized(thumbCache) { thumbCache[key] }?.let { return@withContext it }
            val url = imageUrl(track) ?: return@withContext null
            val bytes = fetchBytes(url) ?: return@withContext null
            val bmp = com.luckylearning.windtone.data.CoverRepository.decodeScaled(bytes, targetPx)
                ?: return@withContext null
            synchronized(thumbCache) { thumbCache[key] = bmp }
            bmp
        }

    /**
     * 按**图片地址**直接取缩略图（歌单封面用）。
     *
     * 歌单不是曲目，没有 `OnlineTrack` 也没有 raw，不能走 [thumb] 那条链。
     * 缓存键带 `url:` 前缀，与曲目缩略图共用同一张 LRU —— 两者都是「列表里的小图」，
     * 分成两个缓存只会在两处各占一半额度。
     */
    suspend fun thumbUrl(url: String, targetPx: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            val normalized = OnlineCoverUrls.normalizeImage(url) ?: return@withContext null
            val key = "url:$normalized@$targetPx"
            synchronized(thumbCache) { thumbCache[key] }?.let { return@withContext it }
            val bytes = fetchBytes(normalized) ?: return@withContext null
            val bmp = com.luckylearning.windtone.data.CoverRepository.decodeScaled(bytes, targetPx)
                ?: return@withContext null
            synchronized(thumbCache) { thumbCache[key] = bmp }
            bmp
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
}
