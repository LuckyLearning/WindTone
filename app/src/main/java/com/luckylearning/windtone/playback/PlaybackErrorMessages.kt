package com.luckylearning.windtone.playback

import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.UserFacingCopy
import com.luckylearning.windtone.ui.AppStrings
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
 *
 * 用户可见文案走 `pem_*` / `ufc_*`（i18n 第 21 批）。
 * [ParallelMsg] 与 `ParallelDownload` 抛出的 `message` 对齐，是**内部协议键**，不进 strings。
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
                s(R.string.pem_http_auth, httpStatus),
                s(R.string.pem_http_auth_next),
            )
            404 -> return withNext(
                s(R.string.pem_http_404),
                s(R.string.pem_http_404_next),
            )
            416 -> return withNext(
                s(R.string.pem_http_416),
                s(R.string.pem_clear_cache_next),
            )
            in 400..599 -> return withNext(
                s(R.string.pem_http_generic, httpStatus),
                s(R.string.pem_http_generic_next),
            )
        }

        parallelOrCacheHint(causeChain)?.let { return it }

        for (t in causeChain) {
            when (t) {
                is UnknownHostException -> return withNext(
                    s(R.string.pem_dns),
                    s(R.string.pem_check_network_next),
                )
                is SocketTimeoutException -> return withNext(
                    s(R.string.pem_timeout),
                    s(R.string.pem_timeout_next),
                )
                is SSLException -> return withNext(
                    s(R.string.pem_ssl),
                    s(R.string.pem_ssl_next),
                )
                is FileNotFoundException -> return withNext(
                    s(R.string.pem_local_missing),
                    s(R.string.pem_rescan_next),
                )
                is EOFException -> return withNext(
                    s(R.string.pem_eof),
                    s(R.string.pem_eof_next),
                )
            }
        }

        when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> return withNext(
                s(R.string.pem_network_failed),
                s(R.string.pem_check_network_next),
            )
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return withNext(
                s(R.string.pem_timeout),
                s(R.string.pem_timeout_next),
            )
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> return withNext(
                s(R.string.pem_file_missing),
                s(R.string.pem_rescan_next),
            )
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> return withNext(
                s(R.string.pem_no_permission),
                s(R.string.pem_no_permission_next),
            )
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> return withNext(
                s(R.string.pem_cleartext),
                s(R.string.pem_cleartext_next),
            )
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> return withNext(
                s(R.string.pem_read_oor),
                s(R.string.pem_clear_cache_next),
            )
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> return withNext(
                s(R.string.pem_bad_http),
                s(R.string.pem_bad_http_next),
            )
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> return withNext(
                s(R.string.pem_bad_content),
                s(R.string.pem_bad_content_next),
            )
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> return withNext(
                s(R.string.pem_parse),
                s(R.string.pem_parse_next),
            )
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                val detail = firstUsefulMessage(causeChain)
                return when {
                    detail != null && looksLocalOrCache(detail) ->
                        withNext(trimDetail(detail), s(R.string.pem_clear_cache_next))
                    hasHttpDataSourceException && isNetworkish(causeChain, detail) ->
                        withNext(s(R.string.pem_network_failed), s(R.string.pem_check_network_next))
                    detail != null -> withNext(
                        s(R.string.pem_read_failed_detail, trimDetail(detail)),
                        s(R.string.pem_read_failed_next),
                    )
                    else -> withNext(s(R.string.pem_read_failed), s(R.string.pem_read_failed_next))
                }
            }
        }

        // 有 HttpDataSourceException 但没有状态码：旧逻辑一律「网络连接失败」，会误伤缓存/分片
        if (hasHttpDataSourceException) {
            val detail = firstUsefulMessage(causeChain)
            return when {
                detail != null && looksLocalOrCache(detail) ->
                    withNext(trimDetail(detail), s(R.string.pem_clear_cache_next))
                isNetworkish(causeChain, detail) ->
                    withNext(s(R.string.pem_network_failed), s(R.string.pem_check_network_next))
                detail != null -> withNext(
                    s(R.string.pem_online_read_detail, trimDetail(detail)),
                    s(R.string.pem_online_read_next),
                )
                else -> withNext(s(R.string.pem_online_read), s(R.string.pem_online_read_next))
            }
        }

        return withNext(errorCodeName, s(R.string.pem_diag_next))
    }

    /** A4：原因 + 一条可操作路径（句号衔接，便于 Snackbar 一次读完） */
    internal fun withNext(reason: String, nextStep: String): String =
        UserFacingCopy.withNext(reason, nextStep)

    private fun parallelOrCacheHint(chain: List<Throwable>): String? {
        val joined = chain.mapNotNull { it.message }.joinToString(" ")
        return when {
            ParallelMsg.WAIT_TIMEOUT in joined -> withNext(
                s(R.string.pem_parallel_timeout),
                s(R.string.pem_parallel_timeout_next),
            )
            ParallelMsg.INCOMPLETE in joined -> withNext(
                s(R.string.pem_parallel_incomplete),
                s(R.string.pem_clear_cache_next),
            )
            ParallelMsg.CANCELLED in joined -> withNext(
                s(R.string.pem_parallel_cancelled),
                s(R.string.pem_parallel_cancelled_next),
            )
            ParallelMsg.NO_RANGE in joined -> withNext(
                s(R.string.pem_parallel_no_range),
                s(R.string.pem_parallel_no_range_next),
            )
            ParallelMsg.FAIL_PREFIX in joined -> {
                val inner = chain.mapNotNull { it.message }
                    .firstOrNull { it.startsWith(ParallelMsg.FAIL_PREFIX) }
                    ?.substringAfter(ParallelMsg.FAIL_SEP, "")
                    ?.ifBlank { null }
                val reason = if (inner != null) {
                    s(R.string.pem_parallel_fail_detail, trimDetail(inner))
                } else {
                    s(R.string.pem_parallel_fail)
                }
                withNext(reason, s(R.string.pem_parallel_fail_next))
            }
            ParallelMsg.CACHE_SPAN in joined || ("Cache" in joined && "span" in joined.lowercase()) ->
                withNext(s(R.string.pem_cache_read), s(R.string.pem_clear_cache_next))
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
        return ParallelMsg.TOKEN_PARALLEL in message ||
            ParallelMsg.TOKEN_RANGE in message ||
            ParallelMsg.TOKEN_CACHE in message ||
            "cache" in m ||
            "enospc" in m ||
            "no space" in m ||
            "file not found" in m ||
            "permission denied" in m ||
            ParallelMsg.TOKEN_LOCAL in message
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
            ParallelMsg.TOKEN_NETWORK in (detail ?: "")
    }

    private fun trimDetail(message: String): String =
        message.replace('\n', ' ').trim().take(48)

    private fun s(@StringRes id: Int, vararg args: Any): String = AppStrings.s(id, *args)

    /**
     * 与 [com.luckylearning.windtone.playback.parallel.ParallelDownload] 抛出的
     * `IOException.message` 对齐。改并行层文案时必须同步这里，否则分类会退回笼统网络失败。
     * **不进 i18n**（内部协议键，不是界面文案）。
     */
    internal object ParallelMsg {
        const val WAIT_TIMEOUT = "等待分段数据超时"
        const val INCOMPLETE = "分段数据不完整"
        const val CANCELLED = "并行下载已取消"
        const val NO_RANGE = "服务器不支持分段"
        const val FAIL_PREFIX = "并行下载失败"
        const val FAIL_SEP = "："
        const val CACHE_SPAN = "缓存片段"
        const val TOKEN_PARALLEL = "并行"
        const val TOKEN_RANGE = "分段"
        const val TOKEN_CACHE = "缓存"
        const val TOKEN_LOCAL = "本地"
        const val TOKEN_NETWORK = "网络"
    }
}
