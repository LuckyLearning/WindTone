package com.luckylearning.windtone.playback

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 队列操作的纯决策（[QueuePolicies]）。
 *
 * 这些边界最容易在连点 / 多选时出重复条目或插错位置，而它们与播放器状态无关，
 * 单测就能钉死。引擎侧只负责把结果落到 MediaController。
 *
 * 用 Robolectric 跑：测试需要构造 [Song]（依赖 `Uri.parse`，纯 JVM 下是 Android 桩会返回 null）。
 */
@RunWith(RobolectricTestRunner::class)
class QueuePoliciesTest {

    private fun song(key: String) = Song(
        id = 0L,
        uri = Uri.parse("x:$key"),
        title = key,
        artist = null,
        album = null,
    )

    @Test
    fun `playNext 插入位：队列空为 0`() {
        assertEquals(0, QueuePolicies.playNextInsertIndex(-1, 0))
    }

    @Test
    fun `playNext 插入位：当前项下一项且不越界`() {
        assertEquals(3, QueuePolicies.playNextInsertIndex(2, 5))
        // 当前下标越界时夹到队列尾
        assertEquals(5, QueuePolicies.playNextInsertIndex(9, 5))
    }

    @Test
    fun `playNext 移动目标：已在前面则回退一位`() {
        assertEquals(2, QueuePolicies.playNextMoveTarget(exists = 1, insertAt = 3))
    }

    @Test
    fun `playNext 移动目标：已在后面则不动`() {
        assertEquals(3, QueuePolicies.playNextMoveTarget(exists = 5, insertAt = 3))
    }

    @Test
    fun `enqueue 去重：剔除已在队列的`() {
        val a = song("a"); val b = song("b"); val c = song("c")
        val fresh = QueuePolicies.enqueueNewSongs(listOf(a, b, c), setOf("x:a", "x:c"))
        assertEquals(listOf(b), fresh)
    }

    @Test
    fun `enqueue 去重：全部已存在则为空`() {
        val a = song("a")
        assertEquals(emptyList<Song>(), QueuePolicies.enqueueNewSongs(listOf(a), setOf("x:a")))
    }

    @Test
    fun `shuffleOrderIndices 空表`() {
        val (order, cur) = QueuePolicies.shuffleOrderIndices(0, 0, Random(1))
        assertEquals(emptyList<Int>(), order)
        assertEquals(0, cur)
    }

    @Test
    fun `shuffleOrderIndices 单曲原样`() {
        val (order, cur) = QueuePolicies.shuffleOrderIndices(1, 0, Random(1))
        assertEquals(listOf(0), order)
        assertEquals(0, cur)
    }

    @Test
    fun `shuffleOrderIndices 是原下标的置换且能找回当前曲`() {
        val random = Random(42)
        val (order, cur) = QueuePolicies.shuffleOrderIndices(5, 2, random)
        assertEquals(5, order.size)
        assertEquals((0..4).toSet(), order.toSet())
        assertEquals(2, order[cur])
        assertNotEquals(listOf(0, 1, 2, 3, 4), order)
    }

    @Test
    fun `shuffleOrderIndices 当前下标越界夹到合法`() {
        val (order, cur) = QueuePolicies.shuffleOrderIndices(3, 99, Random(7))
        assertEquals(3, order.size)
        assertTrue(cur in 0..2)
        assertEquals(2, order[cur])
    }
}
