package com.luckylearning.windtone.lyrics

import android.net.Uri
import com.luckylearning.windtone.data.baidu.BaiduRules
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程音频的「文件头标签」读取（v1.1.43）。
 *
 * 场景：网盘 / 在线歌单里的歌，歌词是**内嵌在文件里**的（flac 的 VORBIS_COMMENT、
 * mp3 的 USLT），但本地那条路径走的是 `contentResolver.openFileDescriptor` —— 对
 * `http(s)` 必然拿不到 fd，于是界面一直是「无歌词」。用户看到的结论是「播放器不认内嵌歌词」。
 *
 * 做法：用 `Range` 只取文件开头一小段，交给同一套 [EmbeddedTags] 解析。三段约束：
 *
 *  1. **只对标签一定在开头的容器走这条路**（flac / mp3 / ogg）。m4a 的 `moov` 常在文件
 *     尾部，取头部必然读不到，白费流量。
 *  2. **两段式**：先取 128KB（绝大多数 flac 的注释块在前 16KB），没有歌词且文件还有更多
 *     内容才取 512KB。弱网下每首歌多花 128KB 是可接受的，512KB 是上限。
 *  3. **不支持 Range 也不能整包下载**：服务器回 200 时照样只读够上限就断开 ——
 *     否则一首 28MB 的无损会被「读歌词」这个动作整个拉下来。
 */
internal object RemoteTagFetcher {

    /** 依次尝试的头部长度。顺序即成本顺序，不要随便合并成一个大值 */
    private val STEPS = intArrayOf(128 * 1024, 512 * 1024)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** 标签在文件头部的容器；m4a/mp4 故意不在内（见类注释第 1 条） */
    val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "mp2", "mpga", "ogg", "oga", "opus")

    fun read(uri: Uri, extension: String, token: String?): EmbeddedTagResult? {
        if (extension.lowercase() !in SUPPORTED_EXTENSIONS) return null
        for (limit in STEPS) {
            val bytes = fetch(uri, limit, token) ?: return null
            if (bytes.size < 10) return null
            val result = runCatching {
                BinaryReader.open(bytes).use { EmbeddedTags.parse(it, extension, null) }
            }.getOrNull()
            if (result != null && result.hasLyrics) return result
            // 这一段已经是整个文件（服务器没截断）就没必要再取一次更大的
            if (bytes.size < limit) return null
        }
        return null
    }

    private fun fetch(uri: Uri, limit: Int, token: String?): ByteArray? {
        val pan = BaiduRules.needsPanUserAgent(uri.host)
        val url = if (pan) BaiduRules.withAccessToken(uri.toString(), token) else uri.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Range", "bytes=0-${limit - 1}")
            if (pan) BaiduRules.panHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
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
