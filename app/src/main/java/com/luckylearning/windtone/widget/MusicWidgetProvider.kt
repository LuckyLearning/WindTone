package com.luckylearning.windtone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.R
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桌面小部件：显示当前曲目 + 播放控制。
 * 播放状态变化由 [push]（服务端调用）刷新；按钮通过 MediaController 下发指令。
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) manager.updateAppWidget(id, buildViews(context, WidgetState.current))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> sendPlayerCommand(context) { c ->
                if (c.isPlaying) c.pause() else c.play()
            }
            ACTION_NEXT -> sendPlayerCommand(context) { it.seekToNextMediaItem() }
            ACTION_PREVIOUS -> sendPlayerCommand(context) { it.seekToPreviousMediaItem() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.luckylearning.windtone.widget.TOGGLE"
        const val ACTION_NEXT = "com.luckylearning.windtone.widget.NEXT"
        const val ACTION_PREVIOUS = "com.luckylearning.windtone.widget.PREVIOUS"

        /** 服务端推送状态（主线程调用） */
        fun push(context: Context, title: String, artist: String, playing: Boolean, cover: Bitmap?) {
            WidgetState.current = WidgetState.Snapshot(title, artist, playing, cover)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            val views = buildViews(context, WidgetState.current)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun buildViews(context: Context, snap: WidgetState.Snapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            views.setTextViewText(R.id.widget_title, snap.title.ifEmpty { "未在播放" })
            views.setTextViewText(R.id.widget_artist, snap.artist)
            if (snap.cover != null) {
                views.setImageViewBitmap(R.id.widget_cover, snap.cover)
            } else {
                views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_music)
            }
            views.setImageViewResource(
                R.id.widget_toggle,
                if (snap.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_info, openApp)

            fun btn(action: String, requestCode: Int, viewId: Int) {
                val pi = PendingIntent.getBroadcast(
                    context, requestCode,
                    Intent(context, MusicWidgetProvider::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                views.setOnClickPendingIntent(viewId, pi)
            }
            btn(ACTION_PREVIOUS, 11, R.id.widget_prev)
            btn(ACTION_TOGGLE, 12, R.id.widget_toggle)
            btn(ACTION_NEXT, 13, R.id.widget_next)
            return views
        }

        private fun sendPlayerCommand(context: Context, action: (MediaController) -> Unit) {
            val scope = CoroutineScope(Dispatchers.Main.immediate)
            scope.launch {
                val conn = withTimeoutOrNull(4000L) { connect(context) } ?: return@launch
                try {
                    action(conn.first)
                } catch (_: Throwable) {
                } finally {
                    runCatching { MediaController.releaseFuture(conn.second) }
                }
            }
        }

        private suspend fun connect(context: Context): Pair<MediaController, com.google.common.util.concurrent.ListenableFuture<MediaController>> {
            val deferred = CompletableDeferred<Pair<MediaController, com.google.common.util.concurrent.ListenableFuture<MediaController>>>()
            val token = SessionToken(
                context,
                ComponentName(context, com.luckylearning.windtone.playback.PlaybackService::class.java),
            )
            val future = MediaController.Builder(context, token).buildAsync()
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
}

/** 最近一次推送的小部件状态（内存缓存，重挂载时复用） */
private object WidgetState {
    data class Snapshot(
        val title: String = "",
        val artist: String = "",
        val playing: Boolean = false,
        val cover: Bitmap? = null,
    )

    @Volatile
    var current: Snapshot = Snapshot()
}
