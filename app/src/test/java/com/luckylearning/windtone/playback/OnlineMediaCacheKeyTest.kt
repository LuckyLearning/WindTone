package com.luckylearning.windtone.playback

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 网盘直链的缓存键（v1.1.43）。
 *
 * 这里钉住的是「同一首歌换个签名还能命中缓存」—— 这是弱网下重听不再卡的关键。
 * 反过来也要钉住「普通在线地址不受影响」，否则会把在线歌单的缓存行为一起改坏。
 */
@RunWith(RobolectricTestRunner::class)
class OnlineMediaCacheKeyTest {

    @Test
    fun `同一 fsId 的不同签名得到同一个缓存键`() {
        val first = Uri.parse("https://d.pcs.baidu.com/file/abc?fid=1&sign=AAA#wt=414244021542671")
        val second = Uri.parse("https://d.pcs.baidu.com/file/abc?fid=1&sign=BBB#wt=414244021542671")
        assertEquals("pan:414244021542671", OnlineMediaCache.panCacheKey(first))
        assertEquals(OnlineMediaCache.panCacheKey(first), OnlineMediaCache.panCacheKey(second))
    }

    @Test
    fun `fragment 里混进 token 也只取 fsId`() {
        // v1.1.43 曾把 access_token 拼进 fragment，键变成 `pan:123&access_token=...`：
        // 换 token 就换键（缓存全废），凭据还会进缓存索引。这里钉住「只认 & 之前的部分」。
        val dirty = Uri.parse("https://d.pcs.baidu.com/file/abc?fid=1#wt=414244021542671&access_token=abc")
        assertEquals("pan:414244021542671", OnlineMediaCache.panCacheKey(dirty))
    }

    @Test
    fun `没有 fsId fragment 的地址不走网盘键`() {
        assertNull(OnlineMediaCache.panCacheKey(Uri.parse("https://cdn.example.com/a.mp3")))
        assertNull(OnlineMediaCache.panCacheKey(Uri.parse("https://d.pcs.baidu.com/file/x#wt=")))
        assertNull(OnlineMediaCache.panCacheKey(Uri.parse("https://d.pcs.baidu.com/file/x#other=1")))
        assertNull(OnlineMediaCache.panCacheKey(null))
    }
}
