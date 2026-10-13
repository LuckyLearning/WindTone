package com.luckylearning.windtone.data.webdav

import android.net.Uri
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * WebDAV 音乐扫描器：递归列目录，筛出音频文件，顺带匹配同目录 .lrc 歌词。
 *
 * 限制（防超大目录把车机卡死）：
 *  - 最大目录深度 [MAX_DEPTH]
 *  - 最多收集 [MAX_SONGS] 首音频
 *  - 最多请求 [MAX_REQUESTS] 次 PROPFIND
 */
object WebDavScanner {

    private const val TAG = "WebDavScanner"
    private const val MAX_DEPTH = 5
    private const val MAX_SONGS = 3000
    private const val MAX_REQUESTS = 600

    private val AUDIO_EXT = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape")
    private val LYRIC_EXT = setOf("lrc", "txt", "krc", "yrc")

    data class Result(
        val songs: List<Song>,
        val dirsScanned: Int,
        val truncated: Boolean,
    )

    /**
     * 从 [rootPath]（"" 表示站点根）开始递归扫描。
     * [baseUrl] 用于拼出文件的可播放 URL。
     * 歌词文件与音频文件同批收集，扫描结束后按「同目录 + 同主名」配对。
     */
    suspend fun scan(baseUrl: String, rootPath: String, onProgress: (String) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val audio = ArrayList<Pair<String, WebDavClient.Entry>>() // dirPath -> entry
            val lyrics = ArrayList<Pair<String, WebDavClient.Entry>>()
            var requests = 0
            var truncated = false
            val queue = ArrayDeque<Pair<String, Int>>() // path to depth
            queue.add(rootPath to 0)

            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                if (requests >= MAX_REQUESTS) { truncated = true; break }
                val (dir, depth) = queue.removeFirst()
                requests++
                onProgress("正在扫描 $dir（第 ${audio.size} 首…）")
                val entries = try {
                    WebDavClient.listDir(dir)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "列目录失败: $dir — ${e.message}")
                    emptyList()
                }
                for (e in entries) {
                    if (e.isDir) {
                        if (depth + 1 < MAX_DEPTH) queue.add(e.path to depth + 1)
                    } else {
                        val ext = e.name.substringAfterLast('.', "").lowercase(Locale.US)
                        when {
                            ext in AUDIO_EXT -> {
                                if (audio.size < MAX_SONGS) audio.add(dir to e)
                                else truncated = true
                            }

                            ext in LYRIC_EXT -> lyrics.add(dir to e)
                        }
                    }
                }
            }

            // 歌词配对：同一目录下，主名相同即视为匹配（复用 LyricsMatcher 的宽容匹配更佳，
            // 但那需要一次网络往返去猜名字；这里用最朴素的同名规则，命中率已经够高）
            val lyricsByDir = lyrics.groupBy { it.first }
            val songs = audio.map { (dir, e) ->
                val base = e.name.substringBeforeLast('.', e.name)
                val sidecar = lyricsByDir[dir].orEmpty().firstOrNull { (_, l) ->
                    l.name.substringBeforeLast('.', l.name) == base
                }?.second

                val title = base.substringAfter(" - ").ifBlank { base }
                Song(
                    id = 0L,
                    // 文件 URL 不带尾斜杠；带斜杠会让部分服务器按目录处理直接 404
                    uri = Uri.parse(WebDavClient.joinUrl(baseUrl, e.path.trimEnd('/'), trailingSlash = false)),
                    title = title,
                    artist = base.substringBefore(" - ").takeIf { it != base && it.isNotBlank() },
                    album = null,
                    durationMs = 0L, // HTTP 通道拿不到时长，播放后由 ExoPlayer 回填 UI
                    sizeBytes = e.sizeBytes,
                    displayName = e.name,
                    folderPath = dir.trim('/').ifEmpty { "WebDAV 根目录" },
                    source = SongSource.WEBDAV,
                    dateAdded = e.lastModified,
                    lrcUri = sidecar?.let {
                        Uri.parse(WebDavClient.joinUrl(baseUrl, it.path.trimEnd('/'), trailingSlash = false))
                    },
                )
            }

            Log.i(TAG, "WebDAV 扫描完成：${songs.size} 首音频，$requests 次请求，截断=$truncated")
            Result(songs = songs, dirsScanned = requests, truncated = truncated)
        }
}
