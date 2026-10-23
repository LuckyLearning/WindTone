package com.luckylearning.windtone.data.aliyun

import java.net.HttpURLConnection
import java.net.URL

/**
 * 网盘直链**播放自检**：把「取到的直链到底能不能下载」这件事单独验一遍。
 *
 * 为什么需要：播放器只会给一个 `ERROR_CODE_IO_UNSPECIFIED`，而这个码既可能是
 * 「直链 403 / 域名连不上」，也可能是「本地缓存写坏了」「解码器不认格式」。
 * 在用户机器上没法看 logcat，只能靠一次真实请求把范围缩到一层：
 *
 *  · 这里能拿到 206 + 字节数 → 直链没问题，问题在播放器侧（缓存 / 解封装）；
 *  · 这里 403 / 404 → 直链本身不可用（令牌、盘 id、文件被移走）；
 *  · 这里超时 / UnknownHost → 网络或 DNS，跟播放器无关。
 *
 * 只读前 1KB（`Range: bytes=0-1023`），不消耗多少流量，也不会拖慢什么。
 */
object AliyunProbe {

    /** 自检结果 */
    data class Result(
        val httpCode: Int,
        /** 实际收到的字节数（Range 请求正常是 1024） */
        val bytes: Int,
        val elapsedMs: Long,
        /** 直链主机（排障用，不含签名） */
        val host: String,
        /** 文件总大小（服务端给了 Content-Range 时才有） */
        val totalBytes: Long = 0L,
    ) {
        /** 能正常下载（200 / 206 且真的读到了数据） */
        val ok: Boolean get() = (httpCode == 200 || httpCode == 206) && bytes > 0

        /** 给人看的一行结论 */
        fun summary(): String = when {
            ok -> "直链可下载：HTTP $httpCode，收到 $bytes 字节，用时 ${elapsedMs}ms" +
                if (totalBytes > 0) "，文件 ${totalBytes / 1024 / 1024}MB" else ""
            httpCode == 0 -> "连不上直链服务器（$host），用时 ${elapsedMs}ms —— 网络或 DNS 问题"
            httpCode == 403 -> "HTTP 403：直链没有权限（令牌或签名问题）"
            httpCode == 404 -> "HTTP 404：文件已不在原位置"
            httpCode == 416 -> "HTTP 416：Range 请求被拒（文件可能被替换过）"
            else -> "HTTP $httpCode：直链返回异常"
        }
    }

    /**
     * 对 [url] 发一个只取前 1KB 的 GET。
     *
     * 不抛异常：所有失败都折成 [Result]（httpCode = 0 表示连不上），
     * 这样界面只需要展示一行结论，用户截图就能反馈。
     */
    fun probe(url: String, timeoutMs: Int = 10_000): Result {
        val host = runCatching { URL(url).host }.getOrDefault("?")
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-1023")
                setRequestProperty("User-Agent", AliyunEndpoints.UA)
            }
            val code = conn.responseCode
            val bytes = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.read(ByteArray(1_024)) } ?: 0
            }.getOrDefault(0)
            val total = parseTotalBytes(conn.getHeaderField("Content-Range"))
            Result(
                httpCode = code,
                bytes = if (code in 200..299) bytes else 0,
                elapsedMs = System.currentTimeMillis() - started,
                host = host,
                totalBytes = total,
            )
        } catch (e: Exception) {
            Result(
                httpCode = 0,
                bytes = 0,
                elapsedMs = System.currentTimeMillis() - started,
                host = host,
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 从 `bytes 0-1023/3456789` 里取总长度（纯函数，单测覆盖） */
    fun parseTotalBytes(contentRange: String?): Long {
        val raw = contentRange ?: return 0L
        val afterSlash = raw.substringAfter('/', "").trim()
        return afterSlash.toLongOrNull() ?: 0L
    }
}
