package com.luckylearning.windtone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 内嵌封面（专辑图）加载与缓存。
 * 直接使用 MediaMetadataRetriever 抽取原始字节，避免引入额外的图片加载框架。
 */
object CoverRepository {

    private val empty = ByteArray(0)

    private val cache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    /**
     * 解码后的位图缓存。
     *
     * 原先只缓存原始字节，列表每次滚动回来都要重新解码——长列表滑动掉帧的主因。
     * 这里按 `byteCount`（真实像素内存）计量，上限取堆内存的 1/8，夹在 8~32MB 之间。
     */
    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private fun bitmapCacheSize(): Int {
        val max = Runtime.getRuntime().maxMemory() / 8
        return max.coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    /**
     * 取「已按目标边长解码且带缓存」的封面位图。
     * 键为 `stableKey@目标边长`，同一首歌在列表（48dp）与播放页（320dp）各缓存一份，互不干扰。
     */
    suspend fun cover(context: Context, song: Song, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return null
        val key = "${song.stableKey}@$targetPx"
        bitmapCache.get(key)?.let { return it }
        val raw = loadRaw(context, song) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeScaled(raw, targetPx) } ?: return null
        bitmapCache.put(key, bmp)
        return bmp
    }

    suspend fun loadRaw(context: Context, song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val key = song.stableKey
        cache.get(key)?.let { return@withContext it.takeIf { b -> b.isNotEmpty() } }
        val bytes = runCatching {
            MetadataReader.embeddedPicture(context, song.uri)
                ?.takeIf { it.size in 1..(8 * 1024 * 1024) }
                ?: empty
        }.getOrDefault(empty)
        cache.put(key, bytes)
        bytes.takeIf { it.isNotEmpty() }
    }

    /** 等比缩放到目标边长，避免在列表里解码超大封面 */
    fun decodeScaled(raw: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= targetPx && h / 2 >= targetPx) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) }.getOrNull()
    }

    fun clear() {
        cache.evictAll()
        bitmapCache.evictAll()
    }

    /** 单曲封面缓存淘汰（删除歌曲时调用，前缀匹配两种尺寸的键） */
    fun evict(stableKey: String) {
        for (snapshot in bitmapCache.snapshot().keys) {
            if (snapshot.startsWith("$stableKey@")) bitmapCache.remove(snapshot)
        }
        cache.remove(stableKey)
    }
}
