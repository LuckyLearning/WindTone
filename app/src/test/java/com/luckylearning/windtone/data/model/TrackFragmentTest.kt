package com.luckylearning.windtone.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackFragmentTest {

    @Test
    fun `同一盘和文件编出稳定键，直链不参与`() {
        val fragment = TrackFragment.of("drive/1", "file:a")
        val parsed = TrackFragment.parse(fragment)
        assertEquals("drive/1" to "file:a", parsed)
        assertEquals("aliyun:$fragment", TrackFragment.cacheKey(fragment))
    }

    @Test
    fun `不是阿里云盘的 fragment 不算身份`() {
        assertNull(TrackFragment.parse("wt=1"))
        assertNull(TrackFragment.parse(null))
        assertNull(TrackFragment.cacheKey("wt=1"))
    }

    @Test
    fun `mediaId 稳定键能被识别并还原身份`() {
        // v1.1.71 的播放全挂：稳定键 "aliyun:ay=…" 长得像 URI，
        // 被浏览树 resolve 误当成地址写回播放器（unknown protocol: aliyun）
        val fragment = TrackFragment.of("drive/1", "file:a")
        val mediaId = "aliyun:$fragment"
        org.junit.Assert.assertTrue(TrackFragment.cacheKeyOfMediaId(mediaId))
        assertEquals(
            "drive/1" to "file:a",
            TrackFragment.parse(mediaId.removePrefix("aliyun:")),
        )
        org.junit.Assert.assertFalse(TrackFragment.cacheKeyOfMediaId("file:///a.mp3"))
        org.junit.Assert.assertFalse(TrackFragment.cacheKeyOfMediaId("https://x/a.flac"))
    }
}
