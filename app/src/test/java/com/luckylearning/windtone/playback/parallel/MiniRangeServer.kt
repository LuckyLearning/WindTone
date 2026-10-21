package com.luckylearning.windtone.playback.parallel

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 测试用的最小 HTTP/1.1 服务（支持 Range），只覆盖本项目用到的那点协议。
 *
 * 为什么不用 `com.sun.net.httpserver` / MockWebServer：Android 单测编译在 android.jar 上，
 * 前者不在其中；后者要多一个依赖。`ServerSocket` 在 android.jar 里就有，而且更接近
 * 真机上 `HttpURLConnection` 面对的服务端行为（尤其是 `Connection: close` 与 206）。
 */
internal class MiniRangeServer(
    private val payload: ByteArray,
    private val supportsRange: Boolean = true,
) {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    fun start() {
        Thread({ acceptLoop() }, "mini-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            Thread({ runCatching { handle(client) } }, "mini-http-conn")
                .apply { isDaemon = true }
                .start()
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
            val range = headers["range"]
            val out = s.getOutputStream()
            if (!supportsRange || range == null) {
                writeHead(out, 200, payload.size.toLong(), null)
                out.write(payload)
            } else {
                val parts = range.removePrefix("bytes=").split('-')
                val start = parts[0].toInt().coerceAtLeast(0)
                val end = (parts.getOrNull(1)?.toIntOrNull() ?: (payload.size - 1))
                    .coerceAtMost(payload.size - 1)
                val length = end - start + 1
                writeHead(out, 206, length.toLong(), "bytes $start-$end/${payload.size}")
                out.write(payload, start, length)
            }
            out.flush()
        }
    }

    private fun writeHead(out: OutputStream, code: Int, length: Long, contentRange: String?) {
        val reason = if (code == 206) "Partial Content" else "OK"
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
    }
}
