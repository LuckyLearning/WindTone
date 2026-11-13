package com.anylyric.accessibility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按应用隐藏」的判定规则。
 *
 * 这些函数看着只有几行，但它们是**唯一**决定「悬浮窗该不该藏」的地方：
 * 判错一次就是真机上的「该藏没藏」或「到处乱藏」，所以固定成纯函数并上单测。
 */
class ForegroundAppsTest {

    @After
    fun tearDown() {
        // object 单例：不清干净会污染下一个测试
        ForegroundApps.reset()
        ForegroundApps.serviceConnected = false
    }

    @Test
    fun `input method package is ignored`() {
        // 在名单应用里点搜索框：事件多出一层输入法，若不忽略，判定会变成「前台是输入法」
        // → 不再命中名单 → 悬浮窗又冒出来，用户看到的就是「隐藏失效」。
        assertTrue(
            shouldIgnoreForegroundEvent("com.iflytek.inputmethod", setOf("com.iflytek.inputmethod"))
        )
        assertFalse(shouldIgnoreForegroundEvent("com.example.video", setOf("com.iflytek.inputmethod")))
    }

    @Test
    fun `empty ime set disables the filter`() {
        assertFalse(shouldIgnoreForegroundEvent("com.iflytek.inputmethod", emptySet()))
        assertFalse(shouldIgnoreForegroundEvent(null, setOf("com.iflytek.inputmethod")))
    }

    @Test
    fun `foreground in the hidden list is hidden`() {
        assertTrue(overlayHiddenFor("com.example.video", setOf("com.example.video")))
    }

    @Test
    fun `foreground outside the hidden list is shown`() {
        assertFalse(overlayHiddenFor("com.example.music", setOf("com.example.video")))
    }

    @Test
    fun `empty list never hides`() {
        // 名单为空 = 功能完全不介入，连判定都不该发生
        assertFalse(overlayHiddenFor("com.example.video", emptySet()))
    }

    @Test
    fun `unknown foreground never hides`() {
        assertFalse(overlayHiddenFor(null, setOf("com.example.video")))
        assertFalse(overlayHiddenFor("", setOf("com.example.video")))
    }

    @Test
    fun `detection needs both the switch and a non-empty list`() {
        assertTrue(foregroundDetectionNeeded(enabled = true, hiddenApps = setOf("a.b.c")))
        // 开关开着但名单空：没有可判定的对象，一次早退
        assertFalse(foregroundDetectionNeeded(enabled = true, hiddenApps = emptySet()))
        // 名单在但开关关着：用户明确停用了，同样不该跑
        assertFalse(foregroundDetectionNeeded(enabled = false, hiddenApps = setOf("a.b.c")))
    }

    @Test
    fun `report notifies only when the package really changed`() {
        val seen = ArrayList<String?>()
        val listener: (String?) -> Unit = { seen += it }
        ForegroundApps.addListener(listener)
        try {
            ForegroundApps.report("com.example.video")
            // 同一个包重复上报（窗口内部变化）不该触发重算
            ForegroundApps.report("com.example.video")
            ForegroundApps.report("com.example.music")
        } finally {
            ForegroundApps.removeListener(listener)
        }
        assertEquals(listOf("com.example.video", "com.example.music"), seen)
    }

    @Test
    fun `reset reports null so the overlay comes back`() {
        val seen = ArrayList<String?>()
        val listener: (String?) -> Unit = { seen += it }
        ForegroundApps.report("com.example.video")
        ForegroundApps.addListener(listener)
        try {
            ForegroundApps.reset()
        } finally {
            ForegroundApps.removeListener(listener)
        }
        assertEquals(listOf<String?>(null), seen)
        assertNull(ForegroundApps.currentPkg)
    }

    @Test
    fun `recent keeps the newest 20 and drops the oldest`() {
        // 车机现场排查就靠这份列表：能翻到「刚才切过的那个包」。
        // 无上限会让长跑进程一点点涨内存，所以固定 20 条。
        repeat(25) { ForegroundApps.report("pkg.$it") }
        val recent = ForegroundApps.recent()
        assertEquals(20, recent.size)
        assertEquals("pkg.5", recent.first().pkg)
        assertEquals("pkg.24", recent.last().pkg)
    }

    @Test
    fun `recent skips duplicate reports`() {
        // 同一个包重复上报（窗口内部变化）不该在列表里刷出一堆同一条
        ForegroundApps.report("com.example.video")
        ForegroundApps.report("com.example.video")
        assertEquals(1, ForegroundApps.recent().size)
    }

    @Test
    fun `reset clears the recent list`() {
        ForegroundApps.report("com.example.video")
        ForegroundApps.reset()
        assertTrue(ForegroundApps.recent().isEmpty())
    }
}
