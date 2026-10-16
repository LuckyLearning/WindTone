package com.luckylearning.windtone.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.BinaryReader
import com.luckylearning.windtone.lyrics.EmbeddedTags
import com.luckylearning.windtone.lyrics.LrcParser
import com.luckylearning.windtone.lyrics.LyricSource
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.lyrics.TextCodec
import com.luckylearning.windtone.lyrics.WordLyricsParser
import com.luckylearning.windtone.lyrics.WordTimingSynth.withDerivedWordTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 歌词加载器。
 * 优先级：同目录 .lrc 文件 > 同目录 .txt 歌词 > 音频内嵌同步歌词(SYLT) > 音频内嵌歌词(USLT / Vorbis LYRICS / ©lyr)
 *
 * ⚠️ 所有出口都过一遍 [WordTimingSynth.withDerivedWordTiming]：只有 .krc / .yrc /
 * 增强型 LRC 才自带逐字时间轴，普通 LRC 没有 → 不派生的话「逐字歌词」对绝大多数
 * 用户的歌词文件都等于没效果（2026-10-16 定位）。这里是全应用歌词的唯一入口，
 * 在这一层补齐，播放页与桌面悬浮歌词两个消费方同时受益。
 */
class LyricsLoader(private val context: Context) {

    private val maxSidecarBytes = 2 * 1024 * 1024

    suspend fun load(song: Song): Lyrics = withContext(Dispatchers.IO) {
        loadRaw(song).withDerivedWordTiming()
    }

    private suspend fun loadRaw(song: Song): Lyrics {
        // 1) 侧车歌词文件
        val sidecarUri = song.lrcUri ?: findSidecar(song)
        if (sidecarUri != null) {
            val bytes = readSidecar(sidecarUri)
            if (bytes != null && bytes.isNotEmpty()) {
                val ext = sidecarUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                val parsed = when {
                    ext == "krc" -> WordLyricsParser.parseKrc(bytes, LyricSource.SIDECAR_KRC)
                    ext == "yrc" -> WordLyricsParser.parseWordText(
                        TextCodec.decode(bytes), LyricSource.SIDECAR_KRC,
                    )
                    else -> {
                        val text = TextCodec.decode(bytes)
                        val isLrc = ext == "lrc" || LrcParser.hasTimeline(text)
                        if (isLrc) LrcParser.parse(text, LyricSource.SIDECAR_LRC)
                        else LrcParser.plainText(text, LyricSource.SIDECAR_TXT)
                    }
                }
                if (!parsed.isEmpty) return parsed
            }
        }

        // 2) 内嵌歌词
        val embedded = readEmbedded(song)
        if (embedded != null) {
            val synced = embedded.syncedLines
            if (!synced.isNullOrEmpty()) {
                return Lyrics(
                    lines = synced,
                    synced = true,
                    title = embedded.title,
                    artist = embedded.artist,
                    album = embedded.album,
                    source = LyricSource.EMBEDDED_SYLT,
                )
            }
            val text = embedded.lyricsText
            if (!text.isNullOrBlank()) {
                val parsed = if (LrcParser.hasTimeline(text)) {
                    LrcParser.parse(text, LyricSource.EMBEDDED)
                } else {
                    LrcParser.plainText(text, LyricSource.EMBEDDED)
                }
                if (!parsed.isEmpty) {
                    return parsed.copy(
                        title = embedded.title ?: parsed.title,
                        artist = embedded.artist ?: parsed.artist,
                        album = embedded.album ?: parsed.album,
                    )
                }
            }
        }

        return Lyrics.EMPTY
    }

    /** 在音频同目录查找同名歌词文件 */
    fun findSidecar(song: Song): Uri? {
        song.lrcUri?.let { return it }

        // 有真实路径时直接查目录（深度扫描 / SAF 已解析出路径的情况）
        song.path?.let { p ->
            val f = File(p)
            val dir = f.parentFile
            if (dir != null && dir.isDirectory) {
                val hit = pickBest(dir.list()?.toList().orEmpty(), song.baseName)
                if (hit != null) return Uri.fromFile(File(dir, hit))
            }
        }

        // MediaStore 提供的相对路径 + 已知的公共音乐目录
        song.folderPath?.let { rel ->
            val dir = File(rel)
            if (dir.isDirectory) {
                val hit = pickBest(dir.list()?.toList().orEmpty(), song.baseName)
                if (hit != null) return Uri.fromFile(File(dir, hit))
            }
        }
        return null
    }

    fun pickBest(names: List<String>, baseName: String): String? =
        names.mapNotNull { n -> LyricsMatcher.score(n, baseName).takeIf { it >= 0 }?.let { it to n } }
            .maxByOrNull { it.first }
            ?.second

    private fun readSidecar(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { readLimited(it, maxSidecarBytes) }
    }.onFailure { Log.w(TAG, "读取歌词文件失败: $uri", it) }.getOrNull()

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            val take = minOf(n, limit - total)
            if (take > 0) out.write(buf, 0, take)
            total += n
            if (total >= limit) break
        }
        return out.toByteArray()
    }

    private fun readEmbedded(song: Song): com.luckylearning.windtone.lyrics.EmbeddedTagResult? = runCatching {
        val ext = song.displayName.substringAfterLast('.', "").lowercase()
        // 只关闭 ParcelFileDescriptor，底层 fd 随之释放；FileInputStream 无需单独关闭
        context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
            val fis = java.io.FileInputStream(pfd.fileDescriptor)
            val reader = BinaryReader.open(fis)
            if (reader == null) null else EmbeddedTags.parse(reader, ext, song.mimeType)
        }
    }.onFailure { Log.w(TAG, "解析内嵌歌词失败: ${song.uri}", it) }.getOrNull()

    private companion object {
        const val TAG = "LyricsLoader"
    }
}
