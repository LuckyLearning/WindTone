package com.luckylearning.windtone.tile

import android.content.ComponentName
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.PlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 快捷设置磁贴：收藏 / 取消收藏当前正在播放的曲（H1）。
 *
 * ## 为什么是独立磁贴
 *
 * 一个 [TileService] 只有一个点击热区；播放/暂停与下一首已各占一颗，
 * 「收藏当前曲」必须再拆一颗，由用户决定加不加。
 *
 * ## 状态语义
 *
 * - **无当前曲 / 无法识别身份**（空队列、在线直链未入库）：[Tile.STATE_UNAVAILABLE]
 * - **已收藏**：[Tile.STATE_ACTIVE]（系统会用强调色，相当于亮起的红心）
 * - **未收藏**：[Tile.STATE_INACTIVE]
 *
 * 副标题显示歌名；标签固定为「收藏」。落盘走 [com.luckylearning.windtone.data.FavoriteToggle]，
 * 与播放页红心同一套基础设施。
 */
class FavoriteTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private val favorites
        get() = (application as MusicApp).container.favorites

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
                val item = c.currentMediaItem ?: return@launch
                val mediaId = item.mediaId?.takeIf { it.isNotBlank() } ?: return@launch
                val title = item.mediaMetadata.title?.toString()
                favorites.toggleByMediaId(mediaId, title)
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
                tile.label = getString(R.string.tile_favorite)
                tile.setSubtitleCompat(getString(R.string.tile_idle))
                tile.updateTile()
                return@launch
            }
            try {
                val c = conn.first
                val item = c.currentMediaItem
                val mediaId = item?.mediaId?.takeIf { it.isNotBlank() }
                val title = item?.mediaMetadata?.title?.toString()
                    ?.takeIf { it.isNotBlank() }
                tile.label = getString(R.string.tile_favorite)
                if (mediaId == null || c.mediaItemCount == 0) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.setSubtitleCompat(getString(R.string.tile_empty))
                } else {
                    val fav = favorites.isFavorite(mediaId)
                    if (fav == null) {
                        // 认不出身份（例如纯在线直链）：灰掉，点了也切不了
                        tile.state = Tile.STATE_UNAVAILABLE
                        tile.setSubtitleCompat(title ?: getString(R.string.tile_favorite_unavailable))
                    } else {
                        tile.state = if (fav) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                        tile.setSubtitleCompat(
                            title ?: if (fav) {
                                getString(R.string.tile_favorite_on)
                            } else {
                                getString(R.string.tile_favorite_off)
                            },
                        )
                    }
                }
            } catch (_: Throwable) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat(getString(R.string.tile_idle))
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
