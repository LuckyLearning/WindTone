package com.luckylearning.windtone.data.aliyun

import android.content.Context
import android.net.Uri
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
 * 起播之后补齐阿里云盘曲目：同目录歌词、文件头标签、封面字节。
 *
 * 标签请求由调用方在开播后再发，避免和第一段音频抢开头。
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
        val entries = drive.list(id.first, record.parentFileId).getOrNull()?.entries ?: return@withContext null
        val hit = entries.firstOrNull { entry ->
            val lower = entry.name.lowercase()
            !entry.isDir && (lower == "$stem.lrc".lowercase() || lower == "$stem.txt".lowercase())
        } ?: return@withContext null
        val url = drive.downloadUrl(id.first, hit.fileId).getOrNull() ?: return@withContext null
        val text = fetchText(url) ?: return@withContext null
        val parsed = if (hit.name.endsWith(".lrc", true) || LrcParser.hasTimeline(text)) {
            LrcParser.parse(text, LyricSource.SIDECAR_LRC)
        } else {
            LrcParser.plainText(text, LyricSource.SIDECAR_TXT)
        }
        parsed.takeIf { !it.isEmpty }
    }

    /** 读文件头。没有歌词也返回歌手、专辑和封面。 */
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
