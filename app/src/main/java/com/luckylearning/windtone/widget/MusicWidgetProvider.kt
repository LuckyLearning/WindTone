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
import com.luckylearning.windtone.ui.AppShortcut
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

            // 整块信息区的兜底：普通地打开应用首页
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            // 三块可点区域各去不同去处（批次 9.5-①③）：
            //   封面 → 播放页；歌名 → 曲库歌曲列表；歌手 → 歌单列表。
            // 以前整块信息区都只进首页，"点哪里都一样"等于三个按钮里有两个是废的。
            //
            // requestCode 必须各不相同：PendingIntent 的身份是
            // (requestCode, Intent) 的组合，但 `FLAG_UPDATE_CURRENT` 在
            // requestCode 相同时会用后一个覆盖前一个，最终三个区域全变成同一个跳转。
            views.setOnClickPendingIntent(R.id.widget_cover, entry(context, 21, AppShortcut.OPEN_PLAYER))
            views.setOnClickPendingIntent(R.id.widget_title, entry(context, 22, AppShortcut.OPEN_LIBRARY))
            views.setOnClickPendingIntent(R.id.widget_artist, entry(context, 23, AppShortcut.OPEN_PLAYLISTS))
            // 信息区的空白部分保留「打开应用」的兜底，避免标题很短时右侧一片死区
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

        /**
         * 小组件的深链 Intent。
         *
         * 复用启动器快捷方式那条通道（`AppShortcut.ACTION` + `EXTRA`），
         * 这样"待处理动作"只有一处消费逻辑 —— 否则小组件与快捷方式各有一套
         * 解析，迟早会出现一个能跳一个不能跳。
         */
        private fun entry(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode,
                Intent(context, MainActivity::class.java)
                    .setAction(AppShortcut.ACTION)
                    .putExtra(AppShortcut.EXTRA, action)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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
