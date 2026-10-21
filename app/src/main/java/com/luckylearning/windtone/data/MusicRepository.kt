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

    /**
     * 扫描并合并三个来源。
     *
     * `folderUris` 由调用方传入（目录列表的唯一事实源在 Room 的 `folders` 表，
     * ViewModel 持有内存副本），仓库层不再直接读偏好。
     */
    suspend fun loadLibrary(
        folderUris: List<String>,
        onProgress: (String) -> Unit = {},
    ): LibraryResult = withContext(Dispatchers.IO) {
        val collected = LinkedHashMap<String, Song>()
        var skipped = 0
        val scope = prefs.scanScope
        // 扫描进度每回调一次，界面就整体重组一次。按「每 5 首 / 每 10 首」回调时，
        // 3000 首等于几百次重组，而人眼根本读不了这么快的数字 —— 统一限到 200ms 一次。
        val progress = throttleProgress(onProgress)

        // 未选择扫描范围：不主动扫描任何位置（首次安装的默认行为）
        if (scope == ScanScope.UNSET) {
            Log.i(TAG, "用户尚未选择扫描范围，跳过扫描")
            return@withContext LibraryResult(emptyList())
        }

        // 1) 系统媒体库 —— 仅在「全盘扫描」模式下读取
        if (scope == ScanScope.FULL) {
            progress("正在读取系统媒体库…")
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
        val folders = folderUris
        folders.forEachIndexed { index, uriStr ->
            coroutineContext.ensureActive()
            progress("正在扫描导入文件夹 ${index + 1}/${folders.size}…")
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@forEachIndexed
            runCatching { folderScanner.scan(uri) { n -> progress("已找到 $n 首…") } }
                .onFailure { Log.w(TAG, "文件夹扫描失败: $uriStr", it) }
                .getOrNull()
                ?.forEach { merge(collected, it) }
        }

        // 3) 深度扫描（全盘模式 + 已授予「所有文件访问权限」时），能顺带读到同目录 .lrc
        if (scope == ScanScope.FULL && deepScanEnabled()) {
            progress("正在深度扫描本地文件…")
            // 媒体库已经索引过的路径：不要再读一次标签（每首一次 MediaMetadataRetriever，
            // 是整条链路里最贵的一步）。深扫此时只负责「补媒体库漏掉的文件」和「补歌词」。
            val knownPaths = collected.values
                .mapNotNull { it.path }
                .mapTo(HashSet()) { it.lowercase() }
            runCatching { fileScanner.scan(knownPaths = knownPaths) { n -> progress("已找到 $n 首…") } }
                .onFailure { Log.w(TAG, "深度扫描失败", it) }
                .getOrNull()
                ?.forEach { merge(collected, it) }
        }

        Log.i(TAG, "扫描完成（范围 $scope）：媒体库 $afterMediaStore 首，合计 ${collected.size} 首")

        // 短音频过滤：只剔除「时长已知且过短」的曲目；时长未知（0）的保留，
        // 播放后拿到真实时长，下次扫描自然会被规则接管。
        val minMs = prefs.minSongDurationSec * 1000L
        val kept = if (minMs > 0L) collected.values.filterNot { it.durationMs in 1 until minMs } else collected.values.toList()
        if (kept.size != collected.size) {
            Log.i(TAG, "短音频过滤（< ${prefs.minSongDurationSec}s）：剔除 ${collected.size - kept.size} 首")
        }

        // 扫描结果先按标题排一个**稳定**顺序即可 —— 这里是纯数据层，拿不到「当前在
        // 哪个浏览视图」；真正的排序由 LibraryCore 按视图决定（v1.1.4 排序下沉），
        // 它在 applyLibrary 里会把这批数据重排一次。
        val songs = sort(kept, SortOrder.TITLE)
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
        // 「歌单内顺序」= 调用方给什么顺序就是什么（M3U 里写死的那个），不能过
        // sortedWith —— 那会把它重新排掉，用户自己拖出来的顺序就白拖了。
        if (order == SortOrder.PLAYLIST_ORDER) return songs
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
            // 同目录的排在一起，目录内再按文件名 —— 适合「按文件夹找歌」的习惯
            SortOrder.FOLDER_PATH -> Comparator { a, b ->
                var r = text(a.folderPath).compareTo(text(b.folderPath))
                if (r == 0) r = text(a.displayName).compareTo(text(b.displayName))
                r
            }
            // 已在函数开头提前返回，这里只是让 when 穷尽
            SortOrder.PLAYLIST_ORDER -> Comparator { _, _ -> 0 }
        }
        return songs.sortedWith(cmp)
    }

    /** 单曲文本匹配（标题/歌手/专辑/文件名），供拼音搜索组合使用。逻辑住在 [LibrarySearch] */
    fun matches(song: Song, queryLower: String): Boolean = LibrarySearch.matches(song, queryLower)

    /**
     * 曲库指纹（给冷启动节流用）：媒体库最新一条 _ID + 总条数。
     * 查询失败返回空串，调用方应视作「未知」并照常扫描。
     */
    fun libraryFingerprint(): String = mediaStoreScanner.fingerprint()

    /** 是否具备「遍历本地文件」的能力（Android 11+ 需所有文件访问权限，更早版本只需读存储权限） */
    fun deepScanEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * 合并同一首歌在不同来源下的两条记录。
     *
     * 原则：**信息更完整的那条做底，另一条只补它缺的字段**。
     * 早先的写法是「后来的整条替换，只保留旧 id」，这在深度扫描阶段是危险的：
     * 深扫产出的条目（尤其是跳过读标签的轻量条目）标题只是文件名、专辑 ID 是 0，
     * 整条替换会把媒体库带回来的中文标签和专辑 ID 一起冲掉。
     */
    private fun merge(target: LinkedHashMap<String, Song>, song: Song) {
        val key = buildKey(song)
        val exists = target[key]
        target[key] = if (exists == null) song else mergeSong(exists, song)
    }

    private fun buildKey(song: Song): String {
        val name = song.displayName.lowercase()
        return "$name|${song.sizeBytes}"
    }

    companion object {
        private const val TAG = "MusicRepository"

        /** 扫描进度回调的最小间隔（毫秒） */
        private const val PROGRESS_THROTTLE_MS = 200L

        /**
         * 进度回调限频。
         *
         * 扫描器按「每 5 首 / 每 10 首」回调，三千首就是几百次；而每次回调都会让
         * 界面整体重组一遍。阶段切换的文案（不带数字的那种）不受限频影响，
         * 保证用户能看到「正在深度扫描」这类关键提示。
         */
        private fun throttleProgress(onProgress: (String) -> Unit): (String) -> Unit {
            var lastAt = 0L
            return { text ->
                val now = System.currentTimeMillis()
                val isStageText = text.none { it.isDigit() }
                if (isStageText || now - lastAt >= PROGRESS_THROTTLE_MS) {
                    lastAt = now
                    onProgress(text)
                }
            }
        }

        fun sourceLabel(source: SongSource): String = when (source) {
            SongSource.MEDIA_STORE -> "媒体库"
            SongSource.FOLDER -> "导入文件夹"
            SongSource.FILE -> "本地文件"
            SongSource.REMOTE -> "在线"
        }

        /**
         * 同一首歌两条记录的合并结果（纯函数，便于单测）。
         *
         * 「完整」的判定用时长 / 歌手 / 专辑三个字段 —— 跳过读标签产出的轻量条目
         * 恰好这三个都是空的，而媒体库、SAF 读标签这两条路径都会填上。
         *
         * 无论哪条做底，**曲库主键都保留 [exists] 的**：主键变了，收藏、歌单、
         * 播放统计、歌词偏移全部会脱钩。
         */
        internal fun mergeSong(exists: Song, song: Song): Song {
            fun rich(s: Song): Boolean =
                s.durationMs > 0L || !s.artist.isNullOrBlank() || !s.album.isNullOrBlank()

            return when {
                !rich(exists) && rich(song) -> song.copy(
                    id = exists.id,
                    dbId = exists.dbId,
                    lrcUri = exists.lrcUri ?: song.lrcUri,
                    path = song.path ?: exists.path,
                )

                else -> exists.copy(
                    lrcUri = exists.lrcUri ?: song.lrcUri,
                    path = exists.path ?: song.path,
                    durationMs = if (exists.durationMs > 0L) exists.durationMs else song.durationMs,
                    artist = exists.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                    album = exists.album?.takeIf { it.isNotBlank() } ?: song.album,
                )
            }
        }
    }
}
