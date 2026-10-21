package com.luckylearning.windtone.playback.parallel

import android.net.Uri
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * 并行分片的端到端单测（v1.1.47）。
 *
 * 这套代码有几百行、5 个线程、共享一个文件 —— 只靠编译通过不敢发出去。这里起一个
 * **真实的本地 HTTP 服务**（支持 Range），把整首 3MB 读完再逐字节比对：
 * 段错位、段重叠、段漏掉、写指针串了，任何一种都会在这里被抓到。
 *
 * 另外钉住降级路径：服务器忽略 Range（回 200）时必须抛错让上层退回单连接，
 * 而不是拿 5 倍流量硬下。
 *
 * 服务端为什么自己写而不是用 `com.sun.net.httpserver`：Android 单测编译在 android.jar 上，
 * 没有 JDK 的 httpserver 模块；`ServerSocket` 在 android.jar 里有，够用也更接近真机栈。
 */
@RunWith(RobolectricTestRunner::class)
class ParallelDownloadTest {

    private val payload = ByteArray(3 * 1024 * 1024) { i -> (i % 251).toByte() }

    private lateinit var ranged: MiniRangeServer
    private lateinit var noRange: MiniRangeServer

    @Before
    fun setUp() {
        ranged = MiniRangeServer(payload, supportsRange = true).also { it.start() }
        noRange = MiniRangeServer(payload, supportsRange = false).also { it.start() }
    }

    @After
    fun tearDown() {
        ranged.stop()
        noRange.stop()
    }

    private fun newDownload(port: Int): Pair<ParallelDownload, File> {
        val total = payload.size.toLong()
        val segments = SegmentPlanner.plan(total)
        assertTrue("3MB 应切成多段", segments.size > 1)
        val file = File.createTempFile("wt-parallel", ".part")
        val download = ParallelDownload(
            key = "test",
            uri = Uri.parse("http://127.0.0.1:$port/file"),
            headers = emptyMap(),
            userAgent = "WindToneTest",
            file = file,
            totalBytes = total,
            segments = segments,
        )
        download.start()
        return download to file
    }

    @Test
    fun `并行分段读出与源文件逐字节一致`() {
        val (download, file) = newDownload(ranged.port)
        try {
            val out = ByteArray(payload.size)
            var position = 0L
            while (position < payload.size) {
                val want = minOf(8 * 1024L, payload.size - position).toInt()
                val read = download.readAt(position, out, position.toInt(), want)
                assertTrue("position=$position 读不到数据（read=$read）", read > 0)
                position += read
            }
            assertArrayEquals(payload, out)
        } finally {
            download.reap()
            assertFalse("收尾后临时文件必须删掉", file.exists())
        }
    }

    @Test
    fun `不支持 Range 的服务器会报错而不是读错数据`() {
        val (download, file) = newDownload(noRange.port)
        try {
            // 段 1..N 会拿到 200，判定为「服务器不支持分段」并抛错，
            // 由 ParallelRangeDataSource 退回单连接（这里只验证它确实失败）
            assertThrows(IOException::class.java) {
                download.readAt((payload.size / 2).toLong(), ByteArray(1024), 0, 1024)
            }
        } finally {
            download.reap()
            assertFalse(file.exists())
        }
    }

    @Test
    fun `取消后立刻能收尾且不留下文件`() {
        val (download, file) = newDownload(ranged.port)
        download.cancel()
        download.reap()
        assertFalse(file.exists())
    }
}
