package com.luckylearning.windtone.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.luckylearning.windtone.data.baidu.BaiduAuth
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.BinaryReader
import com.luckylearning.windtone.lyrics.EmbeddedTagResult
import com.luckylearning.windtone.lyrics.EmbeddedTags
import com.luckylearning.windtone.lyrics.KrcParser
import com.luckylearning.windtone.lyrics.LrcParser
import com.luckylearning.windtone.lyrics.LyricSource
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.data.aliyun.AliyunEnricher
import com.luckylearning.windtone.lyrics.RemoteTagFetcher
import com.luckylearning.windtone.lyrics.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 歌词加载器。
 * 优先级：同目录 .lrc / .krc / .yrc 文件 > 同目录 .txt 歌词 > 音频内嵌同步歌词(SYLT)
 * > 音频内嵌歌词(USLT / Vorbis LYRICS / ©lyr)
 *
 * ⚠️ **精度只有「逐行」一种**（v1.1.8 起）。全应用的歌词都从这一处出去，
 * 所以在这一层确认：没有任何一条路径会产出字级时间轴。
 * 历史上这里是逐字时间轴派生的唯一出口，那套机制已整体移除 —— 需要恢复时
 * 必须同时恢复「数据产出」与「渲染消费」两端，别只加一头。
 */
class LyricsLoader(private val context: Context) {

    private val maxSidecarBytes = 2 * 1024 * 1024

    /**
     * 远程内嵌歌词的进程内缓存（v1.1.43）。
     *
     * 每首歌都要多发一次 128KB 的 Range 请求，反复切歌会把这笔开销放大；缓存最近 8 首的
     * 结果，切回来就不再发请求。键用 [Song.id]：网盘歌是 `-fsId`，稳定且唯一。
     * 只缓存**真的读到歌词**的结果 —— 把「空」也缓存住，用户补好标签后必须重启才生效。
     */
    private val remoteEmbeddedCache =
        object : LinkedHashMap<Long, EmbeddedTagResult>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, EmbeddedTagResult>) =
                size > 8
        }

    /** 网盘直链要 access_token，这里只读不刷新（8 小时内的 dlink 场景足够） */
    private val baiduAuth by lazy { BaiduAuth(context) }

    suspend fun load(song: Song): Lyrics = withContext(Dispatchers.IO) {
        loadRaw(song)
    }

    private suspend fun loadRaw(song: Song): Lyrics {
        val cloudLyric = AliyunEnricher.sidecarLyrics(context, song)
        if (cloudLyric != null) return cloudLyric
        // 1) 侧车歌词文件
        val sidecarUri = song.lrcUri ?: findSidecar(song)
        if (sidecarUri != null) {
            val bytes = readSidecar(sidecarUri)
            if (bytes != null && bytes.isNotEmpty()) {
                val ext = sidecarUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                val parsed = when {
                    ext == "krc" -> KrcParser.parseKrc(bytes, LyricSource.SIDECAR_KRC)
                    ext == "yrc" -> KrcParser.parseText(
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

    private suspend fun readEmbedded(song: Song): EmbeddedTagResult? {
        val ext = song.displayName.substringAfterLast('.', "").lowercase()
        // 远程（网盘 / 在线歌单）拿不到文件描述符，只能按 Range 取文件头解析
        if (isRemote(song.uri)) return readRemoteEmbedded(song, ext)
        return runCatching {
            // 只关闭 ParcelFileDescriptor，底层 fd 随之释放；FileInputStream 无需单独关闭
            context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
                val fis = java.io.FileInputStream(pfd.fileDescriptor)
                val reader = BinaryReader.open(fis)
                if (reader == null) null else EmbeddedTags.parse(reader, ext, song.mimeType)
            }
        }.onFailure { Log.w(TAG, "解析内嵌歌词失败: ${song.uri}", it) }.getOrNull()
    }

    private fun isRemote(uri: Uri): Boolean =
        uri.scheme.equals("http", true) || uri.scheme.equals("https", true)

    /**
     * 远程内嵌歌词。
     *
     * 先等 600ms 再发请求：切歌瞬间正在建流、拉首包，这时再抢一条连接只会让「开头卡一下」
     * 更明显；歌词比声音晚半秒没人会注意。
     */
    private suspend fun readRemoteEmbedded(song: Song, ext: String): EmbeddedTagResult? {
        if (ext !in RemoteTagFetcher.SUPPORTED_EXTENSIONS) return null
        remoteEmbeddedCache[song.id]?.let { return it }
        delay(REMOTE_LYRIC_DELAY_MS)
        val result = runCatching {
            RemoteTagFetcher.read(song.uri, ext, baiduAuth.currentToken()?.accessToken)
        }.onFailure { Log.w(TAG, "读取远程内嵌歌词失败: ${song.uri}", it) }.getOrNull() ?: return null
        if (result.hasLyrics) remoteEmbeddedCache[song.id] = result
        return result
    }

    private companion object {
        const val TAG = "LyricsLoader"

        /** 远程歌词请求的起步延迟：先让音频把首包拉起来 */
        const val REMOTE_LYRIC_DELAY_MS = 600L
    }
}
