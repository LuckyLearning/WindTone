package com.luckylearning.windtone.playback

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.luckylearning.windtone.data.DownloadedSongs
import com.luckylearning.windtone.data.DownloadsBus
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.docIdOf
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 边播边存：在线歌曲在播放缓存补齐后，复制一份完整音频到用户授权的 SAF 目录。
 *
 * 三条硬约束（与「在线缓存」严格分工）：
 *
 *  1. **只读取缓存，不碰播放链路**。保存过程不删缓存分片、不改队列、不替换当前媒体项，
 *     更不会把保存好的文件重新扫进曲库 —— 之后继续播这首歌仍然走原来的在线缓存。
 *  2. **不抢正在播的带宽**。播放中只有「缓存已经完整」时才做纯磁盘复制；缺口留给播放器
 *     自己填。只有切歌后这首不再出声，才用低限速补齐剩余分片再落盘。
 *  3. **失败静默**。目录权限失效、磁盘满、音源 403 都只记日志，播放照旧。默认是关的。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object OnlineSongSaver {

    private const val TAG = "OnlineSongSaver"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var downloader: ProgressiveDownloader? = null

    private val _status = MutableStateFlow("空闲")
    val status: StateFlow<String> = _status.asStateFlow()

    /** 开关 + 目录权限都在才真正动手；只有读权限不够，写文件必须有 WRITE 授权。 */
    fun enabled(context: Context, treeUri: String): Boolean {
        if (treeUri.isBlank() || !Prefs(context).onlineSaveToDirectory) return false
        return hasWritePermission(context, treeUri)
    }

    private fun hasWritePermission(context: Context, treeUri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == treeUri && it.isWritePermission
        }

    /** 缓存里已经有整首歌（字节数对得上内容长度）时可以只做磁盘复制，不必再联网。 */
    fun isCacheComplete(context: Context, song: Song): Boolean {
        if (!CachePrefetchPolicy.isRemote(song.uri.scheme)) return false
        val key = OnlineMediaCache.stableKey(song.uri) ?: return false
        return isCacheComplete(context, key)
    }

    private fun isCacheComplete(context: Context, key: String): Boolean {
        if (key.isBlank()) return false
        val cache = OnlineMediaCache.cacheForPrefetch(context) ?: return false
        val total = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(key))
        }.getOrDefault(-1L)
        if (total <= 0L) return false
        val cached = runCatching { cache.getCachedLength(key, 0L, total) }.getOrDefault(-1L)
        return cached >= total
    }

    /**
     * 排队保存一首。
     *
     * [completeCache] = false：只在缓存已完整时复制（播放中走这条）；
     * [completeCache] = true：允许补齐剩余分片后再复制（切歌后走这条）。
     */
    fun enqueue(context: Context, song: Song, treeUri: String, completeCache: Boolean) {
        if (!enabled(context, treeUri)) return
        if (!CachePrefetchPolicy.isRemote(song.uri.scheme)) return
        val key = OnlineMediaCache.stableKey(song.uri) ?: return
        synchronized(lock) {
            if (job?.isActive == true && currentKey == key) return
            val lastFailure = failedAt[key] ?: 0L
            if (SystemClock.elapsedRealtime() - lastFailure < 30_000L) return
            cancelLocked()
            currentKey = key
            _status.value = "保存中：${song.displayName.ifBlank { song.title }}"
            val app = context.applicationContext
            job = scope.launch {
                val saved = runCatching {
                    if (completeCache && !isCacheComplete(app, key)) ensureFullyCached(app, song, key)
                    copyToDirectory(app, song, key, treeUri)
                }.onFailure {
                    Log.d(TAG, "保存失败（不影响播放）: ${song.displayName}", it)
                    synchronized(lock) { failedAt[key] = SystemClock.elapsedRealtime() }
                }.getOrDefault(null)
                synchronized(lock) {
                    if (saved != null) failedAt.remove(key)
                    if (currentKey == key) {
                        currentKey = null
                        _status.value = when {
                            saved != null -> "已保存：$saved"
                            isCacheComplete(app, key) -> "已在下载目录"
                            else -> "保存失败"
                        }
                    }
                }
            }
        }
    }

    /** 设置关闭 / 服务退出时停止后台补齐，避免用户关掉功能后还在下载。 */
    fun cancelAll() {
        synchronized(lock) { cancelLocked() }
        _status.value = "空闲"
    }

    private fun cancelLocked() {
        job?.cancel()
        job = null
        // 协程取消打断不了阻塞中的网络 read，必须显式 stop 下载器
        downloader?.let { runCatching { it.cancel() } }
        downloader = null
        currentKey = null
    }

    @Volatile
    private var currentKey: String? = null

    /** 失败冷却：目录权限/Provider 不对时，别让 3 秒一次的 ticker 反复刷同一条错误 */
    private val failedAt = HashMap<String, Long>()

    /** 已确认存在的「目录|文件名」：避免同一首循环播放时每 3 秒查一次 SAF 子项 */
    private val savedNames = HashSet<String>()

    private fun marker(treeUri: String, name: String): String = "$treeUri|$name"

    /**
     * 把剩余分片补进同一个 SimpleCache。限速复用预取的令牌桶，且用独立的下载器实例：
     * `CachePrefetcher.cancel()` 是给「后面两首」用的，不能顺手停掉这首的保存任务。
     */
    private fun ensureFullyCached(context: Context, song: Song, key: String) {
        val cache = OnlineMediaCache.cacheForPrefetch(context) ?: return
        val uri = song.uri.buildUpon().fragment(null).build()
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                ThrottledSourceFactory(CachePrefetcher.httpFactory(song), CachePrefetchPolicy.BYTES_PER_SECOND),
            )
            .setCacheKeyFactory(object : CacheKeyFactory {
                override fun buildCacheKey(dataSpec: DataSpec): String = key
            })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloader = ProgressiveDownloader(MediaItem.fromUri(uri), factory)
        this.downloader = downloader
        try {
            downloader.download(Downloader.ProgressListener { _, _, _ -> })
        } finally {
            if (this.downloader === downloader) this.downloader = null
        }
        check(isCacheComplete(context, key)) { "缓存未补齐完整" }
    }

    /**
     * 从已完整的缓存分片顺序读出整首，写入 SAF 目录。
     *
     * SimpleCache 的分片文件名含起始位置，`span.file` 里的数据从 [CacheSpan.position] 开始；
     * 多个分片必须按位置拼接，直接拿某一个文件当整首会前功尽弃。
     */
    private fun copyToDirectory(context: Context, song: Song, key: String, treeUri: String): String? {
        val resolver = context.contentResolver
        if (!hasWritePermission(context, treeUri)) return null
        val name = outputFileName(song)
        val tree = Uri.parse(treeUri)
        val marker = marker(treeUri, name)
        if (marker in savedNames) return null
        val existing = existingNames(resolver, tree)
        if (name in existing) {
            savedNames += marker
            return null
        }

        val mime = mimeTypeOf(name, song.mimeType)
        val parentDocId = docIdOf(treeUri) ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        // createDocument 要的是「父文档 URI」，不能直接丢 tree URI 进去
        // （externalstorage 会报 IllegalArgumentException: Invalid URI）。
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        val docUri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        }.getOrNull() ?: runCatching {
            // 少数 Provider 不支持 createDocument，但接受往 children 里 insert
            val values = ContentValues().apply {
                put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
                put(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            }
            resolver.insert(children, values)
        }.getOrNull() ?: return null

        val cache = OnlineMediaCache.cacheForPrefetch(context) ?: return null
        var ok = false
        try {
            val out = resolver.openOutputStream(docUri, "w") ?: return null
            out.use { stream ->
                val bytes = copyCachedSpans(cache, key, stream)
                if (bytes <= 0L) throw IOException("缓存为空")
            }
            ok = true
            savedNames += marker
            DownloadedSongs.invalidate()
            DownloadsBus.notifyChanged()
            return name
        } finally {
            if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
        }
    }

    private fun existingNames(resolver: android.content.ContentResolver, tree: Uri): Set<String> {
        val docId = docIdOf(tree.toString()) ?: return emptySet()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val names = HashSet<String>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idx >= 0) {
                while (cursor.moveToNext() && names.size < 2_000) {
                    cursor.getString(idx)?.let { names += it }
                }
            }
        }
        return names
    }

    private fun copyCachedSpans(
        cache: Cache,
        key: String,
        out: OutputStream,
    ): Long {
        val total = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(key))
        }.getOrDefault(-1L)
        val spans = cache.getCachedSpans(key)
            .filter { it.isCached && !it.isHoleSpan }
        return copySpansTo(spans, total, out)
    }

    /**
     * 按 position 顺序拼接分片；[total] > 0 时顺便校验总长。
     *
     * 抽成 internal 纯函数是为了钉住一个真实踩过的坑：`span.file` 里装的就是从
     * `span.position` 开始的那一段，文件自身从 0 开始；早期实现按 position 去 skip，
     * 结果每个非首分片都被判成「文件不完整」，保存永远失败。
     */
    internal fun copySpansTo(
        spans: Collection<CacheSpan>,
        total: Long,
        out: OutputStream,
    ): Long {
        var expected = 0L
        var copied = 0L
        val buffer = ByteArray(64 * 1024)
        for (span in spans.sortedBy { it.position }) {
            if (span.position != expected) throw IOException("缓存分片不连续：${span.position} != $expected")
            val file = span.file ?: throw IOException("缓存分片文件不存在")
            val available = file.length()
            if (available <= 0L) {
                throw IOException(
                    "缓存分片文件不完整：pos=${span.position} spanLen=${span.length} " +
                        "fileLen=$available open=${span.isOpenEnded} total=$total name=${file.name}",
                )
            }
            val take = if (span.isOpenEnded) available else minOf(span.length, available)
            if (take <= 0L) continue
            FileInputStream(file).use { input ->
                var left = take
                while (left > 0L) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("缓存分片提前结束")
                    out.write(buffer, 0, n)
                    left -= n
                    copied += n
                }
            }
            expected += take
        }
        if (total > 0L && expected < total) throw IOException("缓存未完整：$expected/$total")
        return copied
    }

    // ------------------------------------------------------------ 纯函数（单测覆盖）

    /** 去掉文件系统与 SAF 都不友好的字符；保留中文，尽量贴近歌名。 */
    internal fun sanitizeFileName(raw: String, maxLength: Int = 80): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\r\n\t]"), "_")
            .trim()
            .trim('.')
        if (cleaned.isBlank() || cleaned.all { it == '_' || it.isWhitespace() }) return "song"
        return cleaned.take(maxLength)
    }

    /** 文件名后缀：优先原文件名，其次 URL 路径，再退到 mimeType，最后 mp3。 */
    internal fun extensionOf(song: Song): String {
        val fromName = song.displayName.substringAfterLast('.', "")
        if (fromName.length in 2..5 && fromName.all { it.isLetterOrDigit() }) return fromName.lowercase()
        val path = song.uri.buildUpon().fragment(null).build().path.orEmpty()
        val fromPath = path.substringAfterLast('.', "")
        if (fromPath.length in 2..5 && fromPath.all { it.isLetterOrDigit() }) return fromPath.lowercase()
        return when (song.mimeType?.lowercase()) {
            "audio/flac" -> "flac"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/x-ms-wma" -> "wma"
            else -> "mp3"
        }
    }

    /**
     * 稳定文件名 = 歌名 + 身份短哈希 + 后缀。
     *
     * 哈希来自 [Song.stableKey]：同一首换直链仍落同一个文件（重播不会越存越多）；
     * 不同平台恰好同名也不会互相覆盖。
     */
    internal fun outputFileName(song: Song): String {
        val base = song.displayName.ifBlank { song.title }
            .substringBeforeLast('.')
            .let { sanitizeFileName(it) }
        val hash = Integer.toHexString(song.stableKey.hashCode()).padStart(8, '0').take(8)
        return "$base-$hash.${extensionOf(song)}"
    }

    internal fun mimeTypeOf(fileName: String, fallback: String?): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "flac" -> "audio/flac"
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "wma" -> "audio/x-ms-wma"
            "mp3" -> "audio/mpeg"
            else -> fallback?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
        }
}
