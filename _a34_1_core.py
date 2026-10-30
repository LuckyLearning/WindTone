# -*- coding: utf-8 -*-
from pathlib import Path
ROOT = Path(r"E:\workspace\Android\WindTone")

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8", newline="\n")
    print("wrote", rel)

qp = read("app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt")
if "shuffleOrderIndices" not in qp:
    needle = "    fun enqueueNewSongs(songs: List<Song>, existingMediaIds: Set<String>): List<Song> =\n        songs.filterNot { it.stableKey in existingMediaIds }\n}\n"
    insert = """    fun enqueueNewSongs(songs: List<Song>, existingMediaIds: Set<String>): List<Song> =
        songs.filterNot { it.stableKey in existingMediaIds }

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
    if needle not in qp:
        raise SystemExit("QueuePolicies needle missing")
    write("app/src/main/java/com/luckylearning/windtone/playback/QueuePolicies.kt", qp.replace(needle, insert))
else:
    print("QueuePolicies ok")

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
    /**
     * A3：清空播放队列。停在空队列；不改播放模式。
     * 空队列时只提示，不反复落库。
     */
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
     * A4：打乱队列**物理顺序**（Fisher–Yates），**不**打开 `shuffleModeEnabled`。
     * 当前曲跟到新位置并尽量保留进度；正在播则继续播。
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
        raise SystemExit("QueueManager anchor missing")
    write("app/src/main/java/com/luckylearning/windtone/playback/QueueManager.kt", qm.replace(anchor, anchor + "\n" + block))
else:
    print("QueueManager ok")

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
else:
    print("PlayerActions ok")

pe = read("app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt")
if "override fun clearQueue()" not in pe:
    pe = pe.replace(
        "    override fun moveQueueItem(from: Int, to: Int) = queueManager.moveQueueItem(from, to)\n",
        "    override fun moveQueueItem(from: Int, to: Int) = queueManager.moveQueueItem(from, to)\n"
        "    override fun clearQueue() = queueManager.clearQueue()\n"
        "    override fun shuffleQueueOrder() = queueManager.shuffleQueueOrder()\n",
    )
    write("app/src/main/java/com/luckylearning/windtone/playback/PlaybackEngine.kt", pe)
else:
    print("PlaybackEngine ok")

print("core ok")
