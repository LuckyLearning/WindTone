package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.data.online.source.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归：QQ 搜索结果的真实结构是 `req.data.body.song.list`。
 *
 * 曾少写一层 `.body`，导致 QQ 永远搜到 0 首（接口 200、code 0，但列表取空）。
 * 下面的 JSON 是从真实接口响应裁剪出来的骨架。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuiltInSearchTest {

    @Test
    fun parsesTxResultThroughDataBodySongList() {
        val body = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"mid":"0039Sv5s3BeegG","id":228908,"title":"晴天","interval":269,
               "singer":[{"name":"周杰伦","mid":"000N18rM2QjXJx"}],
               "album":{"mid":"000MkMni19ClKG","name":"叶惠美"},
               "file":{"media_mid":"M800000bYDlc2XxKLs","size_128mp3":3200000,"size_320mp3":8000000,"size_flac":0,"size_hires":0}}
            ]}}}}}
        """.trimIndent()

        val tracks = BuiltInSearch.parseTx(body)

        assertEquals(1, tracks.size)
        val t = tracks[0]
        assertEquals("tx", t.sourceId)
        assertEquals("0039Sv5s3BeegG", t.songId)
        assertEquals("晴天", t.title)
        assertEquals("周杰伦", t.artist)
        assertEquals(269_000L, t.durationMs)
        assertTrue("应解析出 128k/320k 两档", setOf(Quality.Q128, Quality.Q320) == t.qualities.toSet())
        // raw 里必须保留取直链需要的签名字段
        val raw = org.json.JSONObject(t.raw)
        assertEquals("M800000bYDlc2XxKLs", raw.getString("strMediaMid"))
        assertEquals("000MkMni19ClKG", raw.getString("albumMid"))
    }

    @Test
    fun txErrorResponseYieldsEmptyList() {
        assertTrue(BuiltInSearch.parseTx("""{"code":0,"music.search.SearchCgiService":{"code":2001}}""").isEmpty())
        assertTrue(BuiltInSearch.parseTx("not json").isEmpty())
    }
}
