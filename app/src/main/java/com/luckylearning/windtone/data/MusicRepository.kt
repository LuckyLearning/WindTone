package com.luckylearning.windtone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 音乐库聚合：把「系统媒体库」「SAF 导入的文件夹」「深度文件扫描」三个来源合并去重。
 */
class MusicRepository(
    private val context: Context,
    private val prefs: Prefs,
) {

    data class LibraryResult(
        val songs: List<Song>,
        val skippedUnsupported: Int = 0,
        val withLyrics: Int = 0,
    )

    private val mediaStoreScanner by lazy { MediaStoreScanner(context) }
    private val folderScanner by lazy { FolderScanner(context) }
    private val fileScanner by lazy { FileScanner(context) }

    suspend fun loadLibrary(onProgress: (String) -> Unit = {}): LibraryResult = withContext(Dispatchers.IO) {
        val collected = LinkedHashMap<String, Song>()
        var skipped = 0
        val scope = prefs.scanScope

        // 未选择扫描范围：不主动扫描任何位置（首次安装的默认行为）
        if (scope == ScanScope.UNSET) {
            Log.i(TAG, "用户尚未选择扫描范围，跳过扫描")
            return@withContext LibraryResult(emptyList())
        }

        // 1) 系统媒体库 —— 仅在「全盘扫描」模式下读取
        if (scope == ScanScope.FULL) {
            onProgress("正在读取系统媒体库…")
            runCatching { mediaStoreScanner.scan(deepScanEnabled()) }
                .onFailure { Log.w(TAG, "媒体库扫描失败", it) }
                .getOrNull()
                ?.let { res ->
                    skipped += res.skippedUnsupported
                    res.songs.forEach { merge(collected, it) }
                }
        }
        val afterMediaStore = collected.size

        // 2) SAF 导入的文件夹 —— 两种范围都要扫；「指定目录」模式下是唯一来源
        val folders = prefs.folderUris
        folders.forEachIndexed { index, uriStr ->
            coroutineContext.ensureActive()
            onProgress("正在扫描导入文件夹 ${index + 1}/${folders.size}…")
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@forEachIndexed
            runCatching { folderScanner.scan(uri) { n -> onProgress("已找到 $n 首…") } }
                .onFailure { Log.w(TAG, "文件夹扫描失败: $uriStr", it) }
                .getOrNull()
                ?.forEach { merge(collected, it) }
        }

        // 3) 深度扫描（全盘模式 + 已授予「所有文件访问权限」时），能顺带读到同目录 .lrc
        if (scope == ScanScope.FULL && deepScanEnabled()) {
            onProgress("正在深度扫描本地文件…")
            runCatching { fileScanner.scan { n -> onProgress("已找到 $n 首…") } }
                .onFailure { Log.w(TAG, "深度扫描失败", it) }
                .getOrNull()
                ?.forEach { merge(collected, it) }
        }

        Log.i(TAG, "扫描完成（范围 $scope）：媒体库 $afterMediaStore 首，合计 ${collected.size} 首")

        // 短音频过滤：只剔除「时长已知且过短」的曲目；时长未知（0，如 WebDAV）保留，
        // 播放后拿到真实时长，下次扫描自然会被规则接管。
        val minMs = prefs.minSongDurationSec * 1000L
        val kept = if (minMs > 0L) collected.values.filterNot { it.durationMs in 1 until minMs } else collected.values.toList()
        if (kept.size != collected.size) {
            Log.i(TAG, "短音频过滤（< ${prefs.minSongDurationSec}s）：剔除 ${collected.size - kept.size} 首")
        }

        val songs = sort(kept, prefs.sortOrder)
        LibraryResult(
            songs = songs,
            skippedUnsupported = skipped,
            withLyrics = songs.count { it.lrcUri != null },
        )
    }

    /** 为没有找到 .lrc 的歌曲再尝试一次目录匹配（例如用户刚放入歌词文件） */
    suspend fun refreshSidecarLyrics(songs: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        songs.map { song ->
            if (song.lrcUri != null) song
            else {
                val uri = runCatching { LyricsLoader(context).findSidecar(song) }.getOrNull()
                if (uri != null) song.copy(lrcUri = uri) else song
            }
        }
    }

    fun sort(songs: List<Song>, order: SortOrder): List<Song> {
        fun text(s: String?): String = s.orEmpty().lowercase()
        val cmp: Comparator<Song> = when (order) {
            SortOrder.TITLE -> Comparator { a, b -> text(a.title).compareTo(text(b.title)) }
            SortOrder.ARTIST -> Comparator { a, b ->
                var r = text(a.artistOrUnknown).compareTo(text(b.artistOrUnknown))
                if (r == 0) r = text(a.albumOrUnknown).compareTo(text(b.albumOrUnknown))
                if (r == 0) r = a.trackNo.compareTo(b.trackNo)
                if (r == 0) r = text(a.title).compareTo(text(b.title))
                r
            }
            SortOrder.ALBUM -> Comparator { a, b ->
                var r = text(a.albumOrUnknown).compareTo(text(b.albumOrUnknown))
                if (r == 0) r = a.trackNo.compareTo(b.trackNo)
                if (r == 0) r = text(a.title).compareTo(text(b.title))
                r
            }
            SortOrder.DURATION -> Comparator { a, b -> b.durationMs.compareTo(a.durationMs) }
            SortOrder.DATE_ADDED -> Comparator { a, b -> b.dateAdded.compareTo(a.dateAdded) }
            SortOrder.FILE_NAME -> Comparator { a, b -> text(a.displayName).compareTo(text(b.displayName)) }
        }
        return songs.sortedWith(cmp)
    }

    fun search(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { matches(it, q) }
    }

    /** 单曲文本匹配（标题/歌手/专辑/文件名），供拼音搜索组合使用 */
    fun matches(song: Song, queryLower: String): Boolean {
        if (queryLower.isEmpty()) return false
        return song.title.lowercase().contains(queryLower) ||
            song.artistOrUnknown.lowercase().contains(queryLower) ||
            song.albumOrUnknown.lowercase().contains(queryLower) ||
            song.displayName.lowercase().contains(queryLower)
    }

    /** 是否具备「遍历本地文件」的能力（Android 11+ 需所有文件访问权限，更早版本只需读存储权限） */
    fun deepScanEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun merge(target: LinkedHashMap<String, Song>, song: Song) {
        val key = buildKey(song)
        val exists = target[key]
        if (exists == null) {
            target[key] = song
        } else {
            // 已有条目：优先保留带歌词文件的、来源更"完整"的那条
            if (exists.lrcUri == null && song.lrcUri != null) {
                target[key] = song.copy(id = exists.id)
            } else if (exists.durationMs == 0L && song.durationMs > 0L) {
                target[key] = song.copy(id = exists.id, lrcUri = exists.lrcUri ?: song.lrcUri)
            }
        }
    }

    private fun buildKey(song: Song): String {
        val name = song.displayName.lowercase()
        return "$name|${song.sizeBytes}"
    }

    companion object {
        private const val TAG = "MusicRepository"

        fun sourceLabel(source: SongSource): String = when (source) {
            SongSource.MEDIA_STORE -> "媒体库"
            SongSource.FOLDER -> "导入文件夹"
            SongSource.FILE -> "本地文件"
            SongSource.WEBDAV -> "WebDAV"
        }
    }
}
