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
    /** 只看下载目录本身：边播边存把文件直接写在这一层，不跟着用户的其他子目录跑 */
    private const val MAX_DEPTH = 1
    private const val CACHE_MS = 2_000L

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
        val list = runCatching { scan(context.contentResolver, Uri.parse(treeUri)) }
            .onFailure { Log.d(TAG, "扫描下载目录失败（按空处理）", it) }
            .getOrDefault(emptyList())
        synchronized(this@DownloadedSongs) {
            cachedTree = treeUri
            cachedAt = System.currentTimeMillis()
            cached = list
        }
        list
    }

    private fun scan(resolver: ContentResolver, tree: Uri): List<Song> {
        val rootId = docIdOf(tree.toString()) ?: return emptyList()
        val out = ArrayList<Song>()
        val seen = HashSet<String>()
        walk(resolver, tree, rootId, 0, out, seen)
        return out.sortedByDescending { it.dateAdded }
    }

    private fun walk(
        resolver: ContentResolver,
        tree: Uri,
        dirDocId: String,
        depth: Int,
        out: MutableList<Song>,
        seen: MutableSet<String>,
    ) {
        if (out.size >= MAX_SONGS) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val timeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return@use
            while (cursor.moveToNext() && out.size < MAX_SONGS) {
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx).orEmpty()
                val mime = cursor.getString(mimeIdx).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth + 1 < MAX_DEPTH) walk(resolver, tree, docId, depth + 1, out, seen)
                    continue
                }
                if (!isAudio(name, mime)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                if (!seen.add(uri.toString())) continue
                out += Song(
                    id = 0L,
                    uri = uri,
                    title = displayTitle(name),
                    artist = null,
                    album = null,
                    durationMs = 0L,
                    sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                    mimeType = mime,
                    displayName = name,
                    folderPath = docId.substringAfter(':', docId).substringBeforeLast('/', ""),
                    source = SongSource.FOLDER,
                    dateAdded = if (timeIdx >= 0) cursor.getLong(timeIdx) else 0L,
                )
            }
        }
    }

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
