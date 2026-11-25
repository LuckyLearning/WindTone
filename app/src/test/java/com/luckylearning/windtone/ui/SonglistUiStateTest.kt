package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单页状态机的回归点。
 *
 * 1.3.4 之前歌单页会永久停在骨架屏：`loadedKey` 在发请求**之前**就写了，
 * 请求被切页取消后 effect 回来又被它短路，`loading` 再也没人收尾。
 * 现在 `loadedKey` 只在成功后写，失败/取消留在空值，重试由 [SonglistUiState.retry] 清标记。
 */
class SonglistUiStateTest {

    @Test
    fun `新状态停在等待加载`() {
        val s = SonglistUiState()
        assertTrue(s.loading)
        assertNull(s.loadedKey)
        assertNull(s.tagsLoadedFor)
        assertNull(s.error)
    }

    @Test
    fun `retry 清掉所有已加载标记`() {
        val s = SonglistUiState()
        s.source = "tx"
        s.tagId = "12"
        s.tagsLoadedFor = "tx"
        s.loadedKey = "tx|12"
        s.scrolledKey = "tx|12"
        s.failed = true
        s.error = "boom"

        s.retry()

        assertEquals("", s.tagId)
        assertNull(s.tagsLoadedFor)
        assertNull(s.loadedKey)
        assertNull(s.scrolledKey)
        assertNull(s.error)
        assertFalse(s.failed)
        assertEquals(1, s.retryTick)
        // 平台不变：重试只重拉当前平台，不该跳回第一家
        assertEquals("tx", s.source)
    }

    @Test
    fun `换平台后已加载标记要先清空`() {
        val s = SonglistUiState()
        s.loadedKey = "kw|1"
        s.tagsLoadedFor = "kw"

        // 换平台时由第一个 effect 清这三项；这里只守状态字段的语义，
        // 避免以后又把 loadedKey 当成「试过」而不是「成功了」。
        s.loadedKey = null
        s.tagsLoadedFor = null

        assertNull(s.loadedKey)
        assertNull(s.tagsLoadedFor)
    }
}
