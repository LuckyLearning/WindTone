package com.luckylearning.windtone.playback

import android.net.Uri
import androidx.media3.datasource.cache.CacheSpan
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** 边播边存的纯规则：文件名稳定、后缀可取、跨直链变化仍落同一个文件。 */
@RunWith(RobolectricTestRunner::class)
class OnlineSongSaverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun song(
        url: String,
        name: String = "Hello - Adele.mp3",
        mime: String? = null,
    ): Song = Song(
        id = 0L,
        uri = Uri.parse(url),
        title = name.substringBeforeLast('.'),
        artist = "Adele",
        album = null,
        mimeType = mime,
        displayName = name,
        source = SongSource.ONLINE,
    )

    @Test
    fun `文件名去掉非法字符并保留中文`() {
        val name = OnlineSongSaver.sanitizeFileName("周杰伦 / 稻香:demo?.mp3")
        assertEquals("周杰伦 _ 稻香_demo_.mp3", name)
    }

    @Test
    fun `文件名过长时截断但不会变成空`() {
        assertEquals(80, OnlineSongSaver.sanitizeFileName("a".repeat(120)).length)
        assertEquals("song", OnlineSongSaver.sanitizeFileName("///..."))
    }

    @Test
    fun `后缀优先原文件名其次 mime`() {
        val song = song("https://cdn.example.com/track", name = "hello.mp3")
        assertEquals("mp3", OnlineSongSaver.extensionOf(song))
        assertEquals("flac", OnlineSongSaver.extensionOf(song.copy(mimeType = "audio/flac", displayName = "hello")))
        assertEquals("m4a", OnlineSongSaver.extensionOf(song.copy(displayName = "hello", mimeType = "audio/mp4")))
    }

    @Test
    fun `同一首换直链仍写同一个文件 不同首不会撞名`() {
        val first = song("https://cdn-a.example.com/one.mp3#lx:kw:1")
        val sameWithNewUrl = song("https://cdn-b.example.com/two.mp3#lx:kw:1")
        val other = song("https://cdn-a.example.com/one.mp3#lx:kw:2")
        assertEquals(OnlineSongSaver.outputFileName(first), OnlineSongSaver.outputFileName(sameWithNewUrl))
        assertNotEquals(OnlineSongSaver.outputFileName(first), OnlineSongSaver.outputFileName(other))
    }

    @Test
    fun `缓存分片按顺序拼接 不再按 position 跳过文件内容`() {
        val first = temp.newFile("0").apply { writeText("hello") }
        val second = temp.newFile("1").apply { writeText("!!!") }
        val spans = listOf(
            CacheSpan("k", 0L, 5L, 0L, first),
            CacheSpan("k", 5L, 3L, 0L, second),
        )
        val out = ByteArrayOutputStream()
        val copied = OnlineSongSaver.copySpansTo(spans, 8L, out)
        assertEquals(8L, copied)
        assertEquals("hello!!!", out.toString())
    }

    @Test
    fun `缓存分片总长对不上时拒绝写半首`() {
        val first = temp.newFile("0").apply { writeText("hello") }
        val spans = listOf(CacheSpan("k", 0L, 5L, 0L, first))
        try {
            OnlineSongSaver.copySpansTo(spans, 8L, ByteArrayOutputStream())
            fail("缺 3 字节时应拒绝")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("缓存未完整"))
        }
    }

    @Test
    fun `缓存分片有缺口时拒绝拼接`() {
        val second = temp.newFile("1").apply { writeText("!!!") }
        val spans = listOf(CacheSpan("k", 5L, 3L, 0L, second))
        try {
            OnlineSongSaver.copySpansTo(spans, 8L, ByteArrayOutputStream())
            fail("从 5 开始的分片不能单独拼成整首")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("不连续"))
        }
    }

    @Test
    fun `mime 类型随后缀 未知退到音频兜底`() {
        assertEquals("audio/flac", OnlineSongSaver.mimeTypeOf("x.flac", null))
        assertEquals("audio/mpeg", OnlineSongSaver.mimeTypeOf("x.mp3", "audio/flac"))
        assertEquals("audio/ogg", OnlineSongSaver.mimeTypeOf("x.ogg", null))
        assertEquals("audio/mpeg", OnlineSongSaver.mimeTypeOf("x.bin", null))
    }

    @Test
    fun `本地歌不会被当成可保存的在线歌`() {
        val local = song("content://media/external/audio/media/12")
        assertTrue(!CachePrefetchPolicy.isRemote(local.uri.scheme))
    }

    @Test
    fun `未授权目录时开关不生效`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        assertTrue(!OnlineSongSaver.enabled(app, uri.toString()))
        assertTrue(!OnlineSongSaver.enabled(app, ""))
    }
}
