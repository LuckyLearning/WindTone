package com.luckylearning.windtone.playback

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.luckylearning.windtone.state.QueueEntry

/**
 * 播放队列操作所需的 [MediaController] 子集。
 *
 * 把 Media3 `Player` 上「队列增删 / 跳转 / 模式」这一小撮成员收口成一个窄接口，
 * 让 [QueueManager] 只依赖它——既屏蔽了 `Player` 上几十个用不到的方法，
 * 也让我们在单测里用一个轻量替身（[FakeQueuePlayer]）就能完全驱动队列逻辑，
 * 不必真去连一个 Media3 会话。
 */
interface QueuePlayer {
    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    var repeatMode: Int
    var shuffleModeEnabled: Boolean

    fun getMediaItemAt(index: Int): MediaItem
    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun seekToDefaultPosition(index: Int)
    fun moveMediaItem(from: Int, to: Int)
    fun addMediaItem(index: Int, item: MediaItem)
    fun addMediaItems(items: List<MediaItem>)
    fun removeMediaItem(index: Int)
}

/** [QueuePlayer] 的 MediaController 实现：每个成员直接转发给底层会话。 */
class MediaControllerQueuePlayer(private val c: MediaController) : QueuePlayer {
    override val mediaItemCount: Int get() = c.mediaItemCount
    override val currentMediaItemIndex: Int get() = c.currentMediaItemIndex
    override val currentPosition: Long get() = c.currentPosition
    override val duration: Long get() = c.duration
    override val isPlaying: Boolean get() = c.isPlaying
    override var repeatMode: Int
        get() = c.repeatMode
        set(value) { c.repeatMode = value }
    override var shuffleModeEnabled: Boolean
        get() = c.shuffleModeEnabled
        set(value) { c.shuffleModeEnabled = value }

    override fun getMediaItemAt(index: Int): MediaItem = c.getMediaItemAt(index)
    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        c.setMediaItems(items, startIndex, startPositionMs)
    override fun prepare() = c.prepare()
    override fun play() = c.play()
    override fun pause() = c.pause()
    override fun seekTo(ms: Long) = c.seekTo(ms)
    override fun seekToNextMediaItem() = c.seekToNextMediaItem()
    override fun seekToPreviousMediaItem() = c.seekToPreviousMediaItem()
    override fun seekToDefaultPosition(index: Int) = c.seekToDefaultPosition(index)
    override fun moveMediaItem(from: Int, to: Int) = c.moveMediaItem(from, to)
    override fun addMediaItem(index: Int, item: MediaItem) = c.addMediaItem(index, item)
    override fun addMediaItems(items: List<MediaItem>) = c.addMediaItems(items)
    override fun removeMediaItem(index: Int) = c.removeMediaItem(index)
}

/** 把 [QueuePlayer] 的当前内容读成队列抽屉用的 [QueueEntry] 列表（纯函数）。 */
object QueueEntries {
    fun read(player: QueuePlayer, currentIndex: Int): List<QueueEntry> {
        val count = player.mediaItemCount
        val entries = ArrayList<QueueEntry>(count)
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            entries.add(
                QueueEntry(
                    index = i,
                    mediaId = item.mediaId,
                    title = item.mediaMetadata.title?.toString() ?: "",
                    artist = item.mediaMetadata.artist?.toString() ?: "",
                    isCurrent = i == currentIndex,
                )
            )
        }
        return entries
    }
}
