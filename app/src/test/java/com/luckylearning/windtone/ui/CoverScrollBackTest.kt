package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.online.OnlineCover
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 歌单封面第一次滚到能画出来，滑出再滑回却变回占位图。
 *
 * 占位是首字。位图还在（或只剩已下载的字节）时，重新进入组合的第一帧就不能再画这个字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverScrollBackTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: ServerSocket
    private val hits = AtomicInteger(0)
    private lateinit var jpeg: ByteArray
    private lateinit var url: String

    @Before
    fun setUp() {
        holdFirst = false
        OnlineCover.clearForTest()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        jpeg = out.toByteArray()
        server = ServerSocket(0)
        url = "http://127.0.0.1:${server.localPort}/a.jpg"
        thread(name = "cover-test-http", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    val n = hits.incrementAndGet()
                    try {
                        if (n == 1 && holdFirst) Thread.sleep(2_000)
                        socket.getOutputStream().use { output ->
                            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\nConnection: close\r\n\r\n"
                            output.write(head.toByteArray(Charsets.US_ASCII))
                            output.write(jpeg)
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        holdFirst = false
        runCatching { server.close() }
        OnlineCover.clearForTest()
    }

    @Volatile
    private var holdFirst = false

    @Test
    fun decodedBitmapEvictionStillRestoresFromBytesWithoutAnotherRequest() {
        val first = runBlocking { OnlineCover.thumbUrl(url, 32) }
        assertNotNull(first)
        assertEquals(1, hits.get())
        OnlineCover.dropDecodedThumbsForTest()
        val again = OnlineCover.peekThumb(url, 32)
        assertNotNull("位图被挤掉之后应还能用已下载的字节解码", again)
        assertEquals("滚回来不应再请求一次", 1, hits.get())
    }

    @Test
    fun cancellingTheFirstLoadDoesNotStickTheCoverOnThePlaceholder() {
        holdFirst = true
        runBlocking {
            val job = launch { OnlineCover.thumbUrl(url, 32) }
            delay(200)
            job.cancel()
            job.join()
        }
        holdFirst = false
        val again = runBlocking { OnlineCover.thumbUrl(url, 32) }
        assertNotNull("取消之后再要这张图不能因为冷却一直是占位", again)
    }

    @Test
    fun scrollingBackShowsTheCoverOnTheFirstFrame() {
        val loaded = runBlocking { OnlineCover.thumbUrl(url, 48) }
        assertNotNull(loaded)
        OnlineCover.dropDecodedThumbsForTest()
        compose.mainClock.autoAdvance = false
        var show by mutableStateOf(true)
        compose.setContent {
            MusicPlayerTheme {
                if (show) {
                    LxCover(
                        url = url,
                        cacheKey = "sheet:kw:1",
                        name = "夏",
                        targetPx = 48,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(compose.onAllNodesWithText("夏").fetchSemanticsNodes().isEmpty())
        show = false
        compose.mainClock.advanceTimeByFrame()
        show = true
        compose.mainClock.advanceTimeByFrame()
        assertTrue(compose.onAllNodesWithText("夏").fetchSemanticsNodes().isEmpty())
        assertEquals(1, hits.get())
    }
}
