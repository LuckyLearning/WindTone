package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音频参数缓存（v1.2.57）的行为锁。
 *
 * 两条容易悄悄改坏的语义：
 *  · **null 也是结论**：解析失败 / 远程不可读必须记住，否则每次翻回信息页都会把
 *    那次注定失败的解析（开 MediaExtractor + 解 3 秒 PCM）再跑一遍；
 *  · **LRU 淘汰**：容量按条数算，翻歌超过容量后最久未用的先走，不能让缓存无限长。
 */
class AudioInfoCacheTest {

    private fun info(codec: String) = AudioInspector.Info(
        extension = "flac",
        container = "FLAC",
        codec = codec,
        mime = "audio/flac",
        bitrateKbps = 900,
        bitrateEstimated = false,
        sampleRateHz = 44100,
        channels = 2,
        bitsPerSample = 16,
        durationMs = 1000L,
        sizeBytes = 1000L,
    )

    @Test
    fun `没查过返回 null`() {
        assertNull(AudioInfoCache().get("a"))
    }

    @Test
    fun `存过就能取回同一个结果`() {
        val cache = AudioInfoCache()
        cache.put("a", info("FLAC"))
        assertEquals("FLAC", cache.get("a")?.info?.codec)
    }

    @Test
    fun `解析失败也记住 —— 不重复跑注定失败的解析`() {
        val cache = AudioInfoCache()
        cache.put("a", null)
        val hit = cache.get("a")
        assertNotNull("必须能区分「没查过」与「查过但没有结果」", hit)
        assertNull(hit?.info)
    }

    @Test
    fun `超过容量淘汰最久未用的`() {
        val cache = AudioInfoCache(capacity = 2)
        cache.put("a", info("A"))
        cache.put("b", info("B"))
        cache.get("a") // a 变成最近使用
        cache.put("c", info("C"))
        assertNull("b 是最久未用的，应被淘汰", cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `顺序按最近使用排列`() {
        val cache = AudioInfoCache(capacity = 3)
        cache.put("a", null)
        cache.put("b", null)
        cache.get("a")
        assertEquals(listOf("b", "a"), cache.keys())
    }

    @Test
    fun `默认容量按条数算 —— 与图片缓存不同`() {
        assertEquals(8, AudioInfoCache.DEFAULT_CAPACITY)
    }

    @Test
    fun `clear 清空`() {
        val cache = AudioInfoCache()
        cache.put("a", info("A"))
        cache.clear()
        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
    }
}
