package com.luckylearning.windtone.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「边播边存」列表的数据源：扫描用户选定的下载目录（SAF 树），把音频文件直接变成
 * 可播放的 [Song]。
 *
 * 关键取舍：
 *  - **不写曲库**。保存本身不改用户曲库，也不触发扫描；这个列表是一个虚拟列表，
 *    文件在目录里怎么变，它下次刷新就跟着变。
 *  - **只读 SAF**。用 DocumentsProvider 查询，不依赖 MANAGE_EXTERNAL_STORAGE；
 *    拷贝到手机 / 车机后只要目录授权还在就能播。
 *  - 文件名里的身份哈希（`歌名-4e5a647a.mp3`）只用于「同一首不重复落盘」，
 *    展示时剥掉，列表里仍是干净歌名。
 */
object DownloadedSongs {

    private const val TAG = "DownloadedSongs"
    private const val MAX_SONGS = 500
    private const val CACHE_MS = 2_000L
    // 歌词旁车是下载目录里唯一的附加文件；.lrc.txt 兼容老版本被 SAF 补后缀的文件。
    private val LRC_EXTENSIONS = listOf(".lrc", ".lrc.txt", ".krc", ".yrc", ".txt")

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma",
        "ape", "dsf", "dff", "tak", "tta", "alac", "aiff", "aif", "mka",
    )

    private var cachedTree: String? = null
    private var cachedAt = 0L
    private var cached: List<Song> = emptyList()

    /** 清掉短缓存（保存完成 / 用户在设置里换了目录时调用） */
    fun invalidate() {
        synchronized(this) {
            cachedTree = null
            cachedAt = 0L
            cached = emptyList()
        }
    }

    suspend fun load(context: Context, treeUri: String): List<Song> = withContext(Dispatchers.IO) {
        if (treeUri.isBlank()) return@withContext emptyList()
        synchronized(this@DownloadedSongs) {
            if (cachedTree == treeUri && System.currentTimeMillis() - cachedAt < CACHE_MS) {
                return@withContext cached
            }
        }
        val list = runCatching { scan(context, Uri.parse(treeUri)) }
            .onFailure { Log.d(TAG, "扫描下载目录失败（按空处理）", it) }
            .getOrDefault(emptyList())
        synchronized(this@DownloadedSongs) {
            cachedTree = treeUri
            cachedAt = System.currentTimeMillis()
            cached = list
        }
        list
    }

    private data class Entry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
    )

    private data class Meta(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val durationMs: Long = 0L,
        val year: Int? = null,
        val genre: String? = null,
        val trackNo: Int = 0,
        val discNumber: Int? = null,
    )

    private fun scan(context: Context, tree: Uri): List<Song> {
        val resolver = context.contentResolver
        val rootId = docIdOf(tree.toString()) ?: return emptyList()
        val entries = queryChildren(resolver, tree, rootId)
        val byName = entries.associateBy { it.name }
        val out = ArrayList<Song>()
        for (entry in entries) {
            if (out.size >= MAX_SONGS) break
            if (entry.mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
            if (!isAudio(entry.name, entry.mime)) continue

            val base = entry.name.substringBeforeLast('.', entry.name)
            // 元信息与封面已内嵌进音频本身，统一从音频标签读取（写侧见 AudioTags）。
            // 歌词仍是同目录旁车；.lrc.txt 兼容老版本 SAF 自动补的后缀。
            val lrc = LRC_EXTENSIONS.firstNotNullOfOrNull { ext -> byName["$base$ext"] }
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId)
            val embedded = readEmbeddedMeta(context, uri, entry.name, entry.mime)
            out += Song(
                id = 0L,
                uri = uri,
                title = embedded?.title ?: displayTitle(entry.name),
                artist = embedded?.artist,
                album = embedded?.album,
                albumArtist = embedded?.albumArtist,
                durationMs = embedded?.durationMs?.takeIf { it > 0 } ?: 0L,
                sizeBytes = entry.size,
                mimeType = entry.mime,
                displayName = entry.name,
                folderPath = entry.docId.substringAfter(':', entry.docId).substringBeforeLast('/', ""),
                source = SongSource.FOLDER,
                trackNo = embedded?.trackNo ?: 0,
                lrcUri = lrc?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.docId) },
                genre = embedded?.genre,
                year = embedded?.year,
                discNumber = embedded?.discNumber,
                dateAdded = entry.modified,
            )
        }
        return out.sortedByDescending { it.dateAdded }
    }

    private fun queryChildren(resolver: ContentResolver, tree: Uri, dirDocId: String): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Entry>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val timeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return@use
            while (cursor.moveToNext() && out.size < 2_000) {
                val docId = cursor.getString(idIdx) ?: continue
                out += Entry(
                    docId = docId,
                    name = cursor.getString(nameIdx).orEmpty(),
                    mime = cursor.getString(mimeIdx).orEmpty(),
                    size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                    modified = if (timeIdx >= 0) cursor.getLong(timeIdx) else 0L,
                )
            }
        }
        return out
    }

    /**
     * 元信息从音频内嵌标签读取（写侧见 AudioTags）。
     *
     * MMR 对 SAF 文档 uri 的内嵌标签支持很弱（尤其 FLAC），缺关键字段时退回
     * [EmbeddedAudioInfo] 的字节级解析 —— 与写入侧同一套容器格式，读回的才是
     * 下载时真正写进去的歌名/歌手/专辑（v1.3.15）。
     * 只读文字不取封面，且仅在 MMR 缺字段时才读字节，避免扫描 500 首时反复读全文件。
     */
    private fun readEmbeddedMeta(context: Context, uri: Uri, name: String, mime: String): Meta? = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        val fromMmr = try {
            mmr.setDataSource(context, uri)
            fun value(key: Int): String? = mmr.extractMetadata(key)?.takeIf { it.isNotBlank() }
            Meta(
                title = value(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = value(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = value(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = value(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                durationMs = value(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                year = value(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.toIntOrNull()?.takeIf { it > 0 },
                genre = value(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE),
                trackNo = value(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull() ?: 0,
                discNumber = value(android.media.MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 },
            )
        } finally {
            runCatching { mmr.release() }
        }
        val needFallback = fromMmr.title.isNullOrBlank() ||
            fromMmr.artist.isNullOrBlank() || fromMmr.album.isNullOrBlank()
        val fromBytes = if (needFallback) {
            runCatching {
                EmbeddedAudioInfo.read(
                    context, uri, AudioFormats.extensionOf(name), mime, wantPicture = false,
                )
            }.getOrNull()
        } else null
        Meta(
            title = fromBytes?.title ?: fromMmr.title,
            artist = fromBytes?.artist ?: fromMmr.artist,
            album = fromBytes?.album ?: fromMmr.album,
            albumArtist = fromMmr.albumArtist,
            durationMs = fromMmr.durationMs,
            year = fromMmr.year,
            genre = fromMmr.genre,
            trackNo = fromMmr.trackNo,
            discNumber = fromMmr.discNumber,
        )
    }.getOrNull()

    internal fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/", ignoreCase = true)) return true
        return name.substringAfterLast('.', "").lowercase() in audioExtensions
    }

    /**
     * 展示名：去掉扩展名，再去掉边播边存追加的身份短哈希（`歌名-4e5a647a`）。
     * 用户选中的本地歌词或其他文件没有这种后缀，原样保留。
     */
    internal fun displayTitle(name: String): String {
        val base = name.substringBeforeLast('.', name).trim()
        return base.replace(Regex("-[0-9a-fA-F]{8}$"), "").trim().ifBlank { base.ifBlank { name } }
    }
}
