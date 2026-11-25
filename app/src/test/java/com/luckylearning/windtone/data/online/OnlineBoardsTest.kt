package com.luckylearning.windtone.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 排行榜解析的回归测试。
 *
 * 四家的榜单详情返回结构各不相同，而且与各家自己的搜索接口**也不一样**
 * （最典型是 QQ：搜索是 `req.data.body.song.list`，榜单是 `toplist.data.songInfoList`）。
 * 下面的 JSON 都是从真实接口响应裁剪出来的骨架，改解析时必须让它们继续通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineBoardsTest {

    @Test
    fun parsesKwBoard() {
        val text = """
            {"name":"酷我热歌榜","musiclist":[
              {"id":"228908","name":"晴天","artist":"周杰伦","album":"叶惠美",
               "albumid":"1293","duration":"269","formats":"MP3H|MP3128"}
            ]}
        """.trimIndent()
        val tracks = OnlineBoards.parseKwBoard(text)
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals("kw", track.sourceId)
        assertEquals("228908", track.songId)
        assertEquals("晴天", track.title)
        assertEquals("周杰伦", track.artist)
        assertEquals(269_000L, track.durationMs)
        // 直链要靠 raw 里的源内 id，缺了就是「搜得到播不了」
        assertTrue(track.raw.contains("\"songmid\":\"228908\""))
    }

    @Test
    fun parsesKgBoardWithPerQualityHashes() {
        val text = """
            {"errcode":0,"data":{"total":500,"info":[
              {"songname":"慌啥子嘛","singername":"","audio_id":"1109038964",
               "hash":"C7B1D05DE4585E4E4B76832B7DA8D3E3","filesize":3037519,
               "320hash":"AAAA320","320filesize":9000000,
               "sqhash":"BBBBFLAC","sqfilesize":21967894,
               "album_id":"214831420","duration":189,"remark":"慌啥子嘛 (赵本山版)",
               "authors":[{"author_name":"赵本山"}]}
            ]}}
        """.trimIndent()
        val track = OnlineBoards.parseKgBoard(text).single()
        assertEquals("kg", track.sourceId)
        // singername 为空时要用 authors[].author_name（榜单常见，洛雪也是这么兜的）
        assertEquals("赵本山", track.artist)
        assertEquals("慌啥子嘛 (赵本山版)", track.album)
        assertEquals(189_000L, track.durationMs)
        // 每个档位有自己的 hash：只给平铺 hash 会让脚本报「kg song hash missing」
        assertTrue(track.raw.contains("\"hash\":\"AAAA320\""))
        assertTrue(track.raw.contains("\"hash\":\"BBBBFLAC\""))
        assertTrue(track.qualities.any { it.id == "320k" })
        assertTrue(track.qualities.any { it.id == "flac" })
        assertNull(track.coverUrl)
    }

    @Test
    fun parsesKgBoardCoverAndExpandsSizeTemplate() {
        val text = """
            {"data":{"info":[
              {"songname":"晴天","singername":"周杰伦","audio_id":"1","hash":"H",
               "filesize":100,"duration":200,"album_id":"9","remark":"叶惠美",
               "album_sizable_cover":"http://imge.kugou.com/stdmusic/{size}/20250125/abc.jpg"}
            ]}}
        """.trimIndent()
        val track = OnlineBoards.parseKgBoard(text).single()
        assertEquals(
            "https://imge.kugou.com/stdmusic/480/20250125/abc.jpg",
            track.coverUrl,
        )
        // org.json 会把 / 写成 \/，不能拿原始文本做包含判断
        assertEquals(
            "https://imge.kugou.com/stdmusic/480/20250125/abc.jpg",
            org.json.JSONObject(track.raw).optString("img"),
        )
    }

    @Test
    fun parsesTxBoardThroughToplistSongInfoList() {
        val text = """
            {"code":0,"toplist":{"code":0,"data":{"songInfoList":[
              {"mid":"004OkWaK0fbwj9","id":744462251,"name":"宿命之外","interval":228,
               "singer":[{"name":"时代少年团"}],
               "album":{"mid":"003RtsOK2RM7NW","name":"宿命之外"},
               "file":{"media_mid":"004OkWaK0fbwj9","size_128mp3":3665286,"size_320mp3":9162912,"size_flac":0,"size_hires":0}}
            ]}}}
        """.trimIndent()
        val track = OnlineBoards.parseTxBoard(text).single()
        assertEquals("tx", track.sourceId)
        assertEquals("004OkWaK0fbwj9", track.songId)
        assertEquals("宿命之外", track.title)
        assertEquals("时代少年团", track.artist)
        assertEquals(228_000L, track.durationMs)
        assertTrue(track.raw.contains("\"strMediaMid\":\"004OkWaK0fbwj9\""))
        assertTrue(track.coverUrl!!.contains("003RtsOK2RM7NW"))
    }

    @Test
    fun parsesMgBoardAndConvertsMmSsDuration() {
        val text = """
            {"code":"000000","columnInfo":{"contentsCount":"1","contents":[
              {"objectInfo":{"songId":"1145708036","copyrightId":"69919818517",
               "songName":"慌啥子嘛","singer":"赵本山","album":"慌啥子嘛",
               "albumId":"1145708035","length":"00:03:09",
               "albumImgs":[{"img":"https://d.musicapp.migu.cn/a.webp"},{"img":"https://d.musicapp.migu.cn/b.jpg"}],
               "lrcUrl":"https://d.musicapp.migu.cn/lrc"}}
            ]}}
        """.trimIndent()
        val track = OnlineBoards.parseMgBoard(text).single()
        assertEquals("mg", track.sourceId)
        assertEquals("69919818517" , track.raw.let { org.json.JSONObject(it).getString("copyrightId") })
        // "00:03:09" → 189 秒。咪咕给的是文本，不能按数字解析
        assertEquals(189_000L, track.durationMs)
        // webp 直接跳过，优先 jpg（省解码、也避免取到小图）
        assertTrue(track.coverUrl!!.endsWith("b.jpg"))
    }

    @Test
    fun miguBoardKeepsWebpWhenThatIsTheOnlyImage() {
        val text = """
            {"code":"000000","columnInfo":{"contents":[
              {"objectInfo":{"songId":"1","copyrightId":"2","songName":"a","singer":"b",
               "length":"00:03:09",
               "albumImgs":[{"img":"http://d.musicapp.migu.cn/only.webp"}]}}
            ]}}
        """.trimIndent()
        val track = OnlineBoards.parseMgBoard(text).single()
        assertEquals("https://d.musicapp.migu.cn/only.webp", track.coverUrl)
    }

    @Test
    fun emptyOrBrokenBodyNeverThrows() {
        // 榜单页要能显示「这个榜取不到」，而不是把整页打崩
        assertTrue(OnlineBoards.parseKwBoard("<html>502</html>").isEmpty())
        assertTrue(OnlineBoards.parseKgBoard("{}").isEmpty())
        assertTrue(OnlineBoards.parseTxBoard("{\"code\":-1}").isEmpty())
        assertTrue(OnlineBoards.parseMgBoard("{\"code\":\"1001\"}").isEmpty())
        assertFalse(OnlineBoards.GROUPS.isEmpty())
    }
}
