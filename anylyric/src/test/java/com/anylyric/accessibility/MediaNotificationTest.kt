package com.anylyric.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 媒体通知文本的抽取规则。
 *
 * 这些断言都对应真机上真出现过的问题：标题在正文里重复一遍 → 歌词第 0 行变成歌名；
 * 只取 `EXTRA_TEXT` → 多行歌词被截成一行「歌手」。
 */
class MediaNotificationTest {

    @Test
    fun bigTextWinsOverSingleLine() {
        val content = MediaNotification.split("晴天", "周杰伦", "晴天\n故事的小黄花\n从出生那年就飘着")
        assertEquals("晴天", content.title)
        // 第一行与标题相同，去掉 —— 否则歌词会从歌名开始念。
        assertEquals("故事的小黄花\n从出生那年就飘着", content.body)
    }

    @Test
    fun fallsBackToTextWhenNoBigText() {
        val content = MediaNotification.split("晴天", "周杰伦", null)
        assertEquals("晴天", content.title)
        assertEquals("周杰伦", content.body)
    }

    @Test
    fun bodyEqualToTitleIsDropped() {
        val content = MediaNotification.split("晴天", "晴天", null)
        assertEquals("晴天", content.title)
        assertNull(content.body)
    }

    @Test
    fun blankFieldsBecomeNull() {
        val content = MediaNotification.split("  ", "   ", "")
        assertNull(content.title)
        assertNull(content.body)
    }

    @Test
    fun missingTitleKeepsBody() {
        val content = MediaNotification.split(null, null, "第一句\n第二句")
        assertNull(content.title)
        assertEquals("第一句\n第二句", content.body)
    }

    /** 单行正文不能被当成「两行歌词」的输入（拆分与去重后只剩一行时保持单行）。 */
    @Test
    fun singleLineBodySurvivesAsSingleLine() {
        val content = MediaNotification.split("歌名", null, "歌手")
        assertEquals("歌手", content.body)
    }
}
