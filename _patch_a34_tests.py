# -*- coding: utf-8 -*-
from pathlib import Path
ROOT = Path(r"E:\workspace\Android\WindTone")

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")
    print("wrote", rel)

# ---------- QueuePoliciesTest ----------
pt = read("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt")
if "shuffleOrderIndices" not in pt:
    pt = pt.replace(
        "import org.junit.Assert.assertEquals\n",
        "import org.junit.Assert.assertEquals\n"
        "import org.junit.Assert.assertNotEquals\n"
        "import org.junit.Assert.assertTrue\n"
        "import kotlin.random.Random\n",
    )
    pt = pt.rstrip() + """

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
        // 固定种子下应与原序不同（几乎必然；种子 42 已核对）
        assertNotEquals(listOf(0, 1, 2, 3, 4), order)
    }

    @Test
    fun `shuffleOrderIndices 当前下标越界夹到合法`() {
        val (order, cur) = QueuePolicies.shuffleOrderIndices(3, 99, Random(7))
        assertEquals(3, order.size)
        assertTrue(cur in 0..2)
        assertEquals(2, order[cur]) // 99 coerceIn → 2
    }
}
"""
    write("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt", pt)
else:
    print("policies test already has shuffle")

# ---------- QueueManagerTest ----------
mt = read("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt")
if "clearQueue 清空" not in mt and "`clearQueue" not in mt:
    anchor = """    @Test
    fun `moveQueueItem 越界不操作`() {
        val (m, p) = fixture(listOf(song("A"), song("B")))
        m.moveQueueItem(0, 9)
        assertFalse(p.events.any { it.startsWith("move") })
        assertEquals(0, p.host.persistCount)
    }

    // ================================================================ 替身
"""
    if anchor not in mt:
        raise SystemExit("QueueManagerTest anchor missing")
    insert = """    @Test
    fun `moveQueueItem 越界不操作`() {
        val (m, p) = fixture(listOf(song("A"), song("B")))
        m.moveQueueItem(0, 9)
        assertFalse(p.events.any { it.startsWith("move") })
        assertEquals(0, p.host.persistCount)
    }

    @Test
    fun `clearQueue 清空并提示且落库`() {
        val (m, p) = fixture(listOf(song("A"), song("B"), song("C")))
        p.isPlaying = true
        m.clearQueue()
        assertEquals(0, p.items.size)
        assertEquals(0, p.mediaItemCount)
        assertEquals(0, p.host.queueSize)
        assertTrue(p.host.currentQueue.isEmpty())
        assertEquals(1, p.host.persistCount)
        assertEquals("已清空播放队列", p.host.messages.last())
        assertTrue(p.events.any { it.startsWith("setMediaItems(0,") })
        // 不改随机播放模式
        assertFalse(p.events.any { it.startsWith("shuffle=") })
    }

    @Test
    fun `clearQueue 空队列只提示不落库`() {
        val (m, p) = fixture()
        m.clearQueue()
        assertEquals(0, p.host.persistCount)
        assertEquals("播放队列是空的", p.host.messages.last())
    }

    @Test
    fun `shuffleQueueOrder 打乱物理序且不开关随机模式`() {
        val songs = listOf(song("A"), song("B"), song("C"), song("D"))
        val (m, p) = fixture(songs)
        p.currentMediaItemIndex = 1
        p.currentPosition = 1234L
        p.isPlaying = true
        p.shuffleModeEnabled = false
        val beforeIds = p.items.map { it.mediaId }
        m.shuffleQueueOrder(kotlin.random.Random(99))
        val afterIds = p.items.map { it.mediaId }
        assertEquals(4, afterIds.size)
        assertEquals(beforeIds.toSet(), afterIds.toSet())
        assertNotEquals(beforeIds, afterIds) // 种子 99 已核对会变序
        // 当前曲 B 仍在，下标已更新
        assertEquals("B".let { song(it).stableKey }, p.items[p.currentMediaItemIndex].mediaId)
        assertTrue(p.events.contains("prepare"))
        assertTrue(p.events.contains("play"))
        assertEquals(1, p.host.persistCount)
        assertEquals("已打乱队列顺序", p.host.messages.last())
        assertFalse(p.events.any { it.startsWith("shuffle=") })
        assertFalse(p.shuffleModeEnabled)
    }

    @Test
    fun `shuffleQueueOrder 单曲只提示`() {
        val (m, p) = fixture(listOf(song("A")))
        m.shuffleQueueOrder(kotlin.random.Random(1))
        assertEquals(0, p.host.persistCount)
        assertEquals("队列只有一首，无需打乱", p.host.messages.last())
    }

    // ================================================================ 替身
"""
    # Need assertNotEquals import
    if "assertNotEquals" not in mt:
        mt = mt.replace(
            "import org.junit.Assert.assertFalse\n",
            "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\n",
        )
    mt = mt.replace(anchor, insert)
    write("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt", mt)
else:
    print("manager tests already patched")

print("tests patched")
