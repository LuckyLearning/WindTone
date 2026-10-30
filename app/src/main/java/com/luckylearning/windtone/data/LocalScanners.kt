package com.luckylearning.windtone.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import java.io.File

/**
 * SAF（Storage Access Framework）文件夹扫描。
 * 用户通过系统文件选择器授权目录后，可直接读取该目录树下的音频与同名 .lrc 歌词文件。
 */
class FolderScanner(private val context: Context) {

    /** SAF 没有 File，一次批量查询只能自己把这几个字段端住 */
    private class DocItem(
        val docId: String,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    fun scan(treeUri: Uri, readTags: Boolean = true, onProgress: (Int) -> Unit = {}): List<Song> {
        // 文件夹视图按「用户选择的目录」分组：整棵子树的歌都归到所选目录名下，
        // 子目录不单独显示（用户在系统选择器里挑哪层，哪层就是一个分组）。
        // 分组键用**解码后的完整文档 ID**（如 primary:Music/华语）而不是叶名——
        // 两个不同位置的同名目录各自成组不会撞在一起；页面标题仍取最后一个文件夹名。
        //
        // ⚠️ 这个取值形态与已入库的 `folderPath` 绑定，不能换成
        // `DocumentsContract.getTreeDocumentId()`（那个不带 `tree/` 前缀），
        // 否则老用户的文件夹分组会全部对不上。
        val folderLabel = runCatching { Uri.decode(treeUri.lastPathSegment.orEmpty()) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: (runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull() ?: "导入目录")

        val out = ArrayList<Song>()
        var counted = 0
        val batchOk = runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            rootDocId.isNotBlank() && walkBatch(treeUri, rootDocId, out, readTags, folderLabel, 0) {
                counted++
                if (counted % 5 == 0) onProgress(out.size)
            }
        }.getOrDefault(false)

        if (batchOk) return out

        // 批量查询在个别 ROM / 第三方 provider 上会被拒（不支持子文档 URI、稳定 ID 缺失）。
        // 退回逐项查询的老路径 —— 宁可慢，也不能让「目录扫描」整个失效。
        Log.w(TAG, "批量查询不可用，回退 DocumentFile：$treeUri")
        out.clear()
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return emptyList()
        counted = 0
        walk(root, out, readTags, folderLabel) {
            counted++
            if (counted % 5 == 0) onProgress(out.size)
        }
        return out
    }

    /**
     * 批量查询版本：**每个目录只查一次**，一次拿齐子项的名字 / 类型 / 大小 / 修改时间。
     *
     * 老实现走 `DocumentFile`，每个子项的 `name` / `isDirectory` / `length()` 各触发
     * 一次 ContentProvider 查询 —— 1000 个文件就是 2000+ 次 IPC，这是 SAF 扫描慢的主因。
     * 批量查询把「每文件 2~3 次」压到「每目录 1 次」。
     *
     * @return false 表示这次查询不可用，调用方应整体回退；true 表示这段走完了。
     */
    private fun walkBatch(
        treeUri: Uri,
        parentDocId: String,
        out: MutableList<Song>,
        readTags: Boolean,
        folderLabel: String,
        depth: Int,
        tick: () -> Unit,
    ): Boolean {
        // 深度上限只是防 provider 返回自引用导致死循环，不是产品限制：老实现没有上限，
        // 定得太小会「优化出漏歌」。32 层对任何真实的音乐目录都远超所需。
        if (depth > MAX_SAF_DEPTH) return true
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = runCatching {
            context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        }.getOrNull() ?: return false

        val dirs = ArrayList<String>()
        val audio = ArrayList<DocItem>()
        val lyrics = ArrayList<DocItem>()
        try {
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = cursor.getString(2)
                // 极少数 provider 不给 MIME_TYPE。此时「无扩展名」按目录处理 ——
                // 音频/歌词一定带扩展名，误判只会多一次子查询，不会把歌漏掉。
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                    (mime.isNullOrBlank() && !name.contains('.'))
                if (isDir) {
                    dirs.add(docId)
                    continue
                }
                when {
                    AudioFormats.isSupported(name) ->
                        audio.add(DocItem(docId, name, cursor.getLong(3), cursor.getLong(4)))

                    LyricsMatcher.isLyricFile(name) ->
                        lyrics.add(DocItem(docId, name, 0L, 0L))
                }
            }
        } finally {
            cursor.close()
        }

        for (item in audio) {
            tick()
            val base = item.name.substringBeforeLast('.')
            val lyric = lyrics
                .mapNotNull { lf -> LyricsMatcher.score(lf.name, base).takeIf { it >= 0 }?.let { it to lf } }
                .maxByOrNull { it.first }?.second

            out.add(
                buildSong(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.docId),
                    name = item.name,
                    lrcUri = lyric?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.docId) },
                    folderPath = folderLabel,
                    source = SongSource.FOLDER,
                    readTags = readTags,
                    sizeBytes = item.sizeBytes,
                    lastModified = item.lastModified,
                )
            )
        }

        for (dirId in dirs) {
            if (!walkBatch(treeUri, dirId, out, readTags, folderLabel, depth + 1, tick)) return false
        }
        return true
    }

    private fun walk(
        dir: DocumentFile,
        out: MutableList<Song>,
        readTags: Boolean,
        folderLabel: String,
        tick: () -> Unit,
    ) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        val audioChildren = ArrayList<DocumentFile>()
        val lyricChildren = ArrayList<DocumentFile>()

        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) continue
            if (name.startsWith(".")) continue
            when {
                AudioFormats.isSupported(name) -> audioChildren.add(child)
                LyricsMatcher.isLyricFile(name) -> lyricChildren.add(child)
            }
        }

        for (audio in audioChildren) {
            tick()
            val name = audio.name ?: continue
            val base = name.substringBeforeLast('.')
            val lyric = lyricChildren
                .mapNotNull { lf -> lf.name?.let { n -> LyricsMatcher.score(n, base).takeIf { it >= 0 }?.let { it to lf } } }
                .maxByOrNull { it.first }?.second

            // SAF 没有真实文件路径，用「所选目录的完整文档 ID」作为 folderPath 统一分组
            out.add(
                buildSong(
                    uri = audio.uri,
                    name = name,
                    lrcUri = lyric?.uri,
                    folderPath = folderLabel,
                    source = SongSource.FOLDER,
                    readTags = readTags,
                    sizeBytes = runCatching { audio.length() }.getOrDefault(0L),
                    lastModified = runCatching { audio.lastModified() }.getOrDefault(0L),
                )
            )
        }

        for (child in children) {
            val childName = child.name ?: continue
            if (child.isDirectory && !childName.startsWith(".")) {
                walk(child, out, readTags, folderLabel, tick)
            }
        }
    }

    private fun buildSong(
        uri: Uri,
        name: String,
        lrcUri: Uri?,
        folderPath: String?,
        source: SongSource,
        readTags: Boolean,
        sizeBytes: Long,
        lastModified: Long,
    ): Song {
        val base = name.substringBeforeLast('.')
        val info = if (readTags) MetadataReader.read(context, uri) else null
        return Song(
            id = uri.toString().hashCode().toLong(),
            uri = uri,
            title = info?.title?.takeIf { it.isNotBlank() } ?: base,
            artist = info?.artist,
            album = info?.album,
            durationMs = info?.durationMs ?: 0L,
            sizeBytes = sizeBytes,
            displayName = name,
            folderPath = folderPath,
            source = source,
            lrcUri = lrcUri,
            dateAdded = lastModified,
            genre = info?.genre,
            albumArtist = info?.albumArtist,
            year = info?.year,
        )
    }

    private companion object {
        const val TAG = "FolderScanner"

        /** 递归深度上限，只用于防 provider 自引用；老实现的 DocumentFile 路径无此上限 */
        const val MAX_SAF_DEPTH = 32

        /** 与 [walkBatch] 里按下标读取的顺序一一对应，改顺序必须同步改下标 */
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/**
 * 深度文件扫描（需要「所有文件访问权限」）。
 * 直接遍历外部存储，能同时发现音频与其同目录的 .lrc，是最完整的歌词匹配方式。
 */
class FileScanner(private val context: Context) {

    /**
     * @param knownPaths 已经被系统媒体库索引过的绝对路径（小写）。命中这些文件时
     *   **不再读标签** —— 标签在 MediaStore 那一步已经拿到，再开一次
     *   MediaMetadataRetriever 纯属重复劳动。深扫这一步的价值是「找到媒体库漏掉的文件」
     *   与「补上同目录歌词」，不是重新读一遍元数据。
     */
    fun scan(
        roots: List<File> = defaultRoots(),
        readTags: Boolean = true,
        knownPaths: Set<String> = emptySet(),
        onProgress: (Int) -> Unit = {},
    ): List<Song> {
        val out = ArrayList<Song>()
        val visited = HashSet<String>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            walk(root, out, visited, readTags, knownPaths, 0, onProgress)
        }
        return out
    }

    private fun walk(
        dir: File,
        out: MutableList<Song>,
        visited: MutableSet<String>,
        readTags: Boolean,
        knownPaths: Set<String>,
        depth: Int,
        onProgress: (Int) -> Unit,
    ) {
        if (depth > 12 || out.size > 20000) return
        if (!visited.add(dir.absolutePath)) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return

        val audioFiles = ArrayList<File>()
        val lyricFiles = ArrayList<File>()
        for (f in children) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (n.equals("Android", true) || n.equals("LOST.DIR", true) ||
                    n.equals("obb", true) || n.equals("data", true)
                ) continue
            } else {
                when {
                    AudioFormats.isSupported(n) -> audioFiles.add(f)
                    LyricsMatcher.isLyricFile(n) -> lyricFiles.add(f)
                }
            }
        }

        for (audio in audioFiles) {
            val name = audio.name
            val base = name.substringBeforeLast('.')
            val lyric = lyricFiles
                .mapNotNull { lf -> LyricsMatcher.score(lf.name, base).takeIf { it >= 0 }?.let { it to lf } }
                .maxByOrNull { it.first }?.second

            val uri = Uri.fromFile(audio)
            // 媒体库已索引过：标签、时长、专辑都在，不必再开一次解析器（这是深扫最贵的一步）
            val known = knownPaths.isNotEmpty() && audio.absolutePath.lowercase() in knownPaths
            val info = if (readTags && !known) MetadataReader.read(context, uri) else null
            out.add(
                Song(
                    id = audio.absolutePath.hashCode().toLong(),
                    uri = uri,
                    title = info?.title?.takeIf { it.isNotBlank() } ?: base,
                    artist = info?.artist,
                    album = info?.album,
                    durationMs = info?.durationMs ?: 0L,
                    sizeBytes = audio.length(),
                    displayName = name,
                    path = audio.absolutePath,
                    folderPath = audio.parent,
                    source = SongSource.FILE,
                    lrcUri = lyric?.let { Uri.fromFile(it) },
                    dateAdded = audio.lastModified(),
                    genre = info?.genre,
                    albumArtist = info?.albumArtist,
                    year = info?.year,
                )
            )
            if (out.size % 10 == 0) onProgress(out.size)
        }

        for (f in children) {
            if (f.isDirectory) walk(f, out, visited, readTags, knownPaths, depth + 1, onProgress)
        }
    }

    private fun defaultRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        roots.add(Environment.getExternalStorageDirectory())
        runCatching {
            context.getExternalFilesDirs(null)
                .filterNotNull()
                .mapNotNull { it.absolutePath.split("/Android/").firstOrNull() }
                .map { File(it) }
                .forEach { roots.add(it) }
        }.onFailure { Log.w("FileScanner", "枚举外部存储失败", it) }
        return roots.filter { it.exists() && it.canRead() }.ifEmpty {
            listOf(File(Environment.getExternalStorageDirectory(), "Music")).filter { it.exists() }
        }
    }
}
