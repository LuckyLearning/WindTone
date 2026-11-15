package com.anylyric.lyrics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 歌词磁盘缓存。
 *
 * 这层的作用是「同一首歌第二次听不再联网」，所以重点验三件事：
 * 存进去能读回来、空的/超大的不落盘、文件数超上限时淘汰最旧的。
 */
class LyricStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir") ?: ".", "anylyric-store-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `saved lyric can be read back`() {
        val key = LyricMatch.cacheKey("稻香", "周杰伦")
        LyricStore.saveAt(dir, key, "[00:01.000]第一句\n")
        assertEquals("[00:01.000]第一句\n", LyricStore.loadAt(dir, key))
    }

    @Test
    fun `missing key returns null`() {
        assertNull(LyricStore.loadAt(dir, "nosuchkey"))
        assertNull(LyricStore.loadAt(dir, ""))
    }

    @Test
    fun `blank content is not stored`() {
        val key = LyricMatch.cacheKey("稻香", "周杰伦")
        LyricStore.saveAt(dir, key, "   ")
        assertNull(LyricStore.loadAt(dir, key))
    }

    @Test
    fun `oversized content is dropped instead of filling storage`() {
        val key = LyricMatch.cacheKey("巨大文件", "")
        LyricStore.saveAt(dir, key, "x".repeat(200 * 1024))
        assertNull(LyricStore.loadAt(dir, key))
    }

    @Test
    fun `trim keeps the newest files`() {
        val files = ArrayList<File>(5)
        for (i in 1..5) {
            val file = File(dir, "k$i.lrc")
            file.writeText("x")
            // setLastModified 的精度是毫秒，串行写会撞在同一毫秒上，这里显式拉开。
            file.setLastModified(1_000L * i)
            files += file
        }
        LyricStore.trim(dir, 2)
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("k4.lrc", "k5.lrc"), left)
        assertTrue(files.first().exists().not())
    }

    /** 上限 0 = 不缓存（0.2.25 设置里的「关闭」）：连写都不写。 */
    @Test
    fun `zero limit writes nothing`() {
        val key = LyricMatch.cacheKey("稻香", "周杰伦")
        LyricStore.saveAt(dir, key, "[00:01.000]第一句\n", max = 0)
        assertNull(LyricStore.loadAt(dir, key))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    /** 自定义上限：写第 4 首时，最旧的那首被淘汰。 */
    @Test
    fun `saveAt honours custom limit`() {
        for (i in 1..4) {
            LyricStore.saveAt(dir, "k$i", "[00:01.000]第 $i 句\n", max = 3)
            // 串行写入可能落在同一毫秒，显式拉开时间戳才能验证「淘汰最旧」。
            File(dir, "k$i.lrc").setLastModified(1_000L * i)
        }
        val left = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("k2.lrc", "k3.lrc", "k4.lrc"), left)
    }

    @Test
    fun `stats counts files and bytes`() {
        LyricStore.saveAt(dir, "a", "12345", max = 10)
        LyricStore.saveAt(dir, "b", "123", max = 10)
        val (count, bytes) = LyricStore.statsAt(dir)
        assertEquals(2, count)
        assertEquals(8L, bytes)
    }

    // ------------------------------------------------------ 来源标记（0.2.27）

    @Test
    fun `source tag survives write and read`() {
        val key = LyricMatch.cacheKey("稻香", "周杰伦")
        LyricStore.saveAt(dir, key, LyricStore.tagged("[00:01.000]第一句", "lrclib"))
        assertEquals("lrclib", LyricStore.sourceAt(dir, key))
        // 正文必须能原样取回：标签行只是文件头，不该混进歌词里。
        assertEquals(
            "[00:01.000]第一句",
            LyricStore.loadAt(dir, key)?.let { LyricStore.stripTag(it) }
        )
    }

    @Test
    fun `untagged cache reports no source`() {
        val key = LyricMatch.cacheKey("稻香", "周杰伦")
        LyricStore.saveAt(dir, key, "[00:01.000]第一句")
        assertNull(LyricStore.sourceAt(dir, key))
        assertEquals("[00:01.000]第一句", LyricStore.stripTag(LyricStore.loadAt(dir, key)!!))
    }

    @Test
    fun `stripTag leaves ordinary text untouched`() {
        assertEquals("abc", LyricStore.stripTag("abc"))
        // 标记行之后什么都没有时，剥完就是空串（别留下一行空白当歌词）。
        assertEquals("", LyricStore.stripTag(LyricStore.tagged("", "kugou")))
    }
}
