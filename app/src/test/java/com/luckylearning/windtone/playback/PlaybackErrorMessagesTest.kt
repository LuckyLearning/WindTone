package com.luckylearning.windtone.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 播放失败文案（用户反馈：歌词已显示却提示「网络错误」）。
 *
 * 歌词与音频是两条路径；Media3 又常把缓存 / 并行分片失败包成
 * `HttpDataSourceException` 或 `ERROR_CODE_IO_UNSPECIFIED`。这里钉住：
 * 只有真正的网络失败才说「网络连接失败」。
 */
class PlaybackErrorMessagesTest {

    @Test
    fun `HTTP 403 说权限而不是网络`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 403,
            errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            causeChain = emptyList(),
            hasHttpDataSourceException = true,
        )
        assertTrue(msg.contains("403"))
        assertTrue(msg.contains("权限") || msg.contains("过期"))
        assertFalse(msg.contains("网络连接失败"))
    }

    @Test
    fun `HTTP 416 说缓存片段`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 416,
            errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            causeChain = emptyList(),
            hasHttpDataSourceException = true,
        )
        assertTrue(msg.contains("416"))
        assertTrue(msg.contains("缓存"))
    }

    @Test
    fun `并行分片超时不说网络连接失败`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 0,
            errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            errorCodeName = "ERROR_CODE_IO_UNSPECIFIED",
            causeChain = listOf(IOException("等待分段数据超时")),
            hasHttpDataSourceException = true,
        )
        assertTrue(msg.contains("并行") || msg.contains("分段"))
        assertFalse("旧逻辑会把任意 HttpDataSourceException 写成网络失败", msg == "网络连接失败")
        assertFalse(msg.contains("网络连接失败"))
    }

    @Test
    fun `并行下载失败带上内层原因`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 0,
            errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            errorCodeName = "ERROR_CODE_IO_UNSPECIFIED",
            causeChain = listOf(IOException("并行下载失败：HTTP 502", IOException("HTTP 502"))),
            hasHttpDataSourceException = true,
        )
        assertTrue(msg.contains("并行下载失败"))
        assertTrue(msg.contains("502"))
        assertFalse(msg.contains("网络连接失败"))
    }

    @Test
    fun `DNS 失败说清楚是解析`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 0,
            errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            errorCodeName = "ERROR_CODE_IO_UNSPECIFIED",
            causeChain = listOf(UnknownHostException("cdn.example.com")),
            hasHttpDataSourceException = true,
        )
        assertTrue(msg.contains("DNS") || msg.contains("解析"))
    }

    @Test
    fun `真正的网络失败码仍说网络连接失败`() {
        assertEquals(
            "网络连接失败",
            PlaybackErrorMessages.describe(
                httpStatus = 0,
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                causeChain = emptyList(),
                hasHttpDataSourceException = true,
            ),
        )
    }

    @Test
    fun `连接超时用超时文案`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 0,
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            causeChain = listOf(SocketTimeoutException("timeout")),
            hasHttpDataSourceException = true,
        )
        assertTrue(msg.contains("超时"))
    }

    @Test
    fun `本地文件打不开不说网络`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 0,
            errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            errorCodeName = "ERROR_CODE_IO_FILE_NOT_FOUND",
            causeChain = listOf(FileNotFoundException("/sdcard/a.mp3")),
            hasHttpDataSourceException = false,
        )
        assertTrue(msg.contains("文件"))
        assertFalse(msg.contains("网络"))
    }

    @Test
    fun `无状态码的 Http 异常若无网络特征则不笼统说网络`() {
        val msg = PlaybackErrorMessages.describe(
            httpStatus = 0,
            errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            errorCodeName = "ERROR_CODE_IO_UNSPECIFIED",
            causeChain = listOf(IOException("unexpected end of stream")),
            hasHttpDataSourceException = true,
        )
        assertFalse(msg == "网络连接失败")
        assertTrue(msg.contains("读取"))
    }

    @Test
    fun `DEFAULT_CACHED_PREFIX_BYTES 合理`() {
        assertEquals(512L * 1024, OnlineMediaCache.DEFAULT_CACHED_PREFIX_BYTES)
    }
}