package com.luckylearning.windtone.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把播放失败翻成用户能看懂、也能截图反馈的一句话。
 *
 * 歌词与音频是两条独立路径（同目录 `.lrc` / 云盘旁路歌词可以成功，音频直链仍可能挂），
 * 所以提示条**不能**凡是 IO 一律写成「网络连接失败」——并行分片超时、缓存片段坏了、
 * 本地文件打不开，都会被 Media3 包成 `HttpDataSourceException` 或 `ERROR_CODE_IO_UNSPECIFIED`。
 */
object PlaybackErrorMessages {

    /**
     * 从 [PlaybackException] 提取人话。生产路径入口。
     */
    fun describe(error: PlaybackException): String {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val bad = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val hasHttp = chain.any { it is HttpDataSource.HttpDataSourceException }
        return describe(
            httpStatus = bad?.responseCode ?: 0,
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            causeChain = chain,
            hasHttpDataSourceException = hasHttp,
        )
    }

    /**
     * 纯规则入口，方便单测不必构造完整 Media3 异常图。
     */
    fun describe(
        httpStatus: Int,
        errorCode: Int,
        errorCodeName: String,
        causeChain: List<Throwable>,
        hasHttpDataSourceException: Boolean,
    ): String {
        when (httpStatus) {
            401, 403 -> return "HTTP $httpStatus，直链没有权限或已过期"
            404 -> return "HTTP 404，文件已不在原位置"
            416 -> return "HTTP 416，缓存片段不完整"
            in 400..599 -> return "HTTP $httpStatus"
        }

        parallelOrCacheHint(causeChain)?.let { return it }

        for (t in causeChain) {
            when (t) {
                is UnknownHostException -> return "无法解析服务器地址（DNS）"
                is SocketTimeoutException -> return "网络连接超时"
                is SSLException -> return "安全连接失败"
                is FileNotFoundException -> return "本地文件不存在或无法打开"
                is EOFException -> return "文件数据不完整"
            }
        }

        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> return "网络连接失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return "网络连接超时"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> return "文件不存在或无法打开"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> return "没有读取权限"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> return "禁止明文 HTTP 连接"
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                return "读取位置超出范围（缓存可能损坏）"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> return "服务器返回异常状态"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> return "服务器返回了非音频内容"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> return "音频格式无法解析"
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                val detail = firstUsefulMessage(causeChain)
                return when {
                    detail != null && looksLocalOrCache(detail) ->
                        trimDetail(detail)
                    hasHttpDataSourceException && isNetworkish(causeChain, detail) ->
                        "网络连接失败"
                    detail != null -> "读取音频失败（${trimDetail(detail)}）"
                    else -> "读取音频失败"
                }
            }
        }

        // 有 HttpDataSourceException 但没有状态码：旧逻辑一律「网络连接失败」，会误伤缓存/分片
        if (hasHttpDataSourceException) {
            val detail = firstUsefulMessage(causeChain)
            return when {
                detail != null && looksLocalOrCache(detail) -> trimDetail(detail)
                isNetworkish(causeChain, detail) -> "网络连接失败"
                detail != null -> "读取在线音频失败（${trimDetail(detail)}）"
                else -> "读取在线音频失败"
            }
        }

        return errorCodeName
    }

    private fun parallelOrCacheHint(chain: List<Throwable>): String? {
        val joined = chain.mapNotNull { it.message }.joinToString(" ")
        return when {
            "等待分段数据超时" in joined -> "并行分片等待超时（未必是断网）"
            "分段数据不完整" in joined -> "并行分片数据不完整"
            "并行下载已取消" in joined -> "并行下载已取消"
            "服务器不支持分段" in joined -> "服务器不支持分段下载"
            "并行下载失败" in joined -> {
                val inner = chain.mapNotNull { it.message }
                    .firstOrNull { it.startsWith("并行下载失败") }
                    ?.substringAfter("：", "")
                    ?.ifBlank { null }
                if (inner != null) "并行下载失败（${trimDetail(inner)}）" else "并行下载失败"
            }
            "缓存片段" in joined || ("Cache" in joined && "span" in joined.lowercase()) ->
                "本地缓存读取失败"
            else -> null
        }
    }

    private fun firstUsefulMessage(chain: List<Throwable>): String? =
        chain.asSequence()
            .mapNotNull { it.message?.trim()?.takeIf { m -> m.isNotEmpty() } }
            // 跳过 Media3 自己的空壳句，优先下层真实原因
            .firstOrNull { m ->
                !m.startsWith("Source error", ignoreCase = true) &&
                    !m.contains("ERROR_CODE_", ignoreCase = false)
            }

    private fun looksLocalOrCache(message: String): Boolean {
        val m = message.lowercase()
        return "并行" in message ||
            "分段" in message ||
            "缓存" in message ||
            "cache" in m ||
            "enospc" in m ||
            "no space" in m ||
            "file not found" in m ||
            "permission denied" in m ||
            "本地" in message
    }

    private fun isNetworkish(chain: List<Throwable>, detail: String?): Boolean {
        if (chain.any {
                it is UnknownHostException ||
                    it is SocketTimeoutException ||
                    it is SSLException ||
                    it is java.net.ConnectException ||
                    it is java.net.NoRouteToHostException
            }
        ) {
            return true
        }
        val m = (detail ?: "").lowercase()
        return "timeout" in m ||
            "timed out" in m ||
            "failed to connect" in m ||
            "connection" in m ||
            "unreachable" in m ||
            "unknown host" in m ||
            "unable to resolve" in m ||
            "网络" in (detail ?: "")
    }

    private fun trimDetail(message: String): String =
        message.replace('\n', ' ').trim().take(48)
}