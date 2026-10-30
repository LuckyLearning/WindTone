package com.luckylearning.windtone.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.QueueEntry
import com.luckylearning.windtone.state.playModeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 播放队列的所有增删 / 跳转 / 乱序 / 模式操作。
 *
 * 与 [PlaybackEngine] 彻底解耦：它只认一个窄接口 [QueuePlayer]（由 [MediaControllerQueuePlayer]
 * 适配 MediaController），所有「改完队列之后要同步的界面状态 / 落库 / 提示」都通过 [QueueHost]
 * 回调交回引擎。因此本类不持有任何 Android / 引擎状态，可以完全用 [QueuePlayer] 的替身单测。
 *
 * 之前这些逻辑散落在 [PlaybackEngine] 里，每个方法都重复一遍
 * `scope.launch { val c = ensureController() ?: return@launch ... }` 的取控制器外壳；
 * 这里统一收到 [act] 里，引擎侧只剩一行委托。
 */
class QueueManager(
    private val scope: CoroutineScope,
    private val connect: suspend () -> QueuePlayer?,
    private val toMediaItem: (Song) -> MediaItem,
    private val host: QueueHost,
) {

    /** 把「拿控制器 → 空就放弃」的统一外壳收口，避免每个方法重复 launch / 判空 */
    private fun act(block: suspend (QueuePlayer) -> Unit) {
        scope.launch { connect()?.let { block(it) } }
    }

    // ---------------------------------------------------------------- 起播

    fun playQueue(queue: List<Song>, startIndex: Int) = act { startQueue(it, queue, startIndex, false) }

    /** 随机播放一整批歌：先切到「随机 + 列表循环」再起播（见原 [PlaybackEngine.playShuffled] 注释） */
    fun playShuffled(songs: List<Song>) = act { startQueue(it, songs, 0, true) }

    private suspend fun startQueue(
        c: QueuePlayer,
        queue: List<Song>,
        startIndex: Int,
        shuffled: Boolean,
    ) {
        if (queue.isEmpty()) return
        // 先落定模式再起播：两个协程谁先跑完不确定，否则用户会听到半随机状态。
        if (shuffled) {
            c.repeatMode = Player.REPEAT_MODE_ALL
            c.shuffleModeEnabled = true
        }
        val i = startIndex.coerceIn(0, queue.size - 1)
        host.setCurrentQueue(queue)
        c.setMediaItems(queue.map { toMediaItem(it) }, i, 0L)
        c.prepare()
        c.play()
        host.applyPlaybackParams()
        host.onPlaybackStarted(i, queue[i].durationMs, queue.size)
        host.persistQueue()
    }

    // ---------------------------------------------------------------- 播放控制

    fun togglePlay() = act { c ->
        if (c.mediaItemCount == 0) {
            val list = host.currentLibrary()
            if (list.isNotEmpty()) host.startLibraryPlayback()
            return@act
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = act { it.seekToNextMediaItem() }

    fun previous() = act { c ->
        // 前五秒回上一首，否则回到本曲开头（与系统媒体通知一致）
        if (c.currentPosition > 3000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = act { c ->
        val target = ms.coerceIn(0L, c.duration.coerceAtLeast(0L))
        c.seekTo(target)
        host.updateProgress(target)
    }

    // ---------------------------------------------------------------- 模式

    /**
     * 播放模式轮换：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序。
     * 接线顺序刻意保留：关随机要先于设循环、开随机要在 repeatMode 之后，否则会闪过中间态。
     */
    fun cyclePlayMode() = act { c ->
        val (repeatMode, shuffle) = playModeOf(c.repeatMode, c.shuffleModeEnabled).next.targets()
        if (shuffle) {
            c.repeatMode = repeatMode
            c.shuffleModeEnabled = true
        } else {
            c.shuffleModeEnabled = false
            c.repeatMode = repeatMode
        }
    }

    // ---------------------------------------------------------------- 入队

    fun playNext(song: Song) = act { c ->
        val insertAt = QueuePolicies.playNextInsertIndex(c.currentMediaItemIndex, c.mediaItemCount)
        val exists = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == song.stableKey }
        if (exists != null) {
            c.moveMediaItem(exists, QueuePolicies.playNextMoveTarget(exists, insertAt))
        } else {
            c.addMediaItem(insertAt, toMediaItem(song))
        }
        host.notifyQueueSize(c.mediaItemCount)
        host.persistQueue()
        host.postMessage("已加入播放队列：${song.title}")
    }

    /**
     * 批量追加到播放队列：已在队列里的歌跳过（避免连点出现重复条目）。
     * 纯去重决策见 [QueuePolicies.enqueueNewSongs]。
     */
    fun enqueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        act { c ->
            val existing = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val fresh = QueuePolicies.enqueueNewSongs(songs, existing)
            if (fresh.isEmpty()) {
                host.postMessage("所选歌曲都在播放队列里了")
                return@act
            }
            c.addMediaItems(fresh.map { toMediaItem(it) })
            host.persistQueue()
            refreshQueue()
        }
    }

    // ---------------------------------------------------------------- 队列抽屉

    fun refreshQueue() = act { c ->
        host.notifyQueueEntries(QueueEntries.read(c, c.currentMediaItemIndex))
    }

    fun jumpToQueue(index: Int) = act { c ->
        c.seekToDefaultPosition(index)
        c.play()
        host.persistQueue()
    }

    fun removeQueueItem(index: Int) = act { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
        host.persistQueue()
        refreshQueue()
    }

    fun moveQueueItem(from: Int, to: Int) = act { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
            host.persistQueue()
            refreshQueue()
        }
    }


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


    /** 引擎侧必须实现的窄回调：本类不持有任何界面 / 落库状态 */
    interface QueueHost {
        fun setCurrentQueue(queue: List<Song>)
        fun notifyQueueSize(size: Int)
        fun notifyQueueEntries(entries: List<QueueEntry>)
        fun updateProgress(positionMs: Long)
        fun postMessage(text: String)
        fun persistQueue()
        fun applyPlaybackParams()
        fun onPlaybackStarted(currentIndex: Int, durationMs: Long, queueSize: Int)
        fun currentLibrary(): List<Song>
        fun startLibraryPlayback()
    }
}
