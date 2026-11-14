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
}
