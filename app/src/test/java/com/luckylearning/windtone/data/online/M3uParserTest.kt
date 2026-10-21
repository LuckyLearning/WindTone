package com.luckylearning.windtone.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3U / HLS / PLS 解析（v1.1.37）。
 *
 * 这一层的错误**不会报错，只会表现为「导入少了一半」「标题全是域名」**，所以每条规则
 * 都单独立一个用例钉住。用例里的样本取自真实清单的形态（含 tvg-* 属性、相对路径、GBK 时代的写法）。
 */
class M3uParserTest {

    @Test
    fun `基本 EXTINF 解析出标题歌手与时长`() {
        val text = """
            #EXTM3U
            #EXTINF:215,周杰伦 - 晴天
            https://cdn.example.com/a/1.mp3
            #EXTINF:180,夜航
            https://cdn.example.com/a/2.mp3
        """.trimIndent()
        val r = M3uParser.parse(text, "https://cdn.example.com/list.m3u")
        assertEquals(PlaylistKind.TRACKS, r.kind)
        assertEquals(2, r.entries.size)
        assertEquals("晴天", r.entries[0].title)
        assertEquals("周杰伦", r.entries[0].artist)
        assertEquals(215_000L, r.entries[0].durationMs)
        // 「夜航」没有 " - "，不该被硬拆成歌手 + 标题
        assertEquals("夜航", r.entries[1].title)
        assertNull(r.entries[1].artist)
    }

    @Test
    fun `直播时长 -1 记为 0 而不是负数`() {
        val text = "#EXTM3U\n#EXTINF:-1,Radio\nhttps://s.example.com/live"
        val r = M3uParser.parse(text, "https://s.example.com/list.m3u")
        assertEquals(0L, r.entries.single().durationMs)
    }

    @Test
    fun `小数时长按毫秒取整`() {
        val text = "#EXTM3U\n#EXTINF:123.5,T\nhttps://s.example.com/a.mp3"
        assertEquals(123_500L, M3uParser.parse(text, "https://s.example.com/l.m3u").entries.single().durationMs)
    }

    @Test
    fun `相对路径按清单地址解析`() {
        val base = "https://host.example.com/music/list.m3u"
        assertEquals(
            "https://host.example.com/music/a.mp3",
            M3uParser.resolveUrl(base, "a.mp3"),
        )
        assertEquals(
            "https://host.example.com/other/a.mp3",
            M3uParser.resolveUrl(base, "/other/a.mp3"),
        )
        assertEquals(
            "https://cdn2.example.com/a.mp3",
            M3uParser.resolveUrl(base, "//cdn2.example.com/a.mp3"),
        )
    }

    @Test
    fun `非 http 地址计入 skipped 而不是混进队列`() {
        val text = """
            #EXTM3U
            file:///sdcard/a.mp3
            https://ok.example.com/a.mp3
            icy://radio.example.com:8000/live
        """.trimIndent()
        val r = M3uParser.parse(text, "https://ok.example.com/l.m3u")
        assertEquals(1, r.entries.size)
        assertEquals(2, r.skipped)
        assertTrue("提示里要说清丢了几条：${r.note}", r.note.contains("2 条"))
    }

    @Test
    fun `没有条目被丢掉时不写备注`() {
        val text = "#EXTM3U\n#EXTINF:10,A - B\nhttps://x.example.com/a.mp3"
        // 备注只解释差异；界面自己会显示条数，重复写就成了「2 条 · 2 条」
        assertEquals("", M3uParser.parse(text, "https://x.example.com/l.m3u").note)
    }

    @Test
    fun `EXTINF 里只有 tvg-name 时用它当标题`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"x\" tvg-name=\"Jazz FM\" group-title=\"Jazz\",\nhttps://s.example.com/jazz"
        val r = M3uParser.parse(text, "https://s.example.com/l.m3u")
        assertEquals("Jazz FM", r.entries.single().title)
    }

    @Test
    fun `HLS 清单整体当一条流，不逐条拆`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=256000
            high/index.m3u8
        """.trimIndent()
        val r = M3uParser.parse(text, "https://live.example.com/radio/index.m3u8")
        assertEquals(PlaylistKind.HLS, r.kind)
        assertEquals(1, r.entries.size)
        // 播放的是清单本身的地址：交给 ExoPlayer 的 HLS 解析器取子清单
        assertEquals("https://live.example.com/radio/index.m3u8", r.entries.single().url)
    }

    @Test
    fun `HLS 带 EXT-X-MEDIA 时用 NAME 作显示名`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="FIP"
            #EXT-X-STREAM-INF:BANDWIDTH=128000,AUDIO="a"
            https://live.example.com/a.m3u8
        """.trimIndent()
        val r = M3uParser.parse(text, "https://live.example.com/a.m3u8")
        assertEquals("FIP", r.entries.single().title)
    }

    @Test
    fun `PLS 按序号把 File 与 Title 绑在一起`() {
        val text = """
            [playlist]
            NumberOfEntries=2
            File1=https://s.example.com/one.mp3
            Title1=Artist A - Song A
            Length1=200
            File2=/two.mp3
            Title2=Song B
            Version=2
        """.trimIndent()
        val r = M3uParser.parse(text, "https://s.example.com/dir/list.pls")
        assertEquals(PlaylistFormat.PLS, r.format)
        assertEquals(2, r.entries.size)
        assertEquals("Song A", r.entries[0].title)
        assertEquals("Artist A", r.entries[0].artist)
        assertEquals(200_000L, r.entries[0].durationMs)
        assertEquals("https://s.example.com/two.mp3", r.entries[1].url)
    }

    @Test
    fun `没有 EXTINF 的纯地址列表也能用`() {
        val text = "https://a.example.com/x/one.mp3\nhttps://a.example.com/x/two.mp3\n"
        val r = M3uParser.parse(text, "https://a.example.com/x/list.txt")
        assertEquals(2, r.entries.size)
        assertEquals("one", r.entries[0].title)
        assertEquals("two", r.entries[1].title)
    }

    @Test
    fun `标题兜底会把 URL 编码的末段解码`() {
        val text = "https://a.example.com/x/%E5%A4%9C%E8%88%AA.flac"
        val r = M3uParser.parse(text, "https://a.example.com/x/list.m3u")
        assertEquals("夜航", r.entries.single().title)
    }

    @Test
    fun `网页内容判为不可用并给出人话原因`() {
        val text = "<!DOCTYPE html><html><body>请登录</body></html>"
        val r = M3uParser.parse(text, "https://example.com/page")
        assertEquals(PlaylistKind.UNSUPPORTED, r.kind)
        assertTrue(r.note.isNotBlank())
    }

    @Test
    fun `空内容判为不可用`() {
        assertEquals(PlaylistKind.UNSUPPORTED, M3uParser.parse("   ", "https://x.example.com/a.m3u").kind)
    }

    @Test
    fun `BOM 与 CRLF 不影响解析`() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:10,A - B\r\nhttps://x.example.com/a.mp3\r\n"
        val r = M3uParser.parse(text, "https://x.example.com/a.m3u")
        assertEquals(1, r.entries.size)
        assertEquals("B", r.entries.single().title)
    }

    @Test
    fun `含协议的标题不做歌手拆分`() {
        val (title, artist) = M3uParser.splitArtistTitle("https://example.com/a - b")
        assertEquals("https://example.com/a - b", title)
        assertNull(artist)
    }

    @Test
    fun `originOf 去掉路径与查询`() {
        assertEquals(
            "https://host.example.com:8080",
            M3uParser.originOf("https://host.example.com:8080/a/b?x=1"),
        )
        assertNull(M3uParser.originOf("not-a-url"))
    }
}
