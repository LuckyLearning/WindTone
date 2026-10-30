package com.luckylearning.windtone.data.online

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线播放列表的纯规则（2026-10-21 随 `OnlinePlaylists` 拆出而补）。
 *
 * 这一层的故障**不抛异常、不红屏**，只表现为「粘贴的地址导不进来」「中文电台标题一片问号」
 * 「4MB 上限没生效把车机拖住」「UnknownHostException 直接甩到用户脸上」——
 * 全是用户看得见但说不清原因的，所以每条规则单独立一个用例钉住。
 *
 * 全部是纯函数，不需要 Robolectric。
 */
class OnlinePlaylistRulesTest {

    // ------------------------------------------------------------ normalizeUrl

    @Test
    fun `空地址拒绝且说清原因`() {
        assertEquals("地址不能为空", failureOf { OnlinePlaylistRules.normalizeUrl("") })
        assertEquals("地址不能为空", failureOf { OnlinePlaylistRules.normalizeUrl("   ") })
    }

    @Test
    fun `丢掉协议头时补 https 而不是拒绝`() {
        // 用户从浏览器复制常只有域名；直接拒绝的话他只会看到「地址格式不对」，不知道怎么修
        assertEquals(
            "https://example.com/list.m3u",
            OnlinePlaylistRules.normalizeUrl("example.com/list.m3u"),
        )
        // 不擅自改写大小写：域名大小写不敏感，但路径敏感
        assertEquals(
            "https://Example.com/a/List.m3u",
            OnlinePlaylistRules.normalizeUrl("Example.com/a/List.m3u"),
        )
    }

    @Test
    fun `已有协议头原样保留`() {
        assertEquals(
            "http://a.example.com/x",
            OnlinePlaylistRules.normalizeUrl("http://a.example.com/x"),
        )
        assertEquals(
            "https://a.example.com/x",
            OnlinePlaylistRules.normalizeUrl("https://a.example.com/x"),
        )
        assertEquals(
            "HTTPS://A.example.com/x",
            OnlinePlaylistRules.normalizeUrl("HTTPS://A.example.com/x"),
        )
    }

    @Test
    fun `只有协议头没有域名要拒绝`() {
        // URL("https://") 能构造出来但 host 是空串，放过去会连一个没有主机的地址
        assertEquals("地址里缺少域名", failureOf { OnlinePlaylistRules.normalizeUrl("https://") })
    }

    @Test
    fun `畸形地址报格式错误`() {
        // 未闭合的 IPv6 字面量 / 主机里有空格：显式校验拦下（JDK 17 与 21 对 URL() 的
        // 抛错行为不同，v1.1.37 CI 实锤 —— 校验不能依赖 JDK 版本行为）
        assertEquals("地址格式不对", failureOf { OnlinePlaylistRules.normalizeUrl("https://[invalid") })
        assertEquals("地址格式不对", failureOf { OnlinePlaylistRules.normalizeUrl("https://a b.com/x") })
        // 域名缺失：与上面同一条显式校验路径，任何 JDK 行为一致
        assertEquals("地址里缺少域名", failureOf { OnlinePlaylistRules.normalizeUrl("https://:8080/x") })
        assertEquals("地址里缺少域名", failureOf { OnlinePlaylistRules.normalizeUrl("https:///x") })
        // 合法 IPv6 带端口不受影响
        assertEquals(
            "https://[2001:db8::1]:8080/x",
            OnlinePlaylistRules.normalizeUrl("https://[2001:db8::1]:8080/x"),
        )
    }

    // ------------------------------------------------------------ defaultName

    @Test
    fun `通用文件名退化成域名`() {
        // 大量清单就叫 list.m3u / index.m3u，直接用文件名会让列表里出现好几个「list」
        assertEquals(
            "cdn.example.com",
            OnlinePlaylistRules.defaultName("https://cdn.example.com/list.m3u"),
        )
        assertEquals(
            "cdn.example.com",
            OnlinePlaylistRules.defaultName("https://cdn.example.com/index.m3u"),
        )
        assertEquals(
            "cdn.example.com",
            OnlinePlaylistRules.defaultName("https://cdn.example.com/playlist.m3u"),
        )
        assertEquals(
            "cdn.example.com",
            OnlinePlaylistRules.defaultName("https://cdn.example.com/default.m3u8"),
        )
    }

    @Test
    fun `有信息量的文件名照用`() {
        assertEquals(
            "my-mix",
            OnlinePlaylistRules.defaultName("https://cdn.example.com/my-mix.m3u"),
        )
        assertEquals(
            "夜航歌单",
            OnlinePlaylistRules.defaultName("https://cdn.example.com/%E5%A4%9C%E8%88%AA%E6%AD%8C%E5%8D%95.m3u"),
        )
    }

    // ------------------------------------------------------------ mimeOf

    @Test
    fun `扩展名映射到 MIME`() {
        assertEquals("audio/mpeg", OnlinePlaylistRules.mimeOf("https://x/a.mp3"))
        assertEquals("audio/mp4", OnlinePlaylistRules.mimeOf("https://x/a.m4a"))
        assertEquals("audio/mp4", OnlinePlaylistRules.mimeOf("https://x/a.aac"))
        assertEquals("audio/mp4", OnlinePlaylistRules.mimeOf("https://x/a.mp4"))
        assertEquals("audio/ogg", OnlinePlaylistRules.mimeOf("https://x/a.ogg"))
        assertEquals("audio/ogg", OnlinePlaylistRules.mimeOf("https://x/a.oga"))
        assertEquals("audio/ogg", OnlinePlaylistRules.mimeOf("https://x/a.opus"))
        assertEquals("audio/flac", OnlinePlaylistRules.mimeOf("https://x/a.flac"))
        assertEquals("audio/wav", OnlinePlaylistRules.mimeOf("https://x/a.wav"))
        assertEquals("application/x-mpegURL", OnlinePlaylistRules.mimeOf("https://x/live.m3u8"))
    }

    @Test
    fun `查询串与大小写不影响扩展名判定`() {
        assertEquals("audio/mpeg", OnlinePlaylistRules.mimeOf("https://x/a.MP3?token=abc&x=1"))
        assertEquals("audio/flac", OnlinePlaylistRules.mimeOf("https://x/a.FLAC#t=10"))
    }

    @Test
    fun `未知扩展名返回 null 而不是瞎猜`() {
        // 播不动的格式交给播放器报错，比这里猜一个更诚实
        assertNull(OnlinePlaylistRules.mimeOf("https://x/live"))
        assertNull(OnlinePlaylistRules.mimeOf("https://x/a.xyz"))
        assertNull(OnlinePlaylistRules.mimeOf("https://x/"))
    }

    // ------------------------------------------------------------ looksLikeHtml

    @Test
    fun `网页内容要认出来`() {
        assertTrue(OnlinePlaylistRules.looksLikeHtml("<!DOCTYPE html>\n<html>"))
        assertTrue(OnlinePlaylistRules.looksLikeHtml("<html lang=\"zh\">"))
        assertTrue(OnlinePlaylistRules.looksLikeHtml("   \n<body>请登录</body>"))
    }

    @Test
    fun `正常清单不误判成网页`() {
        // 清单里出现 <body> 不要紧，有 #EXTM3U 就不是网页
        assertFalse(OnlinePlaylistRules.looksLikeHtml("#EXTM3U\n#EXTINF:1,A\nhttps://x/a.mp3"))
        assertFalse(OnlinePlaylistRules.looksLikeHtml("<body>#EXTM3U\nhttps://x/a.mp3"))
        assertFalse(OnlinePlaylistRules.looksLikeHtml("https://x/a.mp3\nhttps://x/b.mp3"))
    }

    // ------------------------------------------------------------ readCapped

    @Test
    fun `小响应体原样读全`() {
        val body = "#EXTM3U\nhttps://x/a.mp3\n".toByteArray()
        assertEquals(
            body.size,
            OnlinePlaylistRules.readCapped(ByteArrayInputStream(body)).size,
        )
    }

    @Test
    fun `超过 4MB 截断而不是照单全收`() {
        // 防「把整个 IPTV 频道表当音乐列表填进来」：不设限的话解析和落盘都会把车机拖住。
        // 不断言精确等于上限，只要求「贴着上限停」——换缓冲区大小这条用例依然成立。
        val huge = ByteArray(OnlinePlaylistRules.MAX_BYTES + 12345) { 'a'.code.toByte() }
        val out = OnlinePlaylistRules.readCapped(ByteArrayInputStream(huge))
        assertTrue("读过头了：${out.size}", out.size <= OnlinePlaylistRules.MAX_BYTES)
        assertTrue(
            "没读到上限附近，等于没截断：${out.size}",
            out.size > OnlinePlaylistRules.MAX_BYTES - 64 * 1024,
        )
    }

    // ------------------------------------------------------------ decode

    @Test
    fun `UTF-8 内容正常解`() {
        val text = "夜航 - 周杰伦"
        assertEquals(
            text,
            OnlinePlaylistRules.decode(text.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `GB18030 内容回落后能解出中文`() {
        // 中文电台列表里有大量 GBK 文件；按 UTF-8 硬解会得到一片问号，
        // 而标题乱码比「没导入」更难排查（用户只会说「词都不认识」）。
        val gb = "夜航".toByteArray(Charset.forName("GB18030"))
        // 先自证样本真的触发了回落分支，否则这条用例是空的
        assertTrue("样本竟是合法 UTF-8，测不到 GB18030 分支", !strictUtf8Decodes(gb))
        assertEquals("夜航", OnlinePlaylistRules.decode(gb))
    }

    // ------------------------------------------------------------ friendly

    @Test
    fun `网络异常转成用户能懂的话`() {
        // 直接把异常 message 抛给界面会出现「connection closed」这种用户读不懂的英文
        // A4：原因 + 下一步
        fun assertA4(msg: String, vararg needles: String) {
            for (n in needles) assertTrue(msg.contains(n))
            assertTrue(msg.contains("下一步"))
        }
        assertA4(
            OnlinePlaylistRules.friendly(UnknownHostException("x")),
            "解析不到这个域名",
        )
        assertA4(
            OnlinePlaylistRules.friendly(SocketTimeoutException()),
            "连接超时",
        )
        assertA4(
            OnlinePlaylistRules.friendly(SSLException("boom")),
            "HTTPS 握手失败",
        )
        assertA4(
            OnlinePlaylistRules.friendly(IOException("connection closed")),
            "连不上这个地址",
            "connection closed",
        )
        // message 为 null 时退化成类名，不能把 "null" 显示给用户
        assertA4(
            OnlinePlaylistRules.friendly(IOException()),
            "连不上这个地址",
            "IOException",
        )
    }

    // ------------------------------------------------------------ 辅助

    private fun failureOf(block: () -> Unit): String =
        runCatching { block() }.exceptionOrNull()?.message
            ?: error("这里本该抛异常（调用方靠它把原因显示给用户）")

    private fun strictUtf8Decodes(bytes: ByteArray): Boolean = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.isSuccess
}
