package com.luckylearning.windtone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.diag.BitmapMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 内嵌封面（专辑图）加载与缓存。三级结构：
 *
 *  1. **内存位图**（按像素计量，堆的 1/8）—— 滚动时零成本；
 *  2. **磁盘缓存** `cacheDir/covers/<歌曲id>-<边长>.jpg` —— 进程重启后仍在，冷启动后
 *     长列表滚动不再逐首解码，这是列表流畅度的关键（旧实现只有内存一级）；
 *  3. 原始来源解码 —— 内嵌图优先，缺失时回退同目录 folder.jpg / cover.jpg / AlbumArt.jpg（见 [FolderCover]）。
 *
 * 磁盘缓存是**派生数据**：可随时整体删除，不影响任何用户数据。
 * 用 `cacheDir` 而不是 `filesDir`：系统在存储紧张时可以自行回收。
 *
 * ## 内存预算（2026-10-29 收紧，v1.2.12）
 *
 * 两级内存缓存都**按堆比例**取上限，不再有固定常量：
 *  - 位图：`maxMemory/8`，夹 8~32MB（按 `byteCount` 计量，即真实像素内存）；
 *  - 原始字节：`maxMemory/16`，夹 2~6MB（旧实现固定 16MB —— 在车机
 *    192MB 堆上占 8%，而它只是通知栏封面与主题取色的中转，收益远不值这个价）。
 *
 * 另有两条配套纪律：
 *  - **解码尺寸 = 显示尺寸**（见 [targetPx]）。旧实现乘了 2，等于按 4 倍面积解码，
 *    还把「目标大于原图 → 不降采样 → 全尺寸解码」这条路径暴露出来（3000px 内嵌图
 *    在播放页可解出 18MB）。
 *  - **同一键并发只解一次**（[cover] 的 in-flight 表）。列表现项、迷你播放条、
 *    播放页会同时请求同一首歌，旧实现会并发解 2~3 份，峰值瞬时翻倍。
 *
 * 系统内存紧张时由 [trim] 分级释放（`MusicApp.onTrimMemory` 调用）。
 */
object CoverRepository {

    private const val TAG = "CoverRepository"
    private const val DIR_NAME = "covers"

    /** 磁盘缓存上限 50MB，超出后按最后访问时间淘汰到 80% */
    private const val DISK_LIMIT_BYTES = 50L * 1024 * 1024
    private const val DISK_TRIM_RATIO = 0.8
    /** 多少次写盘才做一次容量检查 */
    private const val DISK_TRIM_EVERY_WRITES = 64
    /** 两次容量检查的最小间隔 */
    private const val DISK_TRIM_MIN_INTERVAL_MS = 30_000L
    /** 通知栏封面压到这个尺寸（大图标实际只用 256px，留一倍余量给不同 ROM） */
    private const val NOTIFICATION_COVER_PX = 512
    /** 小于这个体积的封面不必再编解码一次 */
    private const val NOTIFICATION_COVER_INLINE_BYTES = 64 * 1024

    /**
     * 「大图」门槛：显示像素超过它才给 1.25 倍解码余量。
     * 512px 之上只有播放页封面与车机唱片中心贴纸，列表缩略图远低于此。
     */
    private const val LARGE_COVER_PX = 512

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cacheDir: File? = null

    private val empty = ByteArray(0)

    /** 原始封面字节。上限按堆比例，见类文档「内存预算」 */
    private val cache = object : LruCache<String, ByteArray>(rawCacheSize()) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    /**
     * 在途解码表：同一 `key` 并发只解一份。
     *
     * 触发场景很常见 —— 列表现项、底部迷你播放条、播放页副标题会同时请求同一首歌，
     * 旧实现三方各解一次，峰值瞬时翻 3 倍。
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /**
     * 单飞任务的作用域。
     *
     * **刻意不挂调用方的 Job**：第一个请求方离开（滚出屏幕）时只该取消它自己的等待，
     * 不该把别的等待者一起拖死。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 解码后的位图缓存。
     * 按 `byteCount`（真实像素内存）计量，上限取堆内存的 1/8，夹在 8~32MB 之间。
     */
    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /**
     * 解码计量（2026-10-31）。
     *
     * 起因：车机诊断包里 `native 堆 232 MB` 与 169 首曲库严重不成比例，而位图缓存
     * 只有 0.8 MB —— 说明占用不在「缓存里」，要么在解码过程，要么根本不在本模块。
     * 三个计数器用来把这条路走到头：
     *
     *  - `decodeFromDisk` / `decodeFromSource`：各走了多少次解码（磁盘派生图 vs 原始容器）；
     *  - `decodedBytes`：累计产出多少像素字节 —— **这不是常驻内存**，是解码吞吐，
     *    用来判断「一直在解」；
     *  - `peakDecodedBytes`：单张最大产出，用来抓异常大图（内嵌 3000px 之类）。
     */
    /**
     * 磁盘容量检查节流（2026-10-31）。
     *
     * 旧实现每写一张新封面就扫一遍缓存目录：`sumOf { length() }` 是 N 次 `stat()`，
     * `sortBy { lastModified() }` 又是 O(N log N) 次 —— 50MB 上限 / 30KB 一张
     * ≈ 1700 个文件，即**每解一张封面约两万次系统调用**。首次滚完 169 首就是
     * 三百多万次，曲库涨到数千首是 O(N² log N)。eMMC 上单次 stat 20~100µs，
     * 这就是「越用越卡」里最纯粹的那一块浪费。
     *
     * 改成「累计写够 [DISK_TRIM_EVERY_WRITES] 张、或距上次检查超过
     * [DISK_TRIM_MIN_INTERVAL_MS]」才扫一次。最坏情况超出上限 64 张 × 30KB ≈ 2MB，
     * 相对 50MB 上限可以忽略。
     */
    private val diskWritesSinceTrim = AtomicInteger()
    private val diskTrimAt = AtomicLong()

    private val decodeFromDisk = AtomicLong()
    private val decodeFromSource = AtomicLong()
    private val decodedBytes = AtomicLong()

    @Volatile
    private var peakDecodedBytes = 0

    private fun recordDecode(fromDisk: Boolean, byteCount: Int) {
        if (fromDisk) decodeFromDisk.incrementAndGet() else decodeFromSource.incrementAndGet()
        decodedBytes.addAndGet(byteCount.toLong())
        if (byteCount > peakDecodedBytes) peakDecodedBytes = byteCount
    }

    private fun bitmapCacheSize(): Int = bitmapCacheSizeFor(Runtime.getRuntime().maxMemory())

    /**
     * 位图缓存上限：堆的 1/8，夹 8~32MB。
     *
     * 抽成「接收 heapMax 的纯函数」是为了能按不同设备直接断言 —— 纯 JVM 单测里
     * `android.util.LruCache` 是 stub（`testOptions.unitTests.isReturnDefaultValues = true`），
     * 读 `maxSize()` 只会得到 0，测不出任何东西。
     */
    internal fun bitmapCacheSizeFor(heapMax: Long): Int =
        (heapMax / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()

    /**
     * 原始字节缓存上限：堆的 1/32，夹 1.5~3MB（v1.2.41 下调，原 1/16 夹 2~6MB）。
     *
     * 依据是车机实测：v1.2.40 诊断包里 **原始缓存 4.1/6.0MB（68% 满）而位图缓存
     * 0.3/32MB（1%）** —— 装的是压缩字节，占着 Java 堆却省不了解码时间，
     * 而真正省 CPU 的位图缓存几乎闲置。命中率也只有 5%（20 次请求 1 次命中），
     * 说明「同一首歌的原始字节被反复要」这件事基本不发生。
     *
     * 它的唯一用途是通知栏封面和主题取色，两者都能从磁盘缓存重新解码（几十毫秒），
     * 不值得为它常驻 6MB。
     */
    private fun rawCacheSize(): Int = rawCacheSizeFor(Runtime.getRuntime().maxMemory())

    /** 见 [rawCacheSize]；堆的 1/32，夹 1.5~3MB */
    internal fun rawCacheSizeFor(heapMax: Long): Int =
        (heapMax / 32).coerceIn(1536L * 1024, 3L * 1024 * 1024).toInt()

    /** 由 [com.luckylearning.windtone.MusicApp] 在启动时注入；未注入时退化为纯内存缓存 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        cacheDir = File(context.applicationContext.cacheDir, DIR_NAME).apply {
            runCatching { if (!exists()) mkdirs() }
        }
        // 启动时做一次体积修剪，避免上次异常退出留下的超额缓存一直占着空间
        Thread { runCatching { trimDisk(force = true) } }.apply { isDaemon = true }.start()
    }

    /**
     * 取「已按目标边长解码且带缓存」的封面位图。
     * 键为 `stableKey@目标边长`，同一首歌在列表与播放页各缓存一份。
     *
     * 并发语义：同一 `key` 的并发调用共享一次解码（[inFlight]），谁抢到谁去解，
     * 其余人等结果。等待方被取消不会影响解码本身。
     */
    suspend fun cover(context: Context, song: Song, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return null
        val key = "${song.stableKey}@$targetPx"
        bitmapCache.get(key)?.let { return it }

        // 快速路径：已有同键任务在跑，直接等
        inFlight[key]?.let { return runCatching { it.await() }.getOrNull() }

        val fresh = scope.async(start = CoroutineStart.LAZY) { decodeInto(key, context, song, targetPx) }
        // 完成后自摘表项；用 remove(key, fresh) 保证不会误删别人的任务
        fresh.invokeOnCompletion { inFlight.remove(key, fresh) }

        val existing = inFlight.putIfAbsent(key, fresh)
        if (existing != null) {
            // 抢输了：丢弃自己这个还没启动的任务，改等别人的结果
            fresh.cancel()
            return runCatching { existing.await() }.getOrNull()
        }
        fresh.start()
        return runCatching { fresh.await() }.getOrNull()
    }

    /** [cover] 的实际加载：磁盘命中 → 原始字节 → 解码 → 写盘。只由单飞任务调用 */
    private suspend fun decodeInto(key: String, context: Context, song: Song, targetPx: Int): Bitmap? {
        val file = diskFile(song, targetPx)
        if (file != null && file.exists()) {
            val cached = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                ?.let { BitmapMeter.track(it) }
            if (cached != null) {
                recordDecode(fromDisk = true, byteCount = cached.byteCount)
                bitmapCache.put(key, cached)
                return cached
            }
            // 文件损坏：删掉，走后续流程重新生成
            runCatching { file.delete() }
        }

        val raw = loadRaw(context, song) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeScaled(raw, targetPx) } ?: return null
        recordDecode(fromDisk = false, byteCount = bmp.byteCount)
        bitmapCache.put(key, bmp)
        writeDisk(file, bmp)
        return bmp
    }

    /**
     * 原始封面字节（通知栏 / 主题取色用），带内存缓存。
     *
     * 顺序：**内嵌图 → 同目录 folder/cover/AlbumArt.jpg（[FolderCover]）→ 空**。
     * 空结果也会进内存缓存（负缓存），避免同一首歌反复打开 MediaMetadataRetriever / 扫目录。
     */
    suspend fun loadRaw(context: Context, song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val key = song.stableKey
        cache.get(key)?.let { return@withContext it.takeIf { b -> b.isNotEmpty() } }
        val bytes = runCatching {
            MetadataReader.embeddedPicture(context, song.uri)
                ?.takeIf { it.size in 1..(8 * 1024 * 1024) }
                ?: FolderCover.readBytes(song)
                ?: empty
        }.getOrDefault(empty)
        cache.put(key, bytes)
        bytes.takeIf { it.isNotEmpty() }
    }

    /** 已经从文件头读到的封面，直接放进内存缓存，避免再去打开远程地址。 */
    fun rememberRaw(song: Song, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        cache.put(song.stableKey, bytes)
    }

    /**
     * 等比缩放到目标边长。
     *
     * **两段式**：先用 [sampleSizeFor] 让 `inSampleSize` 粗降到「长边 ≥ target」，
     * 再用 [fitSize] 精确缩到 target。第二段不是优化而是必须 —— 只用 `inSampleSize`
     * 时，目标值一旦大于原图就完全不降采样（旧实现会乘 2，更容易触发），
     * 3000px 的内嵌封面会按全尺寸解出来，单张 18MB。
     */
    fun decodeScaled(raw: ByteArray, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) }.getOrNull()
            ?: return null

        val (w, h) = fitSize(decoded.width, decoded.height, targetPx)
        if (w == decoded.width && h == decoded.height) return BitmapMeter.track(decoded)
        val scaled = runCatching { Bitmap.createScaledBitmap(decoded, w, h, true) }.getOrNull()
            ?: return BitmapMeter.track(decoded)
        // 像素已经搬到新位图，旧的大图立刻还给堆（`!==` 兜住「尺寸本就相同、返回自身」的情况）
        if (scaled !== decoded) decoded.recycle()
        // 只在这里登记：所有解码路径（含通知封面 downscaleForNotification）都经过本方法，
        // 在调用方再登记一次会重复计数
        return BitmapMeter.track(scaled)
    }

    /**
     * 给「通知栏 / 锁屏」用的封面字节。
     *
     * 内嵌封面常见 1000~3000px、1~8MB，而通知栏大图标只有 256px。把原始字节原样
     * 塞进 MediaItem 有三重代价，而且都会随播放时长反复发生：
     *   1. 字节常驻在当前播放项的 `MediaMetadata` 上（可达 8MB）；
     *   2. 锁屏歌词**每换一行**就 `replaceMediaItem`，`buildUpon()` 会继承
     *      `artworkData`，整份字节随 Binder 事务再搬一次 —— 一首 60 行的歌
     *      = 60 次，8MB 封面还会直接撞 `TransactionTooLargeException`
     *      （调用点包着 `runCatching`，表现为「大封面歌曲的锁屏歌词不更新」）；
     *   3. 通知重建时按全尺寸解码，几 MB 的 JPEG 解出来是几十 MB 的位图。
     * 这里压到 [NOTIFICATION_COVER_PX] / JPEG 85，典型结果 20~60KB，小两个数量级。
     *
     * 已经够小的字节直接原样返回，省掉一次编解码往返。
     *
     * 阻塞函数：调用方负责切到 `Dispatchers.Default`。
     */
    fun downscaleForNotification(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        if (raw.size <= NOTIFICATION_COVER_INLINE_BYTES) return raw
        val bmp = decodeScaled(raw, NOTIFICATION_COVER_PX) ?: return raw
        return try {
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) return raw
            val packed = out.toByteArray()
            // 压完反而更大（极小图 / 已高度压缩的 JPEG）就沿用原件，不做负优化
            if (packed.isEmpty() || packed.size >= raw.size) raw else packed
        } catch (_: Throwable) {
            raw
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 显示像素 → 解码像素。
     *
     * **不再乘 2**：`roundToPx()` 返回的已经是物理像素，乘 2 等于按 4 倍面积解码 ——
     * 1080p 手机上播放页封面（292dp ≈ 803px）会解成 1606px、5.2MB，列表缩略图
     * （52dp ≈ 143px）解成 286px。改成 1:1 之后，同一份 32MB 位图预算能放
     * 约 4 倍张数，长列表滚过 200 首才开始淘汰，这是「滚动掉帧」的主因之一。
     *
     * 只给大图（≥ [LARGE_COVER_PX]）留 1.25 倍余量：播放页 / 车机唱片在悬浮窗、
     * 分屏、投屏时可能被放大显示，这点余量换「放大不变糊」，面积代价 1.56 倍。
     */
    fun targetPxFor(displayPx: Int): Int {
        if (displayPx <= 0) return 0
        val scale = if (displayPx >= LARGE_COVER_PX) 1.25f else 1f
        return (displayPx * scale).roundToInt()
    }

    /**
     * `inSampleSize` 取值：解出来的**长边**落在 `[target, 2×target)`。
     *
     * 与旧实现按「宽和高都 ≥ 2×target」判定的差别：极端比例（长方形专辑图、
     * 扫描残图）在旧判定下永远不降采样，直接全尺寸解码。
     */
    internal fun sampleSizeFor(srcW: Int, srcH: Int, targetPx: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (max(srcW, srcH) / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    /**
     * 精确目标尺寸：长边对齐 [targetPx]，短边等比（至少 1px）。
     * 原图比目标还小时**不放大** —— 放大只会变糊，且白占内存。
     */
    internal fun fitSize(srcW: Int, srcH: Int, targetPx: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0 || targetPx <= 0) return srcW to srcH
        val longSide = max(srcW, srcH)
        if (longSide <= targetPx) return srcW to srcH
        val scale = targetPx.toFloat() / longSide
        val w = (srcW * scale).roundToInt().coerceAtLeast(1)
        val h = (srcH * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }

    /** 只清内存（进程级），磁盘缓存保留 */
    fun clear() {
        cache.evictAll()
        bitmapCache.evictAll()
    }

    /**
     * 按内存压力分级释放**内存**缓存。磁盘缓存一律保留 —— 它就是「丢了内存还能快速回来」
     * 的那条退路，在这里删掉等于把退路一起堵上。
     *
     * 调用方是 `MusicApp.onTrimMemory`；本类不直接实现 `ComponentCallbacks2`，
     * 是为了让这份逻辑能在纯 JVM 单测里跑（不依赖 Activity 的常量语义）。
     */
    fun trim(level: CoverTrimLevel) {
        bitmapCache.evictAll()
        if (level == CoverTrimLevel.RUNNING_LOW) cache.evictAll()
    }

    /** 缓存画像（诊断包 `[内存]` 段与设置页展示用） */
    data class Stats(
        val bitmapBytes: Int,
        val bitmapMax: Int,
        val bitmapHits: Int,
        val bitmapMisses: Int,
        val rawBytes: Int,
        val rawMax: Int,
        val rawHits: Int,
        val rawMisses: Int,
        val diskBytes: Long,
        // 解码计量（2026-10-31）：见 recordDecode 的注释。给默认值是为了不打断既有构造点。
        val decodeFromDisk: Long = 0L,
        val decodeFromSource: Long = 0L,
        val decodedBytes: Long = 0L,
        val peakDecodedBytes: Int = 0,
    )

    fun stats(): Stats = Stats(
        bitmapBytes = bitmapCache.size(),
        bitmapMax = bitmapCache.maxSize(),
        bitmapHits = bitmapCache.hitCount(),
        bitmapMisses = bitmapCache.missCount(),
        rawBytes = cache.size(),
        rawMax = cache.maxSize(),
        rawHits = cache.hitCount(),
        rawMisses = cache.missCount(),
        diskBytes = diskUsageBytes(),
        decodeFromDisk = decodeFromDisk.get(),
        decodeFromSource = decodeFromSource.get(),
        decodedBytes = decodedBytes.get(),
        peakDecodedBytes = peakDecodedBytes,
    )

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
            trimDisk(force = false)
        }
    }

    /**
     * 超过上限时按最后修改时间淘汰到 80%。
     *
     * @param force true 时无视节流立刻检查（`clear()` 之类需要马上看到结果的场合）。
     */
    private fun trimDisk(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force) {
            val writes = diskWritesSinceTrim.incrementAndGet()
            val last = diskTrimAt.get()
            if (writes < DISK_TRIM_EVERY_WRITES && now - last < DISK_TRIM_MIN_INTERVAL_MS) return
        }
        diskWritesSinceTrim.set(0)
        diskTrimAt.set(now)
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

/**
 * 内存压力等级。
 *
 * 刻意不复用 Android 的 `ComponentCallbacks2.TRIM_MEMORY_*` 常量：那些常量的数值
 * **不随严重程度单调递增**（`RUNNING_CRITICAL = 15` 比 `UI_HIDDEN = 20` 更急），
 * 直接用大小比较判定会写错。这里只留两档，映射在 `MusicApp.onTrimMemory` 里一次做完。
 */
enum class CoverTrimLevel {
    /** 界面已不可见：位图可以全丢，回来时靠磁盘缓存重解 */
    UI_HIDDEN,

    /** 系统内存已经吃紧：连原始字节一起丢 */
    RUNNING_LOW,
}
