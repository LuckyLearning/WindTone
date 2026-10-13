package com.luckylearning.windtone.data.webdav

import android.util.Base64
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * 最小可用的 WebDAV 客户端（零依赖，HttpURLConnection + XmlPullParser）。
 *
 * 只用到两个动词：
 *  - [PROPFIND]（Depth: 1）列目录 —— 扫描 / 目录浏览
 *  - GET 取文件流 —— 下载同目录 .lrc 歌词；音频播放本身交给 ExoPlayer 的
 *    DefaultHttpDataSource（原生支持 Range 请求，进度条可拖）
 *
 * 认证用 Basic；凭证由 [MusicViewModel] 在连接成功后写入 [config]，
 * 进程内共享给歌词下载等旁路请求。
 *
 * 之所以选 WebDAV 而不是百度网盘开放平台：2026-06-03 后开放平台新应用
 * 只能读 /apps/{appname} 隔离目录且拿不到流式播放能力（详见
 * docs/功能规划-第四期-云盘音乐.md），而 WebDAV 是公开协议、无审核、
 * 配合 Alist/OpenList 可桥接百度/阿里等主流网盘。
 */
object WebDavClient {

    private const val TAG = "WebDavClient"
    private const val TIMEOUT_MS = 15_000

    /** 当前凭证（连接成功后设置；断开置 null）。歌词下载等旁路请求共用。 */
    @Volatile
    var config: Config? = null

    data class Config(val baseUrl: String, val user: String?, val password: String?)

    data class Entry(
        /** 相对 baseUrl 的目录路径，始终以 / 结尾；根目录为 "" */
        val path: String,
        val name: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /** 服务器返回的错误（带 HTTP 状态码，便于界面提示 401/404 等） */
    class HttpException(val code: Int, message: String) : IOException(message)

    // ------------------------------------------------------------ 目录列表

    /**
     * 列出 [dirPath]（以 / 结尾，根目录传 ""）下的直接子项。
     * 返回值不包含目录本身。
     */
    fun listDir(dirPath: String): List<Entry> {
        val cfg = config ?: throw IOException("WebDAV 未连接")
        val url = joinUrl(cfg.baseUrl, dirPath)
        val conn = open(url, cfg, method = "PROPFIND")
        conn.setRequestProperty("Depth", "1")
        try {
            val code = conn.responseCode
            if (code != 207) throw HttpException(code, "PROPFIND 失败（HTTP $code）")
            val stream = conn.inputStream
            return parseMultiStatus(stream, expectSelf = joinUrlPath(cfg.baseUrl, dirPath))
        } finally {
            conn.disconnect()
        }
    }

    /** 连通性测试：对根目录发一次 PROPFIND，成功返回服务器描述 */
    fun testConnection(baseUrl: String, user: String?, password: String?): Result<String> {
        val temp = Config(baseUrl, user, password)
        val conn = try {
            open(joinUrl(baseUrl, ""), temp, method = "PROPFIND")
        } catch (e: Exception) {
            return Result.failure(e)
        }
        conn.setRequestProperty("Depth", "0")
        conn.connectTimeout = 8_000
        return try {
            val code = conn.responseCode
            when {
                code in 200..299 || code == 207 ->
                    Result.success(
                        conn.headerFields?.entries
                            ?.firstOrNull { it.key.equals("Server", true) }
                            ?.value?.firstOrNull() ?: "连接成功"
                    )

                code == 401 -> Result.failure(HttpException(code, "账号或密码不对（HTTP 401）"))
                else -> Result.failure(HttpException(code, "服务器返回 HTTP $code，请检查地址"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 文件流

    /** 取远端文件的字节流（歌词下载用），[limit] 内截断，超限返回 null */
    fun fetchBytes(fileUrl: String, limit: Int): ByteArray? {
        val cfg = config
        val conn = if (cfg != null) open(fileUrl, cfg, method = "GET") else open(fileUrl, null, "GET")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetchBytes HTTP $code: $fileUrl")
                return null
            }
            conn.inputStream.use { readLimited(it, limit) }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBytes 失败: $fileUrl", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 内部

    private fun open(url: String, cfg: Config?, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        if (cfg?.user?.isNotEmpty() == true) {
            val token = Base64.encodeToString(
                "${cfg.user}:${cfg.password.orEmpty()}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
    }

    /**
     * 拼 URL：baseUrl 保留原始编码，path 部分按段做 UTF-8 百分号编码。
     * [trailingSlash] 为 true 时结果以 / 结尾（目录 PROPFIND 需要），
     * 为 false 时是纯文件 URL（音频播放地址，不能带尾斜杠）。
     */
    fun joinUrl(baseUrl: String, path: String, trailingSlash: Boolean = true): String {
        val base = baseUrl.trimEnd('/')
        val encoded = path.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                // 只编码不安全字符；% 与字母数字与常用符号保留
                java.net.URLEncoder.encode(segment, "UTF-8")
                    .replace("+", "%20")
            }
        return when {
            encoded.isEmpty() -> "$base/"
            trailingSlash -> "$base/$encoded/"
            else -> "$base/$encoded"
        }
    }

    /** 只拼路径不做编码（用于 multiStatus 的 href 自匹配判断） */
    private fun joinUrlPath(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = path.trim('/')
        return if (p.isEmpty()) "$base/" else "$base/$p/"
    }

    /**
     * 解析 207 MultiStatus。
     * [expectSelf]：当前目录自身的 href 前缀，用于剔除响应里的第一项（目录本身）。
     */
    private fun parseMultiStatus(stream: InputStream, expectSelf: String): List<Entry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser() as XmlPullParser
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        data class Raw(var href: String, var isDir: Boolean = false, var size: Long = 0L, var mtime: Long = 0L)

        val items = ArrayList<Raw>()
        var current: Raw? = null
        var text = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name.lowercase()) {
                        "response" -> { current = Raw(""); items.add(current!!) }
                        "collection" -> current?.isDir = true
                    }
                }

                XmlPullParser.TEXT -> text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val cur = current
                    val t = text.toString().trim()
                    when (parser.name.lowercase()) {
                        "href" -> cur?.href = t
                        "getcontentlength" -> cur?.size = t.toLongOrNull() ?: 0L
                        "getlastmodified" -> cur?.mtime = parseHttpDate(t)
                        "response" -> current = null
                    }
                }
            }
            parser.next()
        }

        return items.mapNotNull { raw ->
            val path = decodeHrefPath(raw.href) ?: return@mapNotNull null
            // 剔除目录自身（href 与请求路径同源）
            if (raw.href.trimEnd('/') == expectSelf.trimEnd('/')) return@mapNotNull null
            val name = path.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) return@mapNotNull null
            Entry(
                path = if (raw.isDir) "$path/" else path,
                name = name,
                isDir = raw.isDir,
                sizeBytes = raw.size,
                lastModified = raw.mtime,
            )
        }
    }

    /** 把 href（可能带完整 URL、可能百分号编码）还原成「相对站点根的路径」 */
    private fun decodeHrefPath(href: String): String? {
        val raw = if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            runCatching { URL(href).path }.getOrNull() ?: return null
        } else href
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: raw
    }

    private fun parseHttpDate(s: String): Long = runCatching {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        fmt.parse(s)?.time ?: 0L
    }.getOrDefault(0L)

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            val take = minOf(n, limit - total)
            if (take > 0) out.write(buf, 0, take)
            total += n
            if (total >= limit) return null // 超限：不是歌词，放弃
        }
        return out.toByteArray()
    }
}
