package com.luckylearning.windtone.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.PlaybackService
import com.luckylearning.windtone.ui.AppShortcut
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 快捷设置磁贴：下拉通知面板一键播放 / 暂停。
 *
 * 标签显示歌名、副标题显示歌手（而不是固定写"正在播放/已暂停"）——
 * 磁贴是"瞥一眼"的场景，副标题用来重复状态词还不如用来告诉用户放的是哪首。
 *
 * ⚠️ 一个 `TileService` 只能有一个点击动作，装不下「下一首」和「收藏当前曲」。
 * 「下一首」拆成了独立的 [NextTrackTileService]（用户想加就加）；「收藏」没有做，
 * 原因见那个文件头部。
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
                if (c.mediaItemCount == 0) {
                    // 队列是空的：`play()` 会静默什么都不做，用户以为磁贴坏了。
                    // 拉起应用并走「播放全部」（曲库装载完后由 MainScreen 执行）。
                    openApp(AppShortcut.PLAY_ALL)
                } else if (c.isPlaying) {
                    c.pause()
                } else {
                    c.play()
                }
            } finally {
                runCatching { MediaController.releaseFuture(conn.second) }
                refreshTile()
            }
        }
    }

    /**
     * 把"磁贴点了但没队列"变成一次可见的动作，而不是静默失败。
     *
     * `startActivityAndCollapse(Intent)` 在 API 34 起被弃用（改成收 `PendingIntent`），
     * 所以这里按版本分两支。lint 的 `StartActivityAndCollapseDeprecated` 是**预期内**的告警：
     * 在 34 以下那条分支里，收 `Intent` 的重载仍然是唯一可用的，不能为了消警告把
     * minSdk 24 的用户交给一个在旧系统上根本不存在的方法。
     */
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun openApp(entry: String) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(AppShortcut.ACTION)
            .putExtra(AppShortcut.EXTRA, entry)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 31, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        scope.launch {
            val conn = withTimeoutOrNull(3000L) { connect() }
            if (conn == null) {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.setSubtitleCompat(getString(R.string.tile_idle))
                tile.updateTile()
                return@launch
            }
            try {
                val c = conn.first
                val md = c.currentMediaItem?.mediaMetadata
                tile.state = if (c.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = md?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.tile_label)
                val artist = md?.artist?.toString().orEmpty()
                tile.setSubtitleCompat(
                    when {
                        c.mediaItemCount == 0 -> getString(R.string.tile_empty)
                        artist.isNotBlank() -> artist
                        c.isPlaying -> getString(R.string.tile_playing)
                        else -> getString(R.string.tile_paused)
                    }
                )
            } catch (_: Throwable) {
                tile.state = Tile.STATE_INACTIVE
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
