package com.luckylearning.windtone.data.online

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放列表抓取器（2026-10-21 从 [OnlinePlaylists] 拆出）。
 *
 * 存在的唯一理由：**给编排层留一个不连网络的缝**。`add` / `refresh` 的规则
 * （抓不到就不入库、刷新失败保留旧条目、UNSUPPORTED 不入库）是真行为，
 * 但不该靠真连网络来测 —— 单测里注入一个假抓取器即可，见 [OnlinePlaylists] 的构造参数。
 *
 * 拆开也顺手解决了另一个问题：抓取相关的超时 / UA / 4MB 上限 / 编码回落
 * 全在这一个文件里，改网络策略不用翻编排逻辑。
 */
internal object NetworkPlaylistFetcher {

    private const val USER_AGENT = "WindTone/1.1 (Android; online-playlist)"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 抓取 + 解析；网络异常在这里转成用户能懂的话（见 [OnlinePlaylistRules.friendly]） */
    suspend fun fetch(url: String): ParsedPlaylist = withContext(Dispatchers.IO) {
        try {
            fetchOnce(url)
        } catch (e: IOException) {
            error(OnlinePlaylistRules.friendly(e))
        }
    }

    private fun fetchOnce(url: String): ParsedPlaylist {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            // 部分电台按 Accept-Encoding 返回 gzip，交给连接自己解；声明出来只是为了不被拒
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("服务器返回 HTTP $code")
            val bytes = conn.inputStream.use { OnlinePlaylistRules.readCapped(it) }
            if (bytes.isEmpty()) error("这个地址返回了空内容")
            val text = OnlinePlaylistRules.decode(bytes)
            // 抓回来是网页时给出明确提示：用户最常犯的错就是把播放页地址贴进来
            if (OnlinePlaylistRules.looksLikeHtml(text)) {
                error("这个地址返回的是网页而不是播放列表，请填 m3u / m3u8 / pls 的直链")
            }
            return M3uParser.parse(text, baseUrl = conn.url.toString())
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
