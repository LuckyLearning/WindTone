package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.data.online.script.LxActionInfo
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归：洛雪 v2 action 入参必须是两层 `{ type, musicInfo }`。
 *
 * 曾经把歌曲字段平铺进 info（缺 musicInfo 层），fish 源报
 * `kw song id missing`，一个网络请求都不发 —— 内置搜索能出结果但一首都播不了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LxActionInfoTest {

    private fun kwTrack(raw: String, songId: String = "228908") = OnlineTrack(
        sourceId = "kw", songId = songId, title = "晴天", artist = "周杰伦", raw = raw,
    )

    @Test
    fun wrapsRawIntoMusicInfoAndPutsQualityOutside() {
        val track = kwTrack("""{"source":"kw","songmid":"228908","name":"晴天","types":[{"type":"320k"}]}""")
        val info = LxActionInfo.build(track, Quality.Q320)

        assertEquals("320k", info.getString("type"))
        val musicInfo = info.getJSONObject("musicInfo")
        assertEquals("228908", musicInfo.getString("songmid"))
        assertEquals("晴天", musicInfo.getString("name"))
        assertTrue(musicInfo.has("types"))
        // 歌曲字段不能在最外层（否则脚本读 info.musicInfo.songmid 会扑空）
        assertFalse(info.has("songmid"))
    }

    @Test
    fun qqqSourceKeepsAllSignatureFieldsInMusicInfo() {
        val raw = """{"source":"tx","songmid":"0039Sv5s3BeegG","strMediaMid":"M800000bYDlc2XxKLs","albumMid":"000MkMni19ClKG","types":[{"type":"320k"}]}"""
        val info = LxActionInfo.build(kwTrack(raw, "0039Sv5s3BeegG"), Quality.Q320)
        val mi = info.getJSONObject("musicInfo")
        assertEquals("M800000bYDlc2XxKLs", mi.getString("strMediaMid"))
        assertEquals("000MkMni19ClKG", mi.getString("albumMid"))
    }

    @Test
    fun fallsBackSongIdWhenRawMissingIdentity() {
        val info = LxActionInfo.build(kwTrack("""{"name":"晴天"}""", "999999"))
        assertEquals("999999", info.getJSONObject("musicInfo").getString("songmid"))
        assertEquals("128k", info.getString("type"))
    }

    @Test
    fun hashSourceDoesNotGetSpuriousSongmid() {
        // 酷狗用 hash 取址，不该硬塞一个 songmid 干扰脚本判断
        val info = LxActionInfo.build(
            OnlineTrack("kg", "123", "晴天", raw = """{"source":"kg","hash":"ABCDEF","types":[]}"""),
        )
        val mi = info.getJSONObject("musicInfo")
        assertTrue(mi.has("hash"))
        assertFalse(mi.has("songmid"))
    }
}
