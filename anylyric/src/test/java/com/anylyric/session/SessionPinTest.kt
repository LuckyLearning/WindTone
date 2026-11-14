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
}
