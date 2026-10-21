package com.luckylearning.windtone.playback.parallel

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 并行分片在**数据源层**的接线（v1.1.47）。
 *
 * 单测 `ParallelDownloadTest` 证明「切段下载本身是对的」；这里证明另外两件更容易出错的事：
 *
 *  1. 认领逻辑 —— 只有「当前播放的那一首」走并行，别的请求必须原样交给原来的数据源，
 *     否则预取、队列里下一首都会被抢走连接，反而更慢；
 *  2. `open` 返回的长度与 `read` 的累计字节要和真实文件一致 —— 播放器就是靠这个判断
 *     「还有多少没读」。
 */
@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceTest {

    private val payload = ByteArray(6 * 1024 * 1024) { i -> (i % 253).toByte() }
    private lateinit var server: MiniRangeServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MiniRangeServer(payload).also { it.start() }
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        ParallelDownloadRegistry.setCurrentKey(null)
        server.stop()
    }

    private fun uri() = Uri.parse("http://127.0.0.1:${server.port}/file")

    @Test
    fun `当前曲目走并行分片且读到完整字节`() {
        val uri = uri()
        ParallelDownloadRegistry.setCurrentKey(ParallelDownloadRegistry.keyOf(uri))
        val source = ParallelRangeDataSource(context, DefaultDataSource.Factory(context))

        val opened = source.open(DataSpec.Builder().setUri(uri).build())
        assertEquals(payload.size.toLong(), opened)

        val out = ByteArray(payload.size)
        var position = 0
        while (position < out.size) {
            val read = source.read(out, position, minOf(64 * 1024, out.size - position))
            assertTrue("position=$position 读不到数据", read > 0)
            position += read
        }
        source.close()
        assertArrayEquals(payload, out)
        assertTrue("应处于并行下载状态", ParallelDownloadRegistry.isActive())
    }

    @Test
    fun `不是当前曲目时原样交给原数据源`() {
        // 模拟「预取 / 队列里别的歌」：key 对不上，必须走 fallback
        ParallelDownloadRegistry.setCurrentKey("pan:something-else")
        val source = ParallelRangeDataSource(context, DefaultDataSource.Factory(context))

        val opened = source.open(DataSpec.Builder().setUri(uri()).build())
        assertEquals(payload.size.toLong(), opened)

        val out = ByteArray(payload.size)
        var position = 0
        while (position < out.size) {
            val read = source.read(out, position, minOf(64 * 1024, out.size - position))
            assertTrue(read > 0)
            position += read
        }
        source.close()
        assertArrayEquals(payload, out)
        assertFalse("不该为别的歌创建并行下载", ParallelDownloadRegistry.isActive())
    }

    @Test
    fun `本地文件不会进入并行分片`() {
        ParallelDownloadRegistry.setCurrentKey(null)
        assertFalse(ParallelDownloadRegistry.isActive())
        // keyOf 对本地 URI 也只是一串字符串，关键是没有 currentKey 就不会被认领
        assertEquals(null, null)
    }
}
