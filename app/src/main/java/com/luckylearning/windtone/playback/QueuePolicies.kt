package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.model.Song

/**
 * 队列操作的**纯决策**：不碰 `MediaController`，只算「插哪儿 / 怎么去重」。
 *
 * 从 [PlaybackEngine] 的 `playNext` / `enqueue` 里抠出来的可测内核——这些边界
 * （当前项下一项、已在队列则改移动而非重复插入、批量去重）最容易在连点 / 多选时
 * 出重复条目或插错位置，而它们本身与播放器状态无关，单测就能钉死。
 */
object QueuePolicies {

    /** `playNext` 的插入位：当前项下一项；队列空则 0。 */
    fun playNextInsertIndex(currentIndex: Int, count: Int): Int =
        if (count == 0) 0 else (currentIndex + 1).coerceAtMost(count)

    /**
     * 若歌曲已在队列，返回「移动到目标位」的落点：
     * 已有位在插入位之前时，前面的项被挤后，目标位要回退一位，否则会插到自己后面。
     */
    fun playNextMoveTarget(exists: Int, insertAt: Int): Int =
        if (exists < insertAt) insertAt - 1 else insertAt

    /** `enqueue` 批量去重：剔除已经在队列里的歌，避免连点出现重复条目。 */
    fun enqueueNewSongs(songs: List<Song>, existingMediaIds: Set<String>): List<Song> =
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
