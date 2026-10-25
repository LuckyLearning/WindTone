package com.luckylearning.windtone.lyrics

import android.net.Uri
import com.luckylearning.windtone.data.aliyun.AliyunEndpoints
import com.luckylearning.windtone.data.model.TrackFragment
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程音频的「文件头标签」读取。
 *
 * 场景：阿里云盘 / 在线歌单里的歌，歌词内嵌在文件里，本地 fd 路径对 http(s) 不可用。
 * 用 Range 只取文件开头一小段交给 [EmbeddedTags]。百度网盘产品面已删除，不再注入其 UA/token。
 */
internal object RemoteTagFetcher {

    private val STEPS = intArrayOf(128 * 1024, 512 * 1024)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "mp2", "mpga", "ogg", "oga", "opus")

    fun read(uri: Uri, extension: String, token: String?): EmbeddedTagResult? {
        if (extension.lowercase() !in SUPPORTED_EXTENSIONS) return null
        var best: EmbeddedTagResult? = null
        for (limit in STEPS) {
            val bytes = fetch(uri, limit) ?: return best
            if (bytes.size < 10) return best
            val result = runCatching {
                BinaryReader.open(bytes).use { EmbeddedTags.parse(it, extension, null) }
            }.getOrNull()
            if (result != null) {
                if (result.hasLyrics) return result
                if (best == null) best = result
            }
            if (bytes.size < limit) return best
        }
        return best
    }

    private fun fetch(uri: Uri, limit: Int): ByteArray? {
        val aliyun = TrackFragment.parse(uri.fragment) != null ||
            AliyunEndpoints.isDownloadHost(uri.host)
        val url = if (aliyun) {
            uri.buildUpon().fragment(null).build().toString()
        } else {
            uri.toString()
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Range", "bytes=0-${limit - 1}")
            if (aliyun) AliyunEndpoints.downloadHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(limit)
                val buf = ByteArray(32 * 1024)
                var total = 0
                while (total < limit) {
                    val n = input.read(buf, 0, minOf(buf.size, limit - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                return out.toByteArray()
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
