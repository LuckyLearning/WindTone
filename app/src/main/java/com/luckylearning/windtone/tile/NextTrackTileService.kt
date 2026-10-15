package com.luckylearning.windtone.tile

import android.content.ComponentName
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 快捷设置磁贴：下一首（批次 9.5-②）。
 *
 * ## 为什么是独立磁贴，而不是塞进 [PlaybackTileService]
 *
 * 一个 `TileService` 只有一个点击热区，`Tile` 本身也不支持附加动作按钮
 * （那是媒体通知/媒体卡才有的能力）。想在同一个磁贴上同时给出"播放暂停"和
 * "下一首"是做不到的，唯一可行的做法就是两个磁贴，由用户决定添加哪几个。
 *
 * ## 磁贴状态
 *
 * 副标题显示当前歌名 —— 这个磁贴只做一件事，标签「下一首」是固定的，
 * 把副标题留给"接下来会跳到哪儿"往上一点的信息（当前曲目），比重复写
 * "下一首"有用。
 */
class NextTrackTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val conn = withTimeoutOrNull(4000L) { connect() } ?: return@launch
            try {
                val c = conn.first
                if (c.mediaItemCount > 0) c.seekToNextMediaItem()
            } finally {
                runCatching { MediaController.releaseFuture(conn.second) }
                refreshTile()
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        scope.launch {
            val conn = withTimeoutOrNull(3000L) { connect() }
            if (conn == null) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat(getString(R.string.tile_idle))
                tile.updateTile()
                return@launch
            }
            try {
                val c = conn.first
                // 队列里只有一首时没有"下一首"可跳，置灰比点了没反应诚实
                val hasNext = c.mediaItemCount > 1
                tile.state = if (hasNext) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.tile_next)
                tile.setSubtitleCompat(
                    c.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.tile_empty)
                )
            } catch (_: Throwable) {
                tile.state = Tile.STATE_UNAVAILABLE
            } finally {
                runCatching { MediaController.releaseFuture(conn.second) }
                tile.updateTile()
            }
        }
    }

    private suspend fun connect(): Pair<MediaController, com.google.common.util.concurrent.ListenableFuture<MediaController>> {
        val deferred = CompletableDeferred<Pair<MediaController, com.google.common.util.concurrent.ListenableFuture<MediaController>>>()
        val token = SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java))
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        future.addListener(
            {
                try {
                    deferred.complete(future.get() to future)
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                }
            },
            MoreExecutors.directExecutor(),
        )
        return deferred.await()
    }
}
