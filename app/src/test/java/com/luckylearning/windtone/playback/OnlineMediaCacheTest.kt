package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线缓存的路由规则（v1.1.38）。
 *
 * 这条判断看着只有一行，却决定「本地播放会不会被缓存层搞坏」：
 * 如果误把 `content://` 也丢给 CacheDataSource，本地歌可能出现随机读失败、
 * 甚至整首播不动 —— 而在线缓存本身还「工作正常」，排查时极易走错方向。
 */
class OnlineMediaCacheTest {

    @Test
    fun `http 与 https 都走缓存`() {
        assertTrue(OnlineMediaCache.shouldCache("http"))
        assertTrue(OnlineMediaCache.shouldCache("https"))
    }

    @Test
    fun `scheme 大小写不敏感`() {
        // Uri.scheme 通常是规范化的小写，但调用方可能传 http 头里的原值
        assertTrue(OnlineMediaCache.shouldCache("HTTPS"))
        assertTrue(OnlineMediaCache.shouldCache("Http"))
    }

    @Test
    fun `本地与内容源不进缓存`() {
        assertFalse(OnlineMediaCache.shouldCache("file"))
        assertFalse(OnlineMediaCache.shouldCache("content"))
        assertFalse(OnlineMediaCache.shouldCache("asset"))
        assertFalse(OnlineMediaCache.shouldCache(null as String?))
        assertFalse(OnlineMediaCache.shouldCache(""))
    }

    @Test
    fun `缓存上限设置在合理区间`() {
        // 太小起不到作用，太大在车机上会挤占系统空间（cacheDir 本身会被系统回收）
        assertEquals(128L * 1024 * 1024, OnlineMediaCache.MAX_BYTES)
        assertTrue(OnlineMediaCache.MAX_BYTES >= 64L * 1024 * 1024)
        assertTrue(OnlineMediaCache.MAX_BYTES <= 2L * 1024 * 1024 * 1024)
        assertEquals(0L, OnlineMediaCache.MAX_BYTES % (1024 * 1024))
        assertEquals(
            listOf(128, 256, 512, 1024, 2048).map { it * 1024L * 1024L },
            OnlineMediaCache.LIMIT_STEPS,
        )
        assertTrue(OnlineMediaCache.LIMIT_STEPS.contains(OnlineMediaCache.MAX_BYTES))
    }

    @Test
    fun `可播前缀阈值至少 256KB 且不超过 8MB`() {
        // 太小起不到「避开并行分片」的作用；太大等于几乎要求整首下完才让路
        assertTrue(OnlineMediaCache.DEFAULT_CACHED_PREFIX_BYTES >= 256L * 1024)
        assertTrue(OnlineMediaCache.DEFAULT_CACHED_PREFIX_BYTES <= 8L * 1024 * 1024)
    }
}