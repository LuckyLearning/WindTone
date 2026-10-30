# -*- coding: utf-8 -*-
"""A3+A4 full apply + commit on top of G5/G6 HEAD. One shot."""
from pathlib import Path
import re
import subprocess

ROOT = Path(r"E:\workspace\Android\WindTone")

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")
    print("wrote", rel, len(text))

# ========== QueuePolicies ==========
qp = read("app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt")
if "shuffleOrderIndices" not in qp:
    if not qp.rstrip().endswith("}"):
        raise SystemExit("qp end")
    qp = qp.rstrip()[:-1].rstrip() + """

    /**
     * A4：打乱**物理队列顺序**（不是打开随机播放模式）。
     *
     * @return `Pair(新下标序列, 原 currentIndex 在新序列中的位置)`。
     *  `新下标序列[i]` = 挪到新位置 i 的旧下标。空表返回 `([] , 0)`；单曲原样。
     */
    fun shuffleOrderIndices(
        size: Int,
        currentIndex: Int,
        random: kotlin.random.Random,
    ): Pair<List<Int>, Int> {
        if (size <= 0) return emptyList<Int>() to 0
        if (size == 1) return listOf(0) to 0
        val order = (0 until size).toMutableList()
        for (i in order.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        val safe = currentIndex.coerceIn(0, size - 1)
        return order to order.indexOf(safe)
    }
}
"""
    write("app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt", qp)

# ========== QueueManager ==========
qm = read("app/src/main/java/com/luckylearning/windtone/playback/QueueManager.kt")
if "fun clearQueue" not in qm:
    anchor = """    fun moveQueueItem(from: Int, to: Int) = act { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
            host.persistQueue()
            refreshQueue()
        }
    }
"""
    block = """
    /** A3：清空播放队列。空队列只提示。不改播放模式。 */
    fun clearQueue() = act { c ->
        if (c.mediaItemCount == 0) {
            host.postMessage("播放队列是空的")
            return@act
        }
        c.setMediaItems(emptyList(), 0, 0L)
        host.setCurrentQueue(emptyList())
        host.notifyQueueSize(0)
        host.persistQueue()
        refreshQueue()
        host.postMessage("已清空播放队列")
    }

    /**
     * A4：打乱队列物理顺序（Fisher–Yates），不打开 shuffleModeEnabled。
     * 当前曲跟到新下标并保留进度；原在播则继续播。
     */
    fun shuffleQueueOrder(random: kotlin.random.Random = kotlin.random.Random.Default) = act { c ->
        val n = c.mediaItemCount
        if (n == 0) {
            host.postMessage("播放队列是空的")
            return@act
        }
        if (n == 1) {
            host.postMessage("队列只有一首，无需打乱")
            return@act
        }
        val items = (0 until n).map { c.getMediaItemAt(it) }
        val (order, newIndex) = QueuePolicies.shuffleOrderIndices(n, c.currentMediaItemIndex, random)
        val newItems = order.map { items[it] }
        val pos = c.currentPosition.coerceAtLeast(0L)
        val wasPlaying = c.isPlaying
        c.setMediaItems(newItems, newIndex, pos)
        c.prepare()
        if (wasPlaying) c.play()
        host.persistQueue()
        refreshQueue()
        host.postMessage("已打乱队列顺序")
    }

"""
    if anchor not in qm:
        raise SystemExit("QM anchor missing")
    write("app/src/main/java/com/luckylearning/windtone/playback/QueueManager.kt", qm.replace(anchor, anchor + "\n" + block))

# ========== PlayerActions ==========
pa = read("app/src/main/java/com/luckylearning/windtone/playback/PlayerActions.kt")
if "fun clearQueue()" not in pa:
    pa = pa.replace(
        "    fun moveQueueItem(from: Int, to: Int)\n",
        "    fun moveQueueItem(from: Int, to: Int)\n"
        "    /** A3：清空播放队列 */\n"
        "    fun clearQueue()\n"
        "    /** A4：打乱队列物理顺序（非随机播放模式） */\n"
        "    fun shuffleQueueOrder()\n",
    )
    write("app/src/main/java/com/luckylearning/windtone/playback/PlayerActions.kt", pa)

# ========== PlaybackEngine ==========
pe = read("app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt")
if "override fun clearQueue()" not in pe:
    pe = pe.replace(
        "    override fun moveQueueItem(from: Int, to: Int) = queueManager.moveQueueItem(from, to)\n",
        "    override fun moveQueueItem(from: Int, to: Int) = queueManager.moveQueueItem(from, to)\n"
        "    override fun clearQueue() = queueManager.clearQueue()\n"
        "    override fun shuffleQueueOrder() = queueManager.shuffleQueueOrder()\n",
    )
    write("app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt", pe)

# ========== QueueSheet in SheetsPlayback (keep G5 SleepSheet) ==========
NEW_QS = r'''@Composable
fun QueueSheet(
    queue: List<QueueEntry>,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveAsPlaylist: () -> Unit = {},
    onClear: () -> Unit = {},
    onShuffleOrder: () -> Unit = {},
) {
    // A3 清空要二次确认，避免车机误触一把清空
    var confirmClear by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = glassContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(AppIcons.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "播放队列 · ${queue.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            // A3/A4：清空 / 打乱顺序（改物理序，不是随机播放模式）+ 原有「存为歌单」
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onShuffleOrder()
                    },
                    enabled = queue.size > 1,
                ) { Text("打乱顺序") }
                if (confirmClear) {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                    TextButton(
                        onClick = {
                            confirmClear = false
                            onClear()
                        },
                        enabled = queue.isNotEmpty(),
                    ) {
                        Text("确认清空", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = queue.isNotEmpty(),
                    ) { Text("清空队列") }
                }
                TextButton(
                    onClick = onSaveAsPlaylist,
                    enabled = queue.isNotEmpty(),
                ) { Text("存为歌单") }
            }
            Spacer(Modifier.height(8.dp))
'''

sp = read("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt")
# Replace from @Composable\nfun QueueSheet through the Spacer after header (before LazyColumn)
m = re.search(
    r"@Composable\r?\nfun QueueSheet\(.*?Spacer\(Modifier\.height\(8\.dp\)\)\r?\n",
    sp,
    re.S,
)
if not m:
    raise SystemExit("QueueSheet header not found in SheetsPlayback")
if "onClear" in m.group(0) and "打乱顺序" in m.group(0):
    print("QueueSheet already patched")
else:
    sp = sp[:m.start()] + NEW_QS + sp[m.end():]
    write("app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt", sp)

# ========== MainScreen ==========
ms = read("app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt")
if "playerVm.clearQueue()" not in ms:
    old = """                QueueSheet(
                    queue = player.queue,
                    onClose = { showQueue = false },
                    onJump = { playerVm.jumpToQueue(it) },
                    onRemove = { playerVm.removeQueueItem(it) },
                    onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                    onSaveAsPlaylist = {
                        showQueue = false
                        saveQueueAsPlaylist = true
                    },
                )
"""
    new = """                QueueSheet(
                    queue = player.queue,
                    onClose = { showQueue = false },
                    onJump = { playerVm.jumpToQueue(it) },
                    onRemove = { playerVm.removeQueueItem(it) },
                    onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                    onSaveAsPlaylist = {
                        showQueue = false
                        saveQueueAsPlaylist = true
                    },
                    onClear = { playerVm.clearQueue() },
                    onShuffleOrder = { playerVm.shuffleQueueOrder() },
                )
"""
    if old not in ms:
        raise SystemExit("MainScreen QS block missing")
    write("app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt", ms.replace(old, new))

# ========== Tests ==========
pt = read("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt")
if "shuffleOrderIndices 空表" not in pt:
    if "import kotlin.random.Random" not in pt:
        pt = pt.replace(
            "import org.junit.Assert.assertEquals\n",
            "import org.junit.Assert.assertEquals\n"
            "import org.junit.Assert.assertNotEquals\n"
            "import org.junit.Assert.assertTrue\n"
            "import kotlin.random.Random\n",
        )
    pt = pt.rstrip()
    if pt.endswith("}"):
        pt = pt[:-1].rstrip() + "\n"
    pt += """
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
        val (order, cur) = QueuePolicies.shuffleOrderIndices(5, 2, Random(42))
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
    write("app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt", pt)

mt = read("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt")
if "clearQueue 清空" not in mt:
    if "assertNotEquals" not in mt:
        mt = mt.replace(
            "import org.junit.Assert.assertFalse\n",
            "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\n",
        )
    marker = "    // ================================================================ 替身"
    insert = """
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

"""
    if marker not in mt:
        raise SystemExit("manager test marker missing")
    write("app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt", mt.replace(marker, insert + marker))

# ========== docs ==========
doc = ROOT / "docs/设计-队列清空与打乱.md"
if not doc.exists():
    doc.write_text("""---
layout: default
title: 设计 - 队列清空与打乱顺序
---

# 队列抽屉：清空队列 + 打乱顺序（A3 / A4）

**状态**：已落地（本机 `main`）  
**日期**：2026-10-31（Asia/Shanghai）

## 干了什么

在播放队列抽屉（`QueueSheet`）一次补两个动作：

| 编号 | 按钮 | 行为 |
| --- | --- | --- |
| **A3** | 清空队列 | 二次确认后清空整份播放队列并落库；空队列只提示 |
| **A4** | 打乱顺序 | **改物理队列序**（Fisher–Yates），**不**打开/关闭「随机播放」模式；当前曲跟到新下标并尽量保留进度 |

与「播放模式 → 随机」的区别：随机模式靠 Media3 `shuffleModeEnabled`；A4 是重排列表本身，模式开关不动。

## 入口与交互

- 播放页 → 播放队列抽屉 → 标题下方操作行（与「存为歌单」同行）
- **打乱顺序**：≥2 首可点
- **清空队列**：先「清空队列」→「取消 / 确认清空」（红色）；防车机误触

## 怎么干的

| 文件 | 职责 |
| --- | --- |
| `playback/QueuePolicies.kt` | `shuffleOrderIndices` 纯函数 |
| `playback/QueueManager.kt` | `clearQueue` / `shuffleQueueOrder` |
| `PlayerActions` + `PlaybackEngine` | 动作面 |
| `ui/SheetsPlayback.kt` | `QueueSheet` UI + 二次确认 |
| `ui/MainScreen.kt` | 接线 |
| `QueuePoliciesTest` / `QueueManagerTest` | 单测 |

文案暂硬编码中文，错开并行 i18n。

## 明确不做

- 不做成「一键开随机播放模式」
- 不做拖拽排序
- 本项不抽 i18n
""", encoding="utf-8", newline="\n")
    print("wrote design doc")

feat = read("docs/功能说明.md")
m = re.search(r"- \*\*播放队列抽屉\*\*：[^\n]+", feat)
if m and "打乱顺序" not in m.group(0):
    feat = feat[:m.start()] + "- **播放队列抽屉**：查看队列、点击跳转、上移 / 下移排序、移除单曲、**打乱顺序**（改物理序，非随机模式）、**清空队列**（二次确认）、存为歌单" + feat[m.end():]
    write("docs/功能说明.md", feat)

# ========== VERIFY then STAGE + COMMIT ==========
checks = {
    "app/src/main/java/com/luckylearning/windtone/playback/QueueManager.kt": ["fun clearQueue", "fun shuffleQueueOrder"],
    "app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt": ["shuffleOrderIndices"],
    "app/src/main/java/com/luckylearning/windtone/playback/PlayerActions.kt": ["fun clearQueue()"],
    "app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt": ["override fun clearQueue()"],
    "app/src/main/java/com/luckylearning/windtone/ui/SheetsPlayback.kt": ["打乱顺序", "onClear", "自定义"],
    "app/src/main/java/com/luckylearning/windtone/ui/MainScreen.kt": ["playerVm.clearQueue()"],
    "app/src/test/java/com/luckylearning/windtone/playback/QueueManagerTest.kt": ["clearQueue 清空", "alwaysZero"],
    "app/src/test/java/com/luckylearning/windtone/playback/QueuePoliciesTest.kt": ["shuffleOrderIndices 空表"],
}
for rel, keys in checks.items():
    t = read(rel)
    for k in keys:
        if k not in t:
            raise SystemExit(f"VERIFY FAIL {rel}: {k}")
    print("OK", rel)

files = list(checks.keys()) + ["docs/设计-队列清空与打乱.md", "docs/功能说明.md"]
subprocess.check_call(["git", "add", "--"] + files, cwd=ROOT)
# commit with UTF-8 message via file
msg = ROOT / "_a34_commit_msg.txt"
msg.write_text(
    "feat(queue): A3 清空队列 + A4 打乱顺序（物理序，非随机模式）\n\n"
    "- QueueSheet：打乱顺序 / 清空（二次确认）/ 存为歌单\n"
    "- QueuePolicies.shuffleOrderIndices + QueueManager.clearQueue/shuffleQueueOrder\n"
    "- 不改 shuffleModeEnabled；单测覆盖；docs/设计-队列清空与打乱.md\n",
    encoding="utf-8",
)
subprocess.check_call(["git", "commit", "-F", str(msg)], cwd=ROOT)
print("COMMITTED")
print(subprocess.check_output(["git", "log", "-1", "--oneline"], cwd=ROOT, text=True))
# re-verify after commit
for rel, keys in checks.items():
    t = read(rel)
    for k in keys:
        if k not in t:
            raise SystemExit(f"POST-COMMIT LOST {rel}: {k}")
print("POST-COMMIT OK")
