package com.anylyric.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPinTest {

    private val sessions = listOf("com.salt.music", "com.anylyric.nav", "com.kugou")

    private fun narrow(pinned: String) =
        SessionPin.narrow(sessions, pinned) { it }

    @Test
    fun `blank pin keeps every candidate`() {
        assertEquals(sessions, narrow(""))
        assertEquals(sessions, narrow("   "))
    }

    @Test
    fun `pin narrows to exactly one package`() {
        assertEquals(listOf("com.kugou"), narrow("com.kugou"))
    }

    @Test
    fun `pin absent from candidates yields empty so caller shows nothing`() {
        assertTrue(narrow("com.netease.cloudmusic").isEmpty())
    }

    @Test
    fun `pin never falls back to other packages`() {
        // 导航语音正在播放，锁的是音乐 App：绝不能回退去读导航。
        val navOnly = listOf("com.anylyric.nav")
        assertTrue(SessionPin.narrow(navOnly, "com.salt.music") { it }.isEmpty())
    }

    @Test
    fun `multiple sessions of the pinned package are all kept`() {
        val dup = listOf("com.kugou", "com.kugou", "com.salt.music")
        assertEquals(
            listOf("com.kugou", "com.kugou"),
            SessionPin.narrow(dup, "com.kugou") { it }
        )
    }

    // ---------------------------------------------------------------- 选择列表去重

    private fun info(pkg: String, title: String? = null, playing: Boolean = false) =
        SessionInfo(packageName = pkg, title = title, playing = playing)

    @Test
    fun `same package collapses to one row keeping the playing session`() {
        // 真机现场：番茄小说同时挂两个会话，列表里出现两条一模一样的「番茄小说」。
        val list = SessionPin.collapse(
            listOf(
                info("com.dragon.read", title = "第 12 章", playing = false),
                info("com.dragon.read", title = "第 12 章", playing = true)
            )
        )
        assertEquals(1, list.size)
        assertTrue(list.single().playing)
    }

    @Test
    fun `collapsed row prefers the one with a title when none is playing`() {
        val list = SessionPin.collapse(
            listOf(
                info("com.salt.music", title = null, playing = false),
                info("com.salt.music", title = "晴天", playing = false)
            )
        )
        assertEquals("晴天", list.single().title)
    }

    @Test
    fun `playing sessions come first but order within a rank is preserved`() {
        val list = SessionPin.collapse(
            listOf(
                info("com.dragon.read", title = "x", playing = false),
                info("com.luna.music", title = "y", playing = true),
                info("com.kugou", title = "z", playing = false)
            )
        )
        assertEquals(
            listOf("com.luna.music", "com.dragon.read", "com.kugou"),
            list.map { it.packageName }
        )
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(SessionPin.collapse(emptyList()).isEmpty())
    }
}
