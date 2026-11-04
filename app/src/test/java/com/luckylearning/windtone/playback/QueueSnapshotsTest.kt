package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多播放队列快照（竞品借鉴：Musicolet / Symfonium）。
 *
 * 契约：
 * - 编解码幂等，名称里的制表符/换行不会破坏记录；
 * - 同名保存 = 覆盖并排到最前；
 * - 上限 20 条，超出丢最旧；
 * - 坏行（缺字段 / 空 id 列表）被跳过而不是抛异常 —— 这串数据在 prefs 里躺很久。
 */
class QueueSnapshotsTest {

    private fun snap(name: String, ids: List<Long> = listOf(1L, 2L, 3L), index: Int = 1, pos: Long = 5000L) =
        QueueSnapshots.Snapshot(name, ids, index, pos, savedAt = 1000L)

    @Test
    fun encodeDecodeRoundTrip() {
        val one = snap("通勤", listOf(10L, 20L, 30L), index = 2, pos = 61_000L)
        val two = snap("睡前", listOf(7L), index = 0, pos = 0L)
        val decoded = QueueSnapshots.decode(QueueSnapshots.encode(listOf(one, two)))
        assertEquals(listOf(one, two), decoded)
    }

    @Test
    fun tabsAndNewlinesInNameAreSanitized() {
        val s = snap("a\tb\nc")
        val decoded = QueueSnapshots.decode(QueueSnapshots.encode(listOf(s)))
        assertEquals(1, decoded.size)
        assertEquals("a b c", decoded[0].name)
        assertEquals(s.songIds, decoded[0].songIds)
    }

    @Test
    fun upsertOverwritesSameNameAndMovesToFront() {
        val a = snap("A")
        val b = snap("B")
        val updatedA = snap("A", ids = listOf(9L))
        val list = QueueSnapshots.upsert(QueueSnapshots.upsert(emptyList(), a), b)
        val after = QueueSnapshots.upsert(list, updatedA)
        assertEquals(listOf("A", "B"), after.map { it.name })
        assertEquals(listOf(9L), after[0].songIds)
        assertEquals(2, after.size)
    }

    @Test
    fun upsertCapsAtMaxSnapshots() {
        var list = emptyList<QueueSnapshots.Snapshot>()
        repeat(QueueSnapshots.MAX_SNAPSHOTS + 5) { i -> list = QueueSnapshots.upsert(list, snap("Q$i")) }
        assertEquals(QueueSnapshots.MAX_SNAPSHOTS, list.size)
        // 最近保存的在最前，最旧的被挤掉
        assertEquals("Q${QueueSnapshots.MAX_SNAPSHOTS + 4}", list.first().name)
        assertTrue(list.none { it.name == "Q0" })
    }

    @Test
    fun upsertIgnoresEmptyNameOrEmptySongs() {
        assertEquals(0, QueueSnapshots.upsert(emptyList(), snap(" ")).size)
        assertEquals(0, QueueSnapshots.upsert(emptyList(), snap("x", ids = emptyList())).size)
    }

    @Test
    fun decodeSkipsMalformedLines() {
        val raw = "good\t0\t0\t1\t1,2\n" + "missing-fields\n" + "badids\t0\t0\t1\t,,\n" + "emptyids\t0\t0\t1\t\n"
        val decoded = QueueSnapshots.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("good", decoded[0].name)
    }

    @Test
    fun decodeClampsIndexIntoRange() {
        val s = QueueSnapshots.decode("x\t99\t0\t1\t5,6")[0]
        assertEquals(1, s.index)
    }

    @Test
    fun removeByName() {
        val list = listOf(snap("A"), snap("B"))
        assertEquals(listOf("B"), QueueSnapshots.remove(list, "A").map { it.name })
    }
}
