package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.model.Song

/**
 * 断点续播的**决策结果**（纯数据，不含副作用）。
 *
 * 从 `PlaybackEngine.restoreQueue` 抽出：恢复逻辑里最容易错的是「什么条件下该恢复、
 * 恢复哪一段、当前曲下标怎么定」，而真正碰 `MediaController` 的动作只有最后几步。
 * 把决策独立出来后，这段分支可以在 JVM 单测里穷举（曲库空 / 无播放态 / 歌已删 /
 * 队列部分失效 / 正常恢复），不必拉起播放器。
 */
internal sealed interface RestorePlan {

    /** 曲库还没就绪（冷启动竞速中），这一次先不恢复，等下一次调用 */
    data object WaitForLibrary : RestorePlan

    /** 没有任何播放态可恢复（首次安装 / 状态被清空） */
    data object NothingToRestore : RestorePlan

    /** 上次的当前曲已不在曲库（被删 / 被移出），不必再重试 */
    data object StaleState : RestorePlan

    /**
     * 可以恢复。
     *
     * @param songs      要摆进播放器的队列
     * @param index      当前曲在 [songs] 中的下标（已夹取到合法范围）
     * @param positionMs 恢复到的播放位置
     * @param state      上次的播放态（循环 / 随机），可能为 null
     * @param fromQueue  是否来自持久化队列（false = 退化成「整库当队列」的单曲恢复）
     */
    data class Restore(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        val state: PlayerStateEntity?,
        val fromQueue: Boolean,
    ) : RestorePlan
}

/**
 * 纯决策：给定曲库快照、上次队列主键与播放态，算出该怎么恢复。
 *
 * 优先级：
 *  1. 持久化队列能映射出歌 → 按原顺序恢复（当前曲用 `currentSongId` 定位）；
 *  2. 队列不可用但记得当前曲 → 整库当队列，定位到那首；
 *  3. 当前曲也已不在曲库 → [RestorePlan.StaleState]，不再重试。
 */
internal fun planRestore(
    librarySongs: List<Song>,
    queueIds: List<Long>,
    state: PlayerStateEntity?,
): RestorePlan {
    if (librarySongs.isEmpty()) return RestorePlan.WaitForLibrary
    val position = (state?.positionMs ?: 0L).coerceAtLeast(0L)

    // 1) 优先整队列恢复：队列里可能有已删除的歌，映射不到的直接跳过
    if (queueIds.isNotEmpty()) {
        val byId = librarySongs.associateBy { it.dbId }
        val restored = queueIds.mapNotNull { byId[it] }
        if (restored.isNotEmpty()) {
            val index = restored.indexOfFirst { it.dbId == state?.currentSongId }
                .takeIf { it >= 0 } ?: 0
            return RestorePlan.Restore(restored, index, position, state, fromQueue = true)
        }
    }

    // 2) 退化为单曲恢复（只记得当前曲）：整库当队列
    val currentId = state?.currentSongId ?: return RestorePlan.NothingToRestore
    val idx = librarySongs.indexOfFirst { it.dbId == currentId }
    if (idx < 0) return RestorePlan.StaleState
    return RestorePlan.Restore(librarySongs, idx, position, state, fromQueue = false)
}
