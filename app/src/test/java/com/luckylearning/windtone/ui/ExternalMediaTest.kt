package com.luckylearning.windtone.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 外部播放入口的意图解析（[ExternalMedia.parse]）。
 *
 * 这两条入口都是「声明了必须接住」的类型：清单里挂了 `ACTION_VIEW` 带音频通配类型 与
 * `MEDIA_PLAY_FROM_SEARCH`，用户在文件管理器里点歌、或让车机语音点歌，落到本应用时
 * 如果解析不出来，表现就是「应用被打开了，但什么也没播」—— 真机上最难定位的那类。
 */
class ExternalMediaTest {

    @Test
    fun `文件管理器点歌解析为按 uri 播放`() {
        val req = ExternalMedia.parse(
            action = Intent.ACTION_VIEW,
            uri = "content://media/external/audio/media/42",
            query = null,
        )
        assertEquals(
            ExternalRequest.OpenUri("content://media/external/audio/media/42"),
            req,
        )
    }

    @Test
    fun `VIEW 但没带 uri 时不接`() {
        // 通知点击、应用间唤起都可能是不带 data 的 VIEW，接了会莫名其妙开播
        assertNull(ExternalMedia.parse(Intent.ACTION_VIEW, null, "周杰伦"))
        assertNull(ExternalMedia.parse(Intent.ACTION_VIEW, "   ", null))
    }

    @Test
    fun `语音点歌解析为搜索词并去掉首尾空白`() {
        val req = ExternalMedia.parse(
            action = ExternalMedia.ACTION_SEARCH,
            uri = null,
            query = "  周杰伦  ",
        )
        assertEquals(ExternalRequest.SearchSong("周杰伦"), req)
    }

    @Test
    fun `语音点歌但没识别出词时不接`() {
        // 空查询如果放过去，就会变成「播整个曲库」—— 不是用户说的
        assertNull(ExternalMedia.parse(ExternalMedia.ACTION_SEARCH, null, null))
        assertNull(ExternalMedia.parse(ExternalMedia.ACTION_SEARCH, null, "   "))
    }

    @Test
    fun `无关 action 一律不接`() {
        assertNull(ExternalMedia.parse(Intent.ACTION_MAIN, "content://x/1", "周杰伦"))
        assertNull(ExternalMedia.parse(null, "content://x/1", "周杰伦"))
    }

    @Test
    fun `取走的意图只执行一次`() {
        ExternalMedia.consumePending()
        ExternalMedia.put(ExternalRequest.SearchSong("周杰伦"))
        assertEquals(ExternalRequest.SearchSong("周杰伦"), ExternalMedia.consumePending())
        // 取走即清空：同一次点击不该被两个 LaunchedEffect 各播一遍
        assertNull(ExternalMedia.consumePending())
    }
}
