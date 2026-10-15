package com.luckylearning.windtone.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.luckylearning.windtone.data.model.Song
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred

/**
 * 与 PlaybackService 的 MediaController 连接（播放状态与 UI 的唯一桥梁）。
 */
class PlaybackConnection(private val context: Context) {

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val lock = Any()

    @Volatile
    var controller: MediaController? = null
        private set

    suspend fun connect(): MediaController {
        controller?.let { return it }
        val deferred = CompletableDeferred<MediaController>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        synchronized(lock) { controllerFuture = future }
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    deferred.complete(c)
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                }
            },
            MoreExecutors.directExecutor(),
        )
        return deferred.await()
    }

    fun release() {
        synchronized(lock) {
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null
        }
        controller = null
    }

    companion object {

        /** 把 Song 转换为 Media3 播放项 */
        fun toMediaItem(song: Song, artwork: ByteArray? = null): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artistOrUnknown)
                .setAlbumTitle(song.albumOrUnknown)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .apply { artwork?.let { setArtworkData(it, null) } }
                .build()

            return MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.stableKey)
                .setMediaMetadata(metadata)
                .build()
        }

        /** 用同一首歌的实例重建播放项（替换封面用，不改变播放源） */
        fun withArtwork(item: MediaItem, artwork: ByteArray?): MediaItem {
            if (artwork == null || artwork.isEmpty()) return item
            val old = item.mediaMetadata
            val newMeta = old.buildUpon()
                .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            return item.buildUpon().setMediaMetadata(newMeta).build()
        }
    }
}
