package com.luckylearning.windtone.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 钉死四家歌单接口的**嵌套层级**。
 *
 * 这里每个样本都是从真实接口裁剪出来的结构（2026-11-24 实测），破坏性重构时
 * 只要层级动过就会红 —— 排行榜那边就是靠同样的测试先抓到「少一层 body」的。
 */
@RunWith(RobolectricTestRunner::class)
class OnlineSonglistsTest {

    // ---------------------------------------------------------------- 分类

    @Test
    fun kwTagsUseIdDashDigestAndDropZoneGroups() {
        val json = """
            {"code":200,"data":[
              {"name":"主题","data":[
                {"id":"173","digest":"10000","name":"流行"},
                {"id":"211","digest":"43","name":"经典老歌专区"},
                {"id":"999","digest":"","name":"无digest"}
              ]}
            ]}
        """.trimIndent()
        val groups = OnlineSonglists.parseKwTags(json)
        assertEquals(1, groups.size)
        assertEquals("主题", groups[0].name)
        // digest=43 是「专区」，详情另有接口，收了只会点进去空白
        assertEquals(listOf("173-10000", "999"), groups[0].tags.map { it.id })
    }

    @Test
    fun txTagsSkipAllCategoryZero() {
        val json = """
            {"code":0,"tags":{"data":{"v_group":[
              {"group_name":"热门","v_item":[{"id":0,"name":"全部"},{"id":3317,"name":"官方歌单"}]}
            ]}}}
        """.trimIndent()
        val groups = OnlineSonglists.parseTxTags(json)
        assertEquals(1, groups.size)
        // category_id=0 会让接口返回 subcode 2001（实测），必须过滤
        assertEquals(listOf("3317"), groups[0].tags.map { it.id })
    }

    @Test
    fun kgTagsWalkTagidsGroups() {
        val json = """
            {"status":1,"data":{"tagids":{
              "主题":{"id":1,"data":[{"id":1084,"name":"精选"},{"id":12,"name":"经典"}]},
              "语种":{"id":2,"data":[{"id":20,"name":"英语"}]}
            }}}
        """.trimIndent()
        val groups = OnlineSonglists.parseKgTags(json)
        assertEquals(2, groups.size)
        assertEquals(listOf("1084", "12"), groups[0].tags.map { it.id })
    }

    @Test
    fun mgTagsFlattenSingleGroupFromTexts() {
        val json = """
            {"code":"000000","data":[
              {"content":[{"texts":["流行","1000001672","%E6%B5%81%E8%A1%8C"]}]},
              {"content":[{"texts":["伤感","1000001795","x"]}]}
            ]}
        """.trimIndent()
        val groups = OnlineSonglists.parseMgTags(json)
        assertEquals(1, groups.size)
        assertEquals(listOf("1000001672", "1000001795"), groups[0].tags.map { it.id })
        assertEquals("流行", groups[0].tags[0].name)
    }

    // ---------------------------------------------------------------- 歌单列表

    @Test
    fun kwSheetsOnlyKeepPlatformPlaylists() {
        val json = """
            {"code":200,"data":{"total":158603,"pn":1,"rn":36,"data":[
              {"id":"3741714190","digest":"8","name":"云朵漫过耳畔","uname":"wolf","img":"http://img1.kuwo.cn/x.jpg",
               "listencnt":12000,"total":62,"desc":"轻音乐"},
              {"id":"888","digest":"5","name":"我的收藏夹","uname":"me","img":"","listencnt":1,"total":3}
            ]}}
        """.trimIndent()
        val page = OnlineSonglists.parseKwSheets(json, 1)
        assertEquals(1, page.items.size)
        val sheet = page.items[0]
        assertEquals("3741714190", sheet.id)
        assertEquals("https://img1.kuwo.cn/x.jpg", sheet.cover)
        assertEquals("1.2万", sheet.playCount)
        assertEquals(62, sheet.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun txSheetsReadBasicNode() {
        val json = """
            {"code":0,"playlist":{"code":0,"data":{"content":{"total_cnt":949,"v_item":[
              {"basic":{"tid":9607499637,"title":"相信音乐官方精选","play_cnt":706046,
                "creator":{"nick":"QQ音乐"},"cover":{"medium_url":"https://p.y.qq.com/m.jpg"},
                "desc":"精选<br>歌单"}}
            ]}}}}
        """.trimIndent()
        val page = OnlineSonglists.parseTxSheets(json, 1)
        assertEquals(1, page.items.size)
        val sheet = page.items[0]
        assertEquals("9607499637", sheet.id)
        assertEquals("QQ音乐", sheet.author)
        assertEquals("70.6万", sheet.playCount)
        assertEquals("精选\n歌单", sheet.desc)
        assertTrue(page.hasMore)
    }

    @Test
    fun kgSheetsReplaceSizePlaceholder() {
        val json = """
            {"status":1,"special_db":[
              {"specialid":4088974,"specialname":"瑜伽纯音","nickname":"一鱼","img":"http://x/{size}.jpg",
               "song_count":20,"total_play_count":"1722万","intro":"安静"}
            ]}
        """.trimIndent()
        val page = OnlineSonglists.parseKgSheets(json, 1)
        assertEquals(1, page.items.size)
        assertEquals("https://x/480.jpg", page.items[0].cover)
        assertEquals("1722万", page.items[0].playCount)
    }

    @Test
    fun mgSheetsParseIdFromActionUrl() {
        val json = """
            {"code":"000000","data":{"nextPageUrl":"x","contentItemList":[
              {"template":"group1","itemList":[
                {"title":"治愈 | 生活的答案","actionUrl":"mgmusic://song-list-info?id=211267917&logId=gd",
                 "imageUrl":"https://d.musicapp.migu.cn/a.webp","barList":[{"title":"134.6万"}]}
              ]}
            ]}}
        """.trimIndent()
        val page = OnlineSonglists.parseMgSheets(json, 1)
        assertEquals(1, page.items.size)
        assertEquals("211267917", page.items[0].id)
        assertEquals("134.6万", page.items[0].playCount)
        assertTrue(page.hasMore)
    }

    // ---------------------------------------------------------------- 歌单详情

    @Test
    fun kwDetailMapsBitrateToQuality() {
        val json = """
            {"result":"ok","id":2858093057,"title":"快来听抖音里的儿歌","pic":"http://pic/150.jpg",
             "uname":"小精灵","playnum":3470109,"total":31,"info":"刷抖音",
             "musiclist":[{"id":28513310,"name":"雪宝宝","artist":"早教歌曲","album":"0-1岁",
               "duration":99,"albumid":123,
               "N_MINFO":"level:p,bitrate:320,format:mp3,size:8Mb;level:h,bitrate:128,format:mp3,size:3Mb"}]}
        """.trimIndent()
        val detail = OnlineSonglists.parseKwDetail(json)
        assertEquals("快来听抖音里的儿歌", detail.sheet?.name)
        assertEquals(1, detail.tracks.size)
        val track = detail.tracks[0]
        assertEquals("28513310", track.songId)
        assertEquals(99_000L, track.durationMs)
        // 320 在前：脚本按档位取，顺序即优先级
        assertEquals(listOf("320k", "128k"), track.qualities.map { it.id })
        assertTrue(track.raw.contains("\"songmid\":\"28513310\""))
    }

    @Test
    fun kwDetailFallsBackTo128WhenBitrateUnmapped() {
        val json = """
            {"result":"ok","musiclist":[{"id":1,"name":"x","artist":"y","duration":10,
              "N_MINFO":"level:p,bitrate:192,format:ogg,size:1.4Mb"}]}
        """.trimIndent()
        val detail = OnlineSonglists.parseKwDetail(json)
        assertEquals(listOf("128k"), detail.tracks[0].qualities.map { it.id })
    }

    @Test
    fun txDetailReadsSingerArrayAndAlbumMid() {
        val json = """
            {"code":0,"cdlist":[{"disstid":7011264340,"dissname":"电子 · 房间里的单人舞池",
              "logo":"https://qpic/music_cover/x.jpg","nickname":"穿着皮鞋跳大神","visitnum":25000,"songnum":76,
              "songlist":[{"name":"雾雨一夜","mid":"004EALe94Pdesa","interval":156,
                "singer":[{"name":"MISFITS文咏曜"},{"name":"法宝"}],
                "album":{"id":100735466,"mid":"002MWx8X1YOeA0","name":"雾雨一夜"}}]}]}
        """.trimIndent()
        val detail = OnlineSonglists.parseTxDetail(json)
        assertEquals("电子 · 房间里的单人舞池", detail.sheet?.name)
        val track = detail.tracks[0]
        assertEquals("004EALe94Pdesa", track.songId)
        assertEquals("MISFITS文咏曜、法宝", track.artist)
        assertEquals(156_000L, track.durationMs)
        assertTrue(track.coverUrl.orEmpty().contains("T002R500x500M000002MWx8X1YOeA0"))
    }

    @Test
    fun kgDetailParsesEmbeddedHtmlData() {
        val html = """
            <html><script>
            var global = {
              name: "瑜伽纯音：生活也需要呼吸",
              pic: "http://c1.kgimg.com/custom/240/20210809/x.jpg",
            };
            global.data = [{"songname":"晨钟暮鼓","singername":"一鱼","hash":"DDEFE677",
              "filesize":3962297,"hash_320":"AAA320","filesize_320":9000000,
              "duration":247614,"album_id":46770650,"album_name":"晨钟暮鼓"}];
            </script></html>
        """.trimIndent()
        val detail = OnlineSonglists.parseKgDetail(html)
        assertEquals("瑜伽纯音：生活也需要呼吸", detail.sheet?.name)
        val track = detail.tracks[0]
        assertEquals("DDEFE677", track.songId)
        assertEquals(247_614L, track.durationMs)
        assertEquals(listOf("128k", "320k"), track.qualities.map { it.id })
        // 酷狗 duration 是毫秒，raw 里必须换算成秒（脚本按洛雪习惯乘 1000）
        assertTrue(track.raw.contains("\"duration\":247"))
    }

    @Test
    fun kgDetailWithoutEmbeddedDataReturnsEmpty() {
        val detail = OnlineSonglists.parseKgDetail("<html>404</html>")
        assertTrue(detail.tracks.isEmpty())
    }

    @Test
    fun mgDetailReadsSongListAndBuildsCover() {
        val json = """
            {"code":"000000","data":{"totalCount":12,"songList":[
              {"songName":"世界上不存在的歌","copyrightId":"6005666R1DB","album":"一首歌一个故事",
               "duration":233,"img3":"/data/oss/resource/00/5u/x.webp",
               "singerList":[{"name":"陈奕迅"},{"name":"另一位"}]}
            ]}}
        """.trimIndent()
        val detail = OnlineSonglists.parseMgDetail(json)
        val track = detail.tracks[0]
        assertEquals("6005666R1DB", track.songId)
        assertEquals("陈奕迅、另一位", track.artist)
        assertEquals(233_000L, track.durationMs)
        assertEquals("https://d.musicapp.migu.cn/data/oss/resource/00/5u/x.webp", track.coverUrl)
    }

    // ---------------------------------------------------------------- 容错与工具

    @Test
    fun htmlErrorPageNeverThrows() {
        val bad = "<html><body>502 Bad Gateway</body></html>"
        assertTrue(OnlineSonglists.parseKwSheets(bad, 1).items.isEmpty())
        assertTrue(OnlineSonglists.parseTxSheets(bad, 1).items.isEmpty())
        assertTrue(OnlineSonglists.parseKgSheets(bad, 1).items.isEmpty())
        assertTrue(OnlineSonglists.parseMgSheets(bad, 1).items.isEmpty())
        assertTrue(OnlineSonglists.parseKwDetail(bad).tracks.isEmpty())
        assertTrue(OnlineSonglists.parseTxDetail(bad).tracks.isEmpty())
        assertTrue(OnlineSonglists.parseMgDetail(bad).tracks.isEmpty())
        assertTrue(OnlineSonglists.parseKwTags(bad).isEmpty())
    }

    @Test
    fun formatCountUsesChineseUnits() {
        assertEquals("", OnlineSonglists.formatCount(0))
        assertEquals("999", OnlineSonglists.formatCount(999))
        assertEquals("1.2万", OnlineSonglists.formatCount(12_000))
        assertEquals("1万", OnlineSonglists.formatCount(10_000))
        assertEquals("1.3亿", OnlineSonglists.formatCount(130_000_000))
        assertFalse(OnlineSonglists.formatCount(99_999).contains(".0"))
    }
}
