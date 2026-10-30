package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.UserFacingCopy
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把播放失败翻成用户能看懂、也能截图反馈的一句话（A4：原因 + 一条可操作路径）。
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
            401, 403 -> return withNext(
                "HTTP $httpStatus，直链没有权限或已过期",
                "到设置 → 在线服务重新授权云盘",
            )
            404 -> return withNext(
                "HTTP 404，文件已不在原位置",
                "确认云盘文件还在，或从曲库移除后重新导入",
            )
            416 -> return withNext(
                "HTTP 416，缓存片段不完整",
                "到设置 → 在线服务清理缓存后重试",
            )
            in 400..599 -> return withNext(
                "HTTP $httpStatus",
                "稍后重试；若反复出现，到设置 → 在线服务重新授权",
            )
        }

        parallelOrCacheHint(causeChain)?.let { return it }

        for (t in causeChain) {
            when (t) {
                is UnknownHostException -> return withNext(
                    "无法解析服务器地址（DNS）",
                    "检查网络后重试",
                )
                is SocketTimeoutException -> return withNext(
                    "网络连接超时",
                    "检查网络后重试；弱网可到设置降低并行下载",
                )
                is SSLException -> return withNext(
                    "安全连接失败",
                    "核对系统时间是否准确，再检查网络后重试",
                )
                is FileNotFoundException -> return withNext(
                    "本地文件不存在或无法打开",
                    "确认文件还在，或重新扫描曲库",
                )
                is EOFException -> return withNext(
                    "文件数据不完整",
                    "重新下载或换一份完整文件",
                )
            }
        }

        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> return withNext(
                "网络连接失败",
                "检查网络后重试",
            )
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return withNext(
                "网络连接超时",
                "检查网络后重试；弱网可到设置降低并行下载",
            )
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> return withNext(
                "文件不存在或无法打开",
                "确认文件还在，或重新扫描曲库",
            )
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> return withNext(
                "没有读取权限",
                "到系统设置为本应用授予存储/音频权限",
            )
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> return withNext(
                "禁止明文 HTTP 连接",
                "改用 HTTPS 源，或到系统允许本应用明文流量",
            )
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> return withNext(
                "读取位置超出范围（缓存可能损坏）",
                "到设置 → 在线服务清理缓存后重试",
            )
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> return withNext(
                "服务器返回异常状态",
                "稍后重试；云盘歌可到设置 → 在线服务重新授权",
            )
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> return withNext(
                "服务器返回了非音频内容",
                "确认链接仍是音频；云盘歌请重新授权后再试",
            )
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> return withNext(
                "音频格式无法解析",
                "换编码格式或转码后再试",
            )
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                val detail = firstUsefulMessage(causeChain)
                return when {
                    detail != null && looksLocalOrCache(detail) ->
                        withNext(trimDetail(detail), "到设置 → 在线服务清理缓存后重试")
                    hasHttpDataSourceException && isNetworkish(causeChain, detail) ->
                        withNext("网络连接失败", "检查网络后重试")
                    detail != null -> withNext(
                        "读取音频失败（${trimDetail(detail)}）",
                        "检查网络或文件后重试",
                    )
                    else -> withNext("读取音频失败", "检查网络或文件后重试")
                }
            }
        }

        // 有 HttpDataSourceException 但没有状态码：旧逻辑一律「网络连接失败」，会误伤缓存/分片
        if (hasHttpDataSourceException) {
            val detail = firstUsefulMessage(causeChain)
            return when {
                detail != null && looksLocalOrCache(detail) ->
                    withNext(trimDetail(detail), "到设置 → 在线服务清理缓存后重试")
                isNetworkish(causeChain, detail) ->
                    withNext("网络连接失败", "检查网络后重试")
                detail != null -> withNext(
                    "读取在线音频失败（${trimDetail(detail)}）",
                    "检查网络后重试；云盘歌可重新授权",
                )
                else -> withNext("读取在线音频失败", "检查网络后重试；云盘歌可重新授权")
            }
        }

        return withNext(errorCodeName, "到设置 → 关于与诊断复制诊断包反馈")
    }

    /** A4：原因 + 一条可操作路径（句号衔接，便于 Snackbar 一次读完） */
    internal fun withNext(reason: String, nextStep: String): String =
        UserFacingCopy.withNext(reason, nextStep)

    private fun parallelOrCacheHint(chain: List<Throwable>): String? {
        val joined = chain.mapNotNull { it.message }.joinToString(" ")
        return when {
            "等待分段数据超时" in joined -> withNext(
                "并行分片等待超时（未必是断网）",
                "到设置降低并行下载，或检查网络后重试",
            )
            "分段数据不完整" in joined -> withNext(
                "并行分片数据不完整",
                "到设置 → 在线服务清理缓存后重试",
            )
            "并行下载已取消" in joined -> withNext(
                "并行下载已取消",
                "重新点播放即可",
            )
            "服务器不支持分段" in joined -> withNext(
                "服务器不支持分段下载",
                "到设置关闭并行下载后重试",
            )
            "并行下载失败" in joined -> {
                val inner = chain.mapNotNull { it.message }
                    .firstOrNull { it.startsWith("并行下载失败") }
                    ?.substringAfter("：", "")
                    ?.ifBlank { null }
                val reason = if (inner != null) "并行下载失败（${trimDetail(inner)}）" else "并行下载失败"
                withNext(reason, "检查网络后重试，或到设置关闭并行下载")
            }
            "缓存片段" in joined || ("Cache" in joined && "span" in joined.lowercase()) ->
                withNext("本地缓存读取失败", "到设置 → 在线服务清理缓存后重试")
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
