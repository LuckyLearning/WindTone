package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuOrderTest {

    @Test
    fun `空存档就是默认顺序，实验室排在最后`() {
        val order = SettingsMenuOrder.merge(emptyList())
        assertEquals(SettingsMenuOrder.DEFAULT, order)
        assertEquals("LAB", order.last())
        // v1.1.80：主页只剩 6 张卡
        assertEquals(6, order.size)
    }

    /**
     * 老用户的存档里还留着已降级的三个分类 id（悬浮歌词 / 在线播放 / 更新）。
     *
     * 它们现在分别是「歌词」「实验室」「关于」里的入口行，不再是分类卡；
     * merge 必须把它们过滤掉，否则主页会凭空少一张卡、或者拼出一个不存在的页面。
     */
    @Test
    fun `老存档里已降级的分类会被过滤`() {
        val saved = listOf("APPEARANCE", "OVERLAY", "PLAYBACK", "ONLINE_HUB", "UPDATE", "LYRICS")
        val order = SettingsMenuOrder.merge(saved)
        assertEquals(SettingsMenuOrder.DEFAULT.toSet(), order.toSet())
        assertFalse("OVERLAY 已收进歌词页", order.contains("OVERLAY"))
        assertFalse("ONLINE_HUB 已收进实验室", order.contains("ONLINE_HUB"))
        assertFalse("UPDATE 已收进关于", order.contains("UPDATE"))
        assertFalse("MENU_ORDER 不是分类卡", order.contains("MENU_ORDER"))
        // 保留下来的相对顺序不被过滤打乱
        assertTrue(order.indexOf("APPEARANCE") < order.indexOf("PLAYBACK"))
    }

    @Test
    fun `存档里没有的新页面按默认位置补上`() {
        val saved = SettingsMenuOrder.DEFAULT.filter { it != "LAB" }
        assertEquals(SettingsMenuOrder.DEFAULT, SettingsMenuOrder.merge(saved))
    }
}
