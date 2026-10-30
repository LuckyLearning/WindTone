# -*- coding: utf-8 -*-
from pathlib import Path
p = Path("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt")
t = p.read_text(encoding="utf-8")
old = """        m.shuffleQueueOrder(kotlin.random.Random(99))
        val afterIds = p.items.map { it.mediaId }
        assertEquals(4, afterIds.size)
        assertEquals(beforeIds.toSet(), afterIds.toSet())
        assertNotEquals(beforeIds, afterIds) // 种子 99 已核对会变序
        // 当前曲 B 仍在，下标已更新
        assertEquals(\"B\".let { song(it).stableKey }, p.items[p.currentMediaItemIndex].mediaId)
"""
# file has real quotes not escaped
old = """        m.shuffleQueueOrder(kotlin.random.Random(99))
        val afterIds = p.items.map { it.mediaId }
        assertEquals(4, afterIds.size)
        assertEquals(beforeIds.toSet(), afterIds.toSet())
        assertNotEquals(beforeIds, afterIds) // 种子 99 已核对会变序
        // 当前曲 B 仍在，下标已更新
        assertEquals("B".let { song(it).stableKey }, p.items[p.currentMediaItemIndex].mediaId)
"""
new = """        // nextBits=0 → Fisher–Yates 每次与 0 交换，结果确定且非原序
        val alwaysZero = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        m.shuffleQueueOrder(alwaysZero)
        val afterIds = p.items.map { it.mediaId }
        assertEquals(4, afterIds.size)
        assertEquals(beforeIds.toSet(), afterIds.toSet())
        assertNotEquals(beforeIds, afterIds)
        assertEquals(song("B").stableKey, p.items[p.currentMediaItemIndex].mediaId)
"""
if old not in t:
    raise SystemExit("old block missing")
p.write_text(t.replace(old, new), encoding="utf-8", newline="\n")
print("ok")
