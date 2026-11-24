package com.luckylearning.windtone.data.online.script

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 钉死「宿主 → 脚本」的请求报文。
 *
 * 洛雪 preload 的 `handleRequest = ({ requestKey, data }) => ...` 直接读 `data.source`
 * 和 `data.info`。曾把 `data` 这一层省掉，脚本收到的 `info` 是 undefined，
 * 结果就是**搜索能出结果、一首也播不了**，脚本侧还只抛一句无关的字段错。
 *
 * 这个测试唯一的职责就是让那种写法编不过测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LxRequestEnvelopeTest {

    private fun info() = JSONObject().apply {
        put("type", "320k")
        put("musicInfo", JSONObject().put("source", "kw").put("songmid", "228908"))
    }

    @Test
    fun requestPayloadNestsEverythingUnderData() {
        val payload = LxRequestEnvelope.build("rk1", "kw", "musicUrl", info())
        assertEquals("rk1", payload.getString("requestKey"))

        // 必须有两层
        assertTrue("缺少 data 这一层", payload.has("data"))
        val data = payload.getJSONObject("data")
        assertEquals("kw", data.getString("source"))
        assertEquals("musicUrl", data.getString("action"))
        assertEquals("320k", data.getJSONObject("info").getString("type"))
        assertEquals("228908", data.getJSONObject("info").getJSONObject("musicInfo").getString("songmid"))

        // 反例：顶层不能再平铺 source / action / info（那正是出过错的写法）
        assertFalse(payload.has("source"))
        assertFalse(payload.has("action"))
        assertFalse(payload.has("info"))
    }

    @Test
    fun payloadSurvivesJsonRoundTrip() {
        // 报文以字符串跨 QuickJS 边界，再 JSON.parse 回来 —— 序列化不能丢嵌套
        val text = LxRequestEnvelope.build("rk2", "tx", "musicUrl", info()).toString()
        val parsed = JSONObject(text)
        assertEquals(
            "kw",
            parsed.getJSONObject("data").getJSONObject("info").getJSONObject("musicInfo").getString("source"),
        )
    }

    @Test
    fun actionInfoWrapsMusicInfoAndKeepsQualityOutside() {
        // 洛雪 getMusicUrl 的 info 是 { type, musicInfo }：type 在**外层**。
        // 平铺进 musicInfo 会让 fish 报 "kw song id missing"。
        val track = com.luckylearning.windtone.data.online.source.OnlineTrack(
            sourceId = "kw",
            songId = "228908",
            title = "晴天",
            artist = "周杰伦",
            raw = """{"source":"kw","songmid":"228908"}""",
        )
        val info = LxActionInfo.build(track, com.luckylearning.windtone.data.online.source.Quality.Q320)
        assertEquals("320k", info.getString("type"))
        assertTrue(info.has("musicInfo"))
        assertEquals("228908", info.getJSONObject("musicInfo").getString("songmid"))
        assertFalse(info.getJSONObject("musicInfo").has("type"))
    }
}
