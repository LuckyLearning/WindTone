from pathlib import Path
p = Path("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt")
t = p.read_text(encoding="utf-8")
old = """        p.shuffleModeEnabled = false
        val beforeIds = p.items.map { it.mediaId }
        m.shuffleQueueOrder(kotlin.random.Random(99))
        val afterIds = p.items.map { it.mediaId }
        assertEquals(4, afterIds.size)
        assertEquals(beforeIds.toSet(), afterIds.toSet())
        assertNotEquals(beforeIds, afterIds) // 种子 99 已核对会变序
        // 当前曲 B 仍在，下标已更新
        assertEquals("B".let { song(it).stableKey }, p.items[p.currentMediaItemIndex].mediaId)
"""
new = """        p.shuffleModeEnabled = false
        p.events.clear() // 丢掉 setup 写入的 shuffle=false，只断言打乱动作本身
        val beforeIds = p.items.map { it.mediaId }
        // nextBits=0 → Fisher–Yates 每次与 0 交换，结果确定且非原序
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
    # try already partially fixed
    if "alwaysZero" in t and "events.clear" in t:
        print("already fixed")
    else:
        # show snippet
        i = t.find("shuffleQueueOrder 打乱")
        print(repr(t[i:i+600]))
        raise SystemExit("old block missing")
else:
    p.write_text(t.replace(old, new), encoding="utf-8", newline="\n")
    print("fixed shuffle test")
