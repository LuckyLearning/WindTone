package com.luckylearning.windtone.data

import android.content.Context
import android.net.Uri
import android.os.Environment
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

    fun scan(treeUri: Uri, readTags: Boolean = true, onProgress: (Int) -> Unit = {}): List<Song> {
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Song>()
        var counted = 0
        // 文件夹视图按「用户选择的目录」分组：整棵子树的歌都归到所选目录名下，
        // 子目录不单独显示（用户在系统选择器里挑哪层，哪层就是一个分组）。
        // 分组键用**解码后的完整文档 ID**（如 primary:Music/华语）而不是叶名——
        // 两个不同位置的同名目录各自成组不会撞在一起；页面标题仍取最后一个文件夹名。
        val treeDocId = runCatching { Uri.decode(treeUri.lastPathSegment.orEmpty()) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: (root.name ?: "导入目录")
        walk(root, out, readTags, treeDocId) {
            counted++
            if (counted % 5 == 0) onProgress(out.size)
        }
        return out
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
            out.add(buildSong(audio, name, lyric?.uri, folderLabel, SongSource.FOLDER, readTags))
        }

        for (child in children) {
            val childName = child.name ?: continue
            if (child.isDirectory && !childName.startsWith(".")) {
                walk(child, out, readTags, folderLabel, tick)
            }
        }
    }

    private fun buildSong(
        file: DocumentFile,
        name: String,
        lrcUri: Uri?,
        folderPath: String?,
        source: SongSource,
        readTags: Boolean,
    ): Song {
        val uri = file.uri
        val base = name.substringBeforeLast('.')
        val info = if (readTags) MetadataReader.read(context, uri) else null
        return Song(
            id = uri.toString().hashCode().toLong(),
            uri = uri,
            title = info?.title?.takeIf { it.isNotBlank() } ?: base,
            artist = info?.artist,
            album = info?.album,
            durationMs = info?.durationMs ?: 0L,
            sizeBytes = file.length(),
            displayName = name,
            folderPath = folderPath,
            source = source,
            lrcUri = lrcUri,
            dateAdded = file.lastModified(),
        )
    }
}

/**
 * 深度文件扫描（需要「所有文件访问权限」）。
 * 直接遍历外部存储，能同时发现音频与其同目录的 .lrc，是最完整的歌词匹配方式。
 */
class FileScanner(private val context: Context) {

    fun scan(
        roots: List<File> = defaultRoots(),
        readTags: Boolean = true,
        onProgress: (Int) -> Unit = {},
    ): List<Song> {
        val out = ArrayList<Song>()
        val visited = HashSet<String>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            walk(root, out, visited, readTags, 0, onProgress)
        }
        return out
    }

    private fun walk(
        dir: File,
        out: MutableList<Song>,
        visited: MutableSet<String>,
        readTags: Boolean,
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
            val info = if (readTags) MetadataReader.read(context, uri) else null
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
                )
            )
            if (out.size % 10 == 0) onProgress(out.size)
        }

        for (f in children) {
            if (f.isDirectory) walk(f, out, visited, readTags, depth + 1, onProgress)
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
