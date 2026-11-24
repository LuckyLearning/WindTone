package com.luckylearning.windtone.data.online

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 在线曲目身份编码的回归。
 *
 * 这些断言看着琐碎，但每一条都对应一次真实风险：身份算错 → 收藏脱钩；
 * 占位地址拼错 → 播放器拿到无法打开的 scheme；前缀判断漏掉 → 「unknown protocol」。
 */
@RunWith(RobolectricTestRunner::class)
class OnlineKeysTest {

    @Test
    fun `音源与源内 id 往返一致`() {
        val key = OnlineKeys.of("kw", "228908")
        assertEquals("lx:kw:228908", key)
        assertEquals(OnlineKeys.Key("kw", "228908"), OnlineKeys.parse(key))
        assertTrue(OnlineKeys.isOnlineKey(key))
    }

    @Test
    fun `源内 id 可以含冒号`() {
        val key = OnlineKeys.of("custom", "album:track:1")
        assertEquals(OnlineKeys.Key("custom", "album:track:1"), OnlineKeys.parse(key))
    }

    @Test
    fun `非在线键一律不认`() {
        assertNull(OnlineKeys.parse(null))
        assertNull(OnlineKeys.parse(""))
        assertNull(OnlineKeys.parse("aliyun:ay=drive/file"))
        assertNull(OnlineKeys.parse("content://media/external/audio/media/1"))
        assertNull(OnlineKeys.parse("https://cdn.example.com/a.mp3"))
        assertFalse(OnlineKeys.isOnlineKey("aliyun:ay=1"))
    }

    @Test
    fun `残缺的在线键不认`() {
        assertNull(OnlineKeys.parse("lx:"))
        assertNull(OnlineKeys.parse("lx:kw"))
        assertNull(OnlineKeys.parse("lx:kw:"))
        assertNull(OnlineKeys.parse("lx::228908"))
    }

    @Test
    fun `音源 id 不允许含冒号或空白`() {
        assertTrue(OnlineKeys.isValidSourceId("kw"))
        assertTrue(OnlineKeys.isValidSourceId("custom-6f1b"))
        assertFalse("含冒号会让稳定键无法无歧义解析", OnlineKeys.isValidSourceId("a:b"))
        assertFalse(OnlineKeys.isValidSourceId("a b"))
        assertFalse(OnlineKeys.isValidSourceId(""))
        assertFalse(OnlineKeys.isValidSourceId(null))
    }

    @Test
    fun `占位地址带得回身份`() {
        val key = OnlineKeys.of("kw", "228908")
        val uri = OnlineKeys.pendingUri(key)
        assertEquals(OnlineKeys.PENDING_SCHEME, uri.scheme)
        assertEquals(key, uri.fragment)
        assertTrue(OnlineKeys.isPending(uri.scheme))
        assertEquals(key, OnlineKeys.pendingKey(uri))
    }

    @Test
    fun `真直链不是占位地址`() {
        val uri = Uri.parse("https://cdn.example.com/a.mp3")
        assertFalse(OnlineKeys.isPending(uri.scheme))
        assertNull(OnlineKeys.pendingKey(uri))
    }

    /**
     * 记录这条**必须优先判断**的原因，而不是靠注释自觉：
     * `lx:kw:228908` 在 Uri 眼里是一个 scheme 为 `lx` 的地址，
     * 直接塞给播放器就是 `MalformedURLException: unknown protocol: lx`。
     */
    @Test
    fun `稳定键长得像一个 uri 所以必须先于 looksLikeUri 判断`() {
        assertEquals("lx", Uri.parse(OnlineKeys.of("kw", "1")).scheme)
    }
}
