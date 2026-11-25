package com.luckylearning.windtone.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面「有时有、有时没有」对得上的几条地址规则。
 *
 * 不发网络：真正的下载在 [OnlineCover]，这里钉死「什么样的字符串根本不是图片」。
 */
class OnlineCoverUrlsTest {

    @Test
    fun kuwoPicWebIsNotAnImageUrl() {
        val resolver = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=228908"
        assertTrue(OnlineCoverUrls.isKuwoResolver(resolver))
        assertNull(OnlineCoverUrls.normalizeImage(resolver))
        assertNull(OnlineCoverUrls.directImage(resolver, null))
    }

    @Test
    fun rawImageIsUsedWhenCoverUrlIsOnlyTheResolver() {
        val image = "http://img1.kuwo.cn/star/albumcover/500/a.jpg"
        val got = OnlineCoverUrls.directImage(
            "http://artistpicserver.kuwo.cn/pic.web?rid=1",
            image,
        )
        // img*.kuwo.cn 的 http 经常超时，下载前升到 https
        assertEquals("https://img1.kuwo.cn/star/albumcover/500/a.jpg", got)
    }

    @Test
    fun kugouSizeTemplateAndCleartextBecomeHttps() {
        val raw = "http://imge.kugou.com/stdmusic/{size}/20250125/abc.jpg"
        assertEquals(
            "https://imge.kugou.com/stdmusic/480/20250125/abc.jpg",
            OnlineCoverUrls.normalizeImage(raw),
        )
    }

    @Test
    fun miguQqAndKuwoImageHostsUseTheSchemeThatActuallyReturnsBytes() {
        assertEquals(
            "https://d.musicapp.migu.cn/a.jpg",
            OnlineCoverUrls.normalizeImage("http://d.musicapp.migu.cn/a.jpg"),
        )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000abc.jpg",
            OnlineCoverUrls.normalizeImage("http://y.gtimg.cn/music/photo_new/T002R500x500M000abc.jpg"),
        )
        // 2026-11-25：img1.kuwo.cn 的 https 是 JPEG；http 不稳定
        assertEquals(
            "https://img2.kuwo.cn/star/albumcover/500/b.jpg",
            OnlineCoverUrls.normalizeImage("http://img2.kuwo.cn/star/albumcover/500/b.jpg"),
        )
        assertEquals(
            "https://img1.kuwo.cn/star/userpl2015/a.jpg",
            OnlineCoverUrls.normalizeImage("https://img1.kuwo.cn/star/userpl2015/a.jpg"),
        )
    }

    @Test
    fun kwcdnPlaylistCoversStayOnHttpBecauseHttpsCertificateDoesNotMatch() {
        // 歌单列表里大约三分之一是 img1.kwcdn.kuwo.cn。
        // https 报 Hostname mismatch，http 返回 JPEG。解析阶段曾经把 http 一律改成 https。
        val http = "http://img1.kwcdn.kuwo.cn/star/userpl2015/83/89/a.jpg"
        val https = "https://img1.kwcdn.kuwo.cn/star/userpl2015/83/89/a.jpg"
        assertEquals(http, OnlineCoverUrls.normalizeImage(http))
        assertEquals(http, OnlineCoverUrls.normalizeImage(https))
        assertEquals(
            "http://www.kuwo.cn/",
            OnlineCoverUrls.refererFor(https),
        )
    }

    @Test
    fun refererFollowsTheImageHost() {
        assertEquals("https://y.qq.com/", OnlineCoverUrls.refererFor("https://y.gtimg.cn/a.jpg"))
        assertEquals("https://www.kugou.com/", OnlineCoverUrls.refererFor("https://imge.kugou.com/a.jpg"))
        assertEquals("https://music.migu.cn/", OnlineCoverUrls.refererFor("https://d.musicapp.migu.cn/a.jpg"))
        assertEquals("http://www.kuwo.cn/", OnlineCoverUrls.refererFor("http://img2.kuwo.cn/a.jpg"))
        assertNull(OnlineCoverUrls.refererFor("https://example.com/a.jpg"))
    }

    @Test
    fun textBodyThatIsASingleUrlIsFollowedAndImageMagicIsNot() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)
        assertTrue(OnlineCoverUrls.looksLikeImage(jpeg))
        assertNull(OnlineCoverUrls.textUrl(jpeg))

        val pointer = "http://img2.kuwo.cn/star/albumcover/500/c.jpg".toByteArray(Charsets.ISO_8859_1)
        assertFalse(OnlineCoverUrls.looksLikeImage(pointer))
        assertEquals("https://img2.kuwo.cn/star/albumcover/500/c.jpg", OnlineCoverUrls.textUrl(pointer))

        val html = "<html>denied</html>".toByteArray()
        assertNull(OnlineCoverUrls.textUrl(html))
    }

    @Test
    fun kuwoRidIsExtractedForSheetCovers() {
        // 歌单封面只给 pic.web 解析端点，没有 OnlineTrack.songId 可借，必须能从 URL 自取 rid
        assertEquals(
            "228908",
            OnlineCoverUrls.kuwoRid(
                "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=228908",
            ),
        )
        assertEquals(
            "42",
            OnlineCoverUrls.kuwoRid("https://artistpicserver.kuwo.cn/pic.web?rid=42&x=1"),
        )
        assertNull(OnlineCoverUrls.kuwoRid("https://img1.kuwo.cn/a.jpg"))
        assertNull(OnlineCoverUrls.kuwoRid("https://artistpicserver.kuwo.cn/pic.web?rid="))
        assertNull(OnlineCoverUrls.kuwoRid("https://artistpicserver.kuwo.cn/pic.web?rid=abc"))
        assertNull(OnlineCoverUrls.kuwoRid(null))
    }

    @Test
    fun webpMagicCountsAsAnImage() {
        val bytes = ByteArray(16)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        assertTrue(OnlineCoverUrls.looksLikeImage(bytes))
    }
}
