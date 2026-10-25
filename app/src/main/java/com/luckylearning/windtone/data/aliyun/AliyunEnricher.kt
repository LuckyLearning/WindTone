package com.luckylearning.windtone.data.aliyun

import android.content.Context
import com.luckylearning.windtone.data.LyricsMatcher
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.TrackFragment
import com.luckylearning.windtone.lyrics.LrcParser
import com.luckylearning.windtone.lyrics.LyricSource
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.lyrics.RemoteTagFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 阿里云盘同目录侧车歌词与文件头标签。
 *
 * 侧车：按 [LyricsMatcher.pickBest] 在父目录（marker 分页拉全）里挑同名歌词；
 * 标签：读文件头，供列表展示。
 */
object AliyunEnricher {

    suspend fun sidecarLyrics(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val id = TrackFragment.parse(song.uri.fragment) ?: return@withContext null
        val record = AliyunTrackStore(context).get(id.first, id.second) ?: return@withContext null
        if (record.parentFileId.isBlank()) return@withContext null
        val stem = song.displayName.substringBeforeLast('.', song.displayName)
        if (stem.isBlank()) return@withContext null
        val auth = AliyunAuth(context)
        val drive = AliyunDrive(auth)
        val entries = runCatching { drive.listAll(id.first, record.parentFileId) }.getOrNull()
            ?: return@withContext null
        val names = entries.filter { !it.isDir }.map { it.name }
        val bestName = LyricsMatcher.pickBest(names, stem) ?: return@withContext null
        val hit = entries.firstOrNull { it.name == bestName } ?: return@withContext null
        val url = drive.downloadUrl(id.first, hit.fileId).getOrNull() ?: return@withContext null
        val text = fetchText(url) ?: return@withContext null
        val lower = hit.name.lowercase()
        val parsed = when {
            lower.endsWith(".krc") || lower.endsWith(".yrc") ||
                lower.endsWith(".lrc") || LrcParser.hasTimeline(text) ->
                LrcParser.parse(text, LyricSource.SIDECAR_LRC)
            else -> LrcParser.plainText(text, LyricSource.SIDECAR_TXT)
        }
        parsed.takeIf { !it.isEmpty }
    }

    /** 从文件头读标签；没有歌词也不重复拉。专给列表封面。 */
    fun readHeader(song: Song): HeaderMeta? {
        val ext = song.displayName.substringAfterLast('.', "").lowercase()
        val result = RemoteTagFetcher.read(song.uri, ext, token = null) ?: return null
        if (result.artist.isNullOrBlank() && result.album.isNullOrBlank() &&
            result.title.isNullOrBlank() && result.picture == null
        ) {
            return null
        }
        return HeaderMeta(result.title, result.artist, result.album, result.picture)
    }

    fun applyHeader(context: Context, song: Song, meta: HeaderMeta) {
        val id = TrackFragment.parse(song.uri.fragment) ?: return
        AliyunTrackStore(context).applyTags(id.first, id.second, meta.title, meta.artist, meta.album, 0L)
    }

    data class HeaderMeta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val picture: ByteArray?,
    )

    private fun fetchText(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            AliyunEndpoints.downloadHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }.take(512 * 1024)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
