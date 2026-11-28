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
import com.luckylearning.windtone.lyrics.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
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
    fun enqueue(
        context: Context,
        song: Song,
        treeUri: String,
        completeCache: Boolean,
        lyrics: Lyrics? = null,
    ) {
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
                    // 封面与标签内嵌进音频本身：下载目录只留「音频 + 歌词」两个文件。
                    // 封面取不到（网络失败 / 音源无封面）也不影响音频落盘。
                    val cover = runCatching {
                        com.luckylearning.windtone.data.CoverRepository.loadRaw(app, song)
                    }.getOrNull()
                    copyToDirectory(app, song, key, treeUri, lyrics, cover)
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
    private fun copyToDirectory(
        context: Context,
        song: Song,
        key: String,
        treeUri: String,
        lyrics: Lyrics?,
        cover: ByteArray?,
    ): String? {
        val resolver = context.contentResolver
        if (!hasWritePermission(context, treeUri)) return null
        val name = outputFileName(song)
        val tree = Uri.parse(treeUri)
        val marker = marker(treeUri, name)
        if (marker in savedNames) return null
        val existing = existingNames(resolver, tree)

        val parentDocId = docIdOf(treeUri) ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        // createDocument 要的是「父文档 URI」，不能直接丢 tree URI 进去
        // （externalstorage 会报 IllegalArgumentException: Invalid URI）。
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)

        // 音频已经在目录里：只补歌词旁车 + 清掉老版本遗留的 .wtmeta.json/.cover。
        // 元信息/封面已内嵌在音频里，不再依赖旁车。
        if (name in existing) {
            writeLyricsSidecar(resolver, parentUri, children, existing, name, lyrics)
            removeLegacySidecars(resolver, tree, children, existing, name)
            // v1.3.13 及之前的文件只有旁车、音频本身没有标签；升级后重新保存时补写一次，
            // 否则老文件播放时封面/歌手依旧空白（内嵌标签齐全的文件不动）。
            retrofitEmbeddedTags(resolver, tree, children, name, song, cover)
            savedNames += marker
            return null
        }

        val mime = mimeTypeOf(name, song.mimeType)
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
            // 先把完整音频拼到内存，内嵌标签/封面后再一次写出。
            // 上限用 Runtime.maxMemory() 的比例（红线：禁止写死 MB）：超大文件跳过注入，
            // 直接写原音频，宁可没标签也不能 OOM。
            val buffer = ByteArrayOutputStream()
            val bytes = copyCachedSpans(cache, key, buffer)
            if (bytes <= 0L) throw IOException("缓存为空")
            val budget = (Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(8L * 1024 * 1024)
            val tagged = if (bytes <= budget) {
                AudioTags.write(
                    buffer.toByteArray(),
                    AudioTags.Meta(
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        albumArtist = song.albumArtist,
                        year = song.year,
                        genre = song.genre,
                        trackNo = song.trackNo,
                        discNumber = song.discNumber,
                    ),
                    cover,
                )
            } else {
                buffer.toByteArray()
            }
            val out = resolver.openOutputStream(docUri, "w") ?: return null
            out.use { it.write(tagged) }
            ok = true
            writeLyricsSidecar(resolver, parentUri, children, existing, name, lyrics)
            removeLegacySidecars(resolver, tree, children, existing, name)
            savedNames += marker
            // 刚落盘的歌立刻把封面放进内存缓存：下载完点开就能看到封面，
            // 不必等列表重扫 + 再从文件解析。stableKey 与下载列表一致（都是文档 uri）。
            cover?.takeIf { it.isNotEmpty() }?.let { coverBytes ->
                runCatching {
                    val savedUri = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getDocumentId(docUri),
                    )
                    com.luckylearning.windtone.data.CoverRepository.rememberRaw(
                        song.copy(uri = savedUri), coverBytes,
                    )
                }
            }
            DownloadedSongs.invalidate()
            DownloadsBus.notifyChanged()
            return name
        } finally {
            if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
        }
    }

    /**
     * 只写歌词旁车 `.lrc`（元信息与封面已内嵌进音频）。
     *
     * MIME 用 `application/octet-stream` 而非 `text/plain`：ExternalStorageProvider 会按
     * MIME 自动补后缀，`text/plain` + `歌名.lrc` 会变成 `歌名.lrc.txt`，列表就认不出。
     */
    private fun writeLyricsSidecar(
        resolver: android.content.ContentResolver,
        parentUri: Uri,
        children: Uri,
        existing: Set<String>,
        audioName: String,
        lyrics: Lyrics?,
    ) {
        val base = audioName.substringBeforeLast('.')
        val lrc = lyrics?.takeIf { !it.isEmpty }?.let { lyricsToLrc(it) }
        if (!lrc.isNullOrBlank()) {
            writeTextIfAbsent(
                resolver, parentUri, children, existing,
                "$base.lrc", "application/octet-stream", lrc,
            )
        }
    }

    /** 清掉老版本遗留的 `.wtmeta.json` / `.cover` 旁车，让下载目录只剩「音频 + 歌词」。 */
    /**
     * 给老版本保存的文件补写内嵌标签/封面（下载入口侧）。
     *
     * v1.3.14 起新文件已经在落盘时内嵌，但用户目录里 v1.3.13 的老文件只有
     * `.wtmeta.json` / `.cover` 旁车；升级后重新播放保存走的是「文件已存在」分支，
     * 若不补写就永远读不到内嵌信息。这里先读回音频字节做幂等检测：
     * 只有缺歌名或缺封面才重写，已完整的文件一次 IO 都不多写。
     * 超大文件 / 读写失败一律静默跳过，绝不影响音频可播。
     */
    private fun retrofitEmbeddedTags(
        resolver: android.content.ContentResolver,
        tree: Uri,
        children: Uri,
        name: String,
        song: Song,
        cover: ByteArray?,
    ) {
        val docId = findDocId(resolver, children, name) ?: return
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val budget = (Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(8L * 1024 * 1024)
        val original = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (total + n > budget) return
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        } ?: return
        if (original.isEmpty()) return

        val mime = mimeTypeOf(name, song.mimeType)
        val info = com.luckylearning.windtone.data.EmbeddedAudioInfo.parse(
            original, com.luckylearning.windtone.data.AudioFormats.extensionOf(name), mime, true,
        )
        val need = info?.title.isNullOrBlank() ||
            (cover?.isNotEmpty() == true && info?.picture == null)
        if (!need) return

        val tagged = AudioTags.write(
            original,
            AudioTags.Meta(
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumArtist = song.albumArtist,
                year = song.year,
                genre = song.genre,
                trackNo = song.trackNo,
                discNumber = song.discNumber,
            ),
            cover,
        )
        if (tagged.size == original.size && tagged.contentEquals(original)) return
        runCatching {
            resolver.openOutputStream(uri, "w")?.use { it.write(tagged) }
        }
        cover?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching {
                com.luckylearning.windtone.data.CoverRepository.rememberRaw(song.copy(uri = uri), bytes)
            }
        }
    }

    private fun removeLegacySidecars(
        resolver: android.content.ContentResolver,
        tree: Uri,
        children: Uri,
        existing: Set<String>,
        audioName: String,
    ) {
        val base = audioName.substringBeforeLast('.')
        val legacy = listOf("$base.wtmeta.json", "$base.cover", "$base.wtmeta.json.txt", "$base.cover.txt")
        for (name in legacy) {
            if (name !in existing) continue
            val docId = findDocId(resolver, children, name) ?: continue
            runCatching {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            }
        }
    }

    /** 从目录子项里找某个显示名对应的 docId（清理旁车用）。 */
    private fun findDocId(resolver: android.content.ContentResolver, children: Uri, name: String): String? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIdx >= 0 && nameIdx >= 0) {
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == name) return cursor.getString(idIdx)
                }
            }
        }
        return null
    }

    private fun writeTextIfAbsent(
        resolver: android.content.ContentResolver,
        parentUri: Uri,
        children: Uri,
        existing: Set<String>,
        name: String,
        mime: String,
        text: String,
    ) {
        if (name in existing) return
        runCatching {
            val doc = DocumentsContract.createDocument(resolver, parentUri, mime, name)
                ?: resolver.insert(
                    children,
                    ContentValues().apply {
                        put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
                        put(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
                    },
                )
                ?: return
            resolver.openOutputStream(doc, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.d(TAG, "旁车写入失败：$name", it) }
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

    /** 旁车元数据：只放播放页信息能直接用的字段，版本号留作以后扩展。 */
    internal fun metadataJson(song: Song): String = org.json.JSONObject().apply {
        put("v", 1)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("albumArtist", song.albumArtist)
        put("durationMs", song.durationMs)
        put("year", song.year)
        put("genre", song.genre)
        put("trackNo", song.trackNo)
        put("discNumber", song.discNumber)
        put("mimeType", song.mimeType)
        put("originalKey", song.stableKey)
    }.toString()

    /** 把当前歌词写成标准 LRC；无时间轴就写纯文本，交给读取端按纯文本解析。 */
    internal fun lyricsToLrc(lyrics: Lyrics): String {
        val sb = StringBuilder()
        lyrics.title?.takeIf { it.isNotBlank() }?.let { sb.append("[ti:").append(it).append("]\n") }
        lyrics.artist?.takeIf { it.isNotBlank() }?.let { sb.append("[ar:").append(it).append("]\n") }
        lyrics.album?.takeIf { it.isNotBlank() }?.let { sb.append("[al:").append(it).append("]\n") }
        for (line in lyrics.lines) {
            if (lyrics.synced) sb.append(lrcTime(line.timeMs))
            sb.append(line.text).append('\n')
        }
        return sb.toString()
    }

    private fun lrcTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val hundredths = (safe % 1000) / 10
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

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
