package com.luckylearning.windtone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内嵌封面（专辑图）加载与缓存。三级结构：
 *
 *  1. **内存位图**（按像素计量，堆的 1/8）—— 滚动时零成本；
 *  2. **磁盘缓存** `cacheDir/covers/<歌曲id>-<边长>.jpg` —— 进程重启后仍在，冷启动后
 *     长列表滚动不再逐首解码，这是列表流畅度的关键（旧实现只有内存一级）；
 *  3. 原始文件解码 —— 只在都没命中时发生。
 *
 * 磁盘缓存是**派生数据**：可随时整体删除，不影响任何用户数据。
 * 用 `cacheDir` 而不是 `filesDir`：系统在存储紧张时可以自行回收。
 */
object CoverRepository {

    private const val TAG = "CoverRepository"
    private const val DIR_NAME = "covers"

    /** 磁盘缓存上限 50MB，超出后按最后访问时间淘汰到 80% */
    private const val DISK_LIMIT_BYTES = 50L * 1024 * 1024
    private const val DISK_TRIM_RATIO = 0.8

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cacheDir: File? = null

    private val empty = ByteArray(0)

    private val cache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    /**
     * 解码后的位图缓存。
     * 按 `byteCount`（真实像素内存）计量，上限取堆内存的 1/8，夹在 8~32MB 之间。
     */
    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private fun bitmapCacheSize(): Int {
        val max = Runtime.getRuntime().maxMemory() / 8
        return max.coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    /** 由 [com.luckylearning.windtone.MusicApp] 在启动时注入；未注入时退化为纯内存缓存 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        cacheDir = File(context.applicationContext.cacheDir, DIR_NAME).apply {
            runCatching { if (!exists()) mkdirs() }
        }
        // 启动时做一次体积修剪，避免上次异常退出留下的超额缓存一直占着空间
        Thread { runCatching { trimDisk() } }.apply { isDaemon = true }.start()
    }

    /**
     * 取「已按目标边长解码且带缓存」的封面位图。
     * 键为 `stableKey@目标边长`，同一首歌在列表（48dp）与播放页（320dp）各缓存一份。
     */
    suspend fun cover(context: Context, song: Song, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return null
        val key = "${song.stableKey}@$targetPx"
        bitmapCache.get(key)?.let { return it }

        val file = diskFile(song, targetPx)
        if (file != null && file.exists()) {
            val cached = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            }
            if (cached != null) {
                bitmapCache.put(key, cached)
                return cached
            }
            // 文件损坏：删掉，走后续流程重新生成
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
        }

        val raw = loadRaw(context, song) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeScaled(raw, targetPx) } ?: return null
        bitmapCache.put(key, bmp)
        writeDisk(file, bmp)
        return bmp
    }

    /** 原始封面字节（通知栏 / 主题取色用），带内存缓存 */
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

    /** 只清内存（进程级），磁盘缓存保留 */
    fun clear() {
        cache.evictAll()
        bitmapCache.evictAll()
    }

    /** 连磁盘一起清（设置页「清空封面缓存」） */
    fun clearAll() {
        clear()
        val dir = cacheDir ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
            .onFailure { Log.w(TAG, "清理封面磁盘缓存失败", it) }
    }

    /** 当前磁盘缓存占用（字节），设置页展示用 */
    fun diskUsageBytes(): Long {
        val dir = cacheDir ?: return 0L
        return runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    /** 单曲封面缓存淘汰（删除歌曲时调用） */
    fun evict(song: Song) {
        val prefix = "${diskKey(song)}-"
        for (snapshot in bitmapCache.snapshot().keys) {
            if (snapshot.startsWith("${song.stableKey}@")) bitmapCache.remove(snapshot)
        }
        cache.remove(song.stableKey)
        cacheDir?.listFiles { f -> f.name.startsWith(prefix) }?.forEach { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------- 磁盘

    private fun diskKey(song: Song): String =
        if (song.dbId != 0L) song.dbId.toString() else "u${song.stableKey.hashCode()}"

    private fun diskFile(song: Song, targetPx: Int): File? {
        val dir = cacheDir ?: return null
        return File(dir, "${diskKey(song)}-$targetPx.jpg")
    }

    private suspend fun writeDisk(file: File?, bitmap: Bitmap) {
        if (file == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            }.onFailure { Log.w(TAG, "写封面缓存失败", it) }
            trimDisk()
        }
    }

    /** 超过上限时按最后修改时间淘汰到 80% */
    private fun trimDisk() {
        val dir = cacheDir ?: return
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT_BYTES) return
        val target = (DISK_LIMIT_BYTES * DISK_TRIM_RATIO).toLong()
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= target) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= len
        }
    }
}
