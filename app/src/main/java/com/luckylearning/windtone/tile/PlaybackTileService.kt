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
 * 快捷设置磁贴：下拉通知面板一键播放/暂停。
 */
class PlaybackTileService : TileService() {

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
                if (c.isPlaying) c.pause() else c.play()
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
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
                return@launch
            }
            try {
                val c = conn.first
                val playing = c.isPlaying
                tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: getString(R.string.app_name)
                tile.subtitle = if (playing) "正在播放" else "已暂停"
            } catch (_: Throwable) {
                tile.state = Tile.STATE_INACTIVE
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
