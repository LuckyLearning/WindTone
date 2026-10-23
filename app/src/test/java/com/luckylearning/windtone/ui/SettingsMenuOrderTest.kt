package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMenuOrderTest {

    @Test
    fun `空存档就是默认顺序，在线播放在音乐库后面`() {
        val order = SettingsMenuOrder.merge(emptyList())
        assertEquals(SettingsMenuOrder.DEFAULT, order)
        assertEquals(order.indexOf("LIBRARY") + 1, order.indexOf("ONLINE_HUB"))
    }

    @Test
    fun `存档里没有的新页面按默认位置补上`() {
        val saved = SettingsMenuOrder.DEFAULT.filter { it != "ONLINE_HUB" }
        assertEquals(SettingsMenuOrder.DEFAULT, SettingsMenuOrder.merge(saved))
    }
}
