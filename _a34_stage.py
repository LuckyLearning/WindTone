# -*- coding: utf-8 -*-
from pathlib import Path
import subprocess, time

ROOT = Path(r"E:\workspace\Android\WindTone")

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")

# --- QueuePoliciesTest ---
pt = read("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt")
if "shuffleOrderIndices" not in pt:
    if "import kotlin.random.Random" not in pt:
        pt = pt.replace(
            "import org.junit.Assert.assertEquals\n",
            "import org.junit.Assert.assertEquals\n"
            "import org.junit.Assert.assertNotEquals\n"
            "import org.junit.Assert.assertTrue\n"
            "import kotlin.random.Random\n",
        )
    # insert before final closing brace of class
    if not pt.rstrip().endswith("}"):
        raise SystemExit("policies unexpected end")
    # remove one trailing }
    body = pt.rstrip()
    if body.endswith("}"):
        body = body[:-1].rstrip() + "\n"
    body += """
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
"""
    write("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt", body)
else:
    print("policies tests present")

# --- QueueManagerTest ---
mt = read("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt")
if "clearQueue 清空" not in mt:
    if "assertNotEquals" not in mt:
        mt = mt.replace(
            "import org.junit.Assert.assertFalse\n",
            "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\n",
        )
    marker = "    // ================================================================ 替身"
    if marker not in mt:
        raise SystemExit("manager marker missing")
    insert = r'''
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
        p.events.clear()
        val beforeIds = p.items.map { it.mediaId }
        val alwaysZero = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        m.shuffleQueueOrder(alwaysZero)
        val afterIds = p.items.map { it.mediaId }
        assertEquals(4, afterIds.size)
        assertEquals(beforeIds.toSet(), afterIds.toSet())
        assertNotEquals(beforeIds, afterIds)
        assertEquals(song("B").stableKey, p.items[p.currentMediaItemIndex].mediaId)
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

'''
    mt = mt.replace(marker, insert + "\n" + marker)
    write("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt", mt)
else:
    print("manager tests present")

# Verify all critical content still on disk
checks = {
    "app/src/main/java/com/luckylearning/windtone/playback/QueueManager.kt": ["fun clearQueue", "fun shuffleQueueOrder"],
    "app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt": ["shuffleOrderIndices"],
    "app/src/main/java/com/luckylearning/windtone/playback/PlayerActions.kt": ["fun clearQueue()"],
    "app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt": ["override fun clearQueue()"],
    "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt": ["打乱顺序", "onClear"],
    "app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt": ["playerVm.clearQueue()"],
    "app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt": ["clearQueue 清空", "alwaysZero"],
    "app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt": ["shuffleOrderIndices 空表"],
    "docs/设计-队列清空与打乱.md": ["A3", "A4"],
}
for rel, keys in checks.items():
    t = read(rel)
    for k in keys:
        if k not in t:
            raise SystemExit(f"VERIFY FAIL {rel}: missing {k}")
    print("OK", rel)

# Stage immediately (do not touch SleepTimer / PlayerScreen parallel WIP)
files = [
    "app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt",
    "app/src/main/java/com/luckylearning/windtone/playback/QueueManager.kt",
    "app/src/main/java/com/luckylearning/windtone/playback/PlayerActions.kt",
    "app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt",
    "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt",
    "app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt",
    "app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt",
    "app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt",
    "docs/设计-队列清空与打乱.md",
    "docs/功能说明.md",
]
subprocess.check_call(["git", "add", "--"] + files, cwd=ROOT)
print("STAGED")
print(subprocess.check_output(["git", "diff", "--cached", "--stat"], cwd=ROOT, text=True))
