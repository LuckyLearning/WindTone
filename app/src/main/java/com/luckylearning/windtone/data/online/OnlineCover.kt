package com.luckylearning.windtone.data.online

import android.content.Context
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 封面地址（在线歌才有；非在线 / 取不到返回 null） */
    suspend fun urlFor(context: Context, song: Song): String? {
        if (!song.isOnline) return null
        val track = runCatching { OnlineLibrary(context).trackOf(song.stableKey) }.getOrNull()
            ?: return null
        track.coverUrl?.takeIf { it.startsWith("http") }?.let { return it }

        synchronized(urlCache) { urlCache[track.stableKey] }?.let { return it }
        val resolved = withTimeoutOrNull(TIMEOUT_MS) { runCatching { resolveUrl(track) }.getOrNull() }
            ?.takeIf { it.startsWith("http") } ?: return null
        synchronized(urlCache) { urlCache[track.stableKey] = resolved }
        return resolved
    }

    /** 下载封面字节（受 [MAX_BYTES] 限制，超限视为失败 —— 播放页封面不值得为它爆堆） */
    suspend fun bytes(context: Context, song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val url = urlFor(context, song) ?: return@withContext null
        withTimeoutOrNull(TIMEOUT_MS + 3_000L) {
            runCatching { download(url) }.getOrNull()
        }
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        return client.newCall(request).execute().use { response ->
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
            val url = track.coverUrl?.takeIf { it.startsWith("http") }
                ?: withTimeoutOrNull(TIMEOUT_MS) { runCatching { resolveUrl(track) }.getOrNull() }
                    ?.takeIf { it.startsWith("http") }
                ?: return@withContext null
            val bytes = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { download(url) }.getOrNull()
            } ?: return@withContext null
            val bmp = com.luckylearning.windtone.data.CoverRepository.decodeScaled(bytes, targetPx)
                ?: return@withContext null
            synchronized(thumbCache) { thumbCache[key] = bmp }
            bmp
        }

    // ---------------------------------------------------------------- 分派

    /**
     * 只有酷我需要二次解析：`pic.web` 返回的是图片 URL 的**文本**。
     * 酷狗的 `{size}` 模板已在搜索阶段替换好（见 `BuiltInSearch.kgCover`），这里不必再处理。
     */
    private fun resolveUrl(track: OnlineTrack): String? = when (track.sourceId) {
        "kw" -> {
            val url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic" +
                "&pictype=500&size=500&rid=${track.songId}"
            val request = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.trim()?.takeIf { it.startsWith("http") }
            }
        }
        // 其余三家走 raw 里的 img 字段（老存档可能没有 coverUrl，但 raw 里一直有）
        else -> runCatching { JSONObject(track.raw).optString("img") }.getOrNull()
            ?.takeIf { it.startsWith("http") }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
}
