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

    /**
     * 2026-10-30：「关于与诊断」拆成「关于与更新」（一级）+「诊断与排障」（二级），
     * 一级卡的 id 从 `DIAGNOSTICS` 变成 `ABOUT`。
     *
     * 不迁移的后果很具体：老用户存档里那一项被当成未知 id 丢掉，卡片被塞回默认位置，
     * 表现为「我自己排的顺序怎么自己变回去了」。这条测试锁住迁移与位置保持。
     */
    @Test
    fun `老存档里的关于卡片迁移到新 id 且位置不变`() {
        // 六张卡齐全、但「关于」还叫旧 id —— 迁移后应与默认顺序**逐位相同**，
        // 不能被当成未知 id 丢掉再按默认位置补回（那样卡片会自己跳位）
        val saved = listOf("APPEARANCE", "PLAYBACK", "LYRICS", "LIBRARY", "DIAGNOSTICS", "LAB")
        val order = SettingsMenuOrder.merge(saved)

        assertEquals(SettingsMenuOrder.DEFAULT, order)
        assertFalse("旧 id 不该留在顺序里", order.contains("DIAGNOSTICS"))
        assertEquals(4, order.indexOf("ABOUT"))
    }
}
