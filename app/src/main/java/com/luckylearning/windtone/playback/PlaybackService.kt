package com.luckylearning.windtone.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.widget.MusicWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台播放服务。
 * 通过 `MediaLibraryService` + `MediaLibrarySession` 实现：
 *  - 息屏/退到后台继续播放
 *  - 通知栏与锁屏播放控制
 *  - 耳机线控 / 蓝牙按键
 *  - 均衡器音效绑定 / 睡眠定时到点暂停 / 桌面小部件状态推送
 *  - **车机媒体面板的浏览树**（歌单层级，见 [LibraryBrowser]）
 *
 * **为什么从 `MediaSessionService` 换成 `MediaLibraryService`**：
 * 前者只能把「当前播放项」交给系统，车机面板因此永远只有一个播放卡片，
 * 看不到任何歌单层级。后者是前者的**子类**（既有能力一个不少），
 * 额外要求实现 `MediaLibrarySession.Callback` —— 也就是「浏览树从哪来」，
 * 这份实现就是本文件里的 [LibraryCallback]。
 * 应用自己的 `MediaController` 连接方式完全不变（仍按组件名 `SessionToken` 建连），
 * 因为两者绑定的就是同一个 binder。
 *
 * **关于 `@OptIn(UnstableApi::class)`**：`ExoPlayer.Builder.setSkipSilenceEnabled`、
 * `ExoPlayer.audioSessionId`、`Player.Listener.onAudioSessionIdChanged` 都标着 Media3 的
 * `@UnstableApi`。构建脚本里的 `-opt-in=androidx.media3.common.util.UnstableApi` 只让
 * **编译器**闭嘴，lint 不认这个开关（`UnsafeOptInUsageError`），所以这里显式标一次。
 * 类级标注可以覆盖到 `onCreate` 里的匿名 `Player.Listener` —— lint 会往上找最近的标注。
 * 本应用与 Media3 是紧耦合（整个播放内核就是 ExoPlayer），不是「顺手用了个内部 API」。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 浏览树的查询作用域。
     *
     * 为什么单开一个：浏览回调来自 binder 线程，且要求**立即**返回 Future，
     * 不能阻塞在那里等 Room 查完。于是用 `SettableFuture`，结果异步填回去。
     * `SupervisorJob`：一个节点的查询失败不该连累其它节点。
     */
    private val browseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val browser by lazy { LibraryBrowser((application as MusicApp).container.db) }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSkipSilenceEnabled(Prefs(this).skipSilence)
            .build()

        PlayerHandle.player = player
        SleepTimer.fireAction = { player.pause() }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushWidget()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                pushWidget()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                pushWidget()
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session

        // audioSessionId 可能尚未就绪，就绪后由监听器再绑
        AudioFx.attach(player.audioSessionId)
        pushWidget()
    }

    // ================================================================ 浏览树

    /**
     * 车机 / 外部媒体浏览器要的那棵树。
     *
     * 所有回调遵守同一个约定：**立刻返回 Future，查询在 IO 线程完成**；
     * 失败不留悬念——写一条诊断日志（[CrashLog]）+ 返回错误码，
     * 与项目里其它 catch 块的口径一致（见《AI 开发指南》「诊断可观测性」）。
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseRootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = browseResult { b ->
            b.itemOf(mediaId)
                ?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError<MediaItem>(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browseResult { b ->
            LibraryResult.ofItemList(b.childrenOf(parentId, page, pageSize), params)
        }

        /**
         * 车机把「要播的这一项」交回来。
         *
         * 这里必须自己补 URI：`MediaItem` 跨 Binder 只保留 `mediaId` 与 metadata，
         * `LocalConfiguration`（真正的 `content://` / `file://`）会被丢掉。
         * 歌曲节点的 `mediaId` 本来就是音频 URI（见 [com.luckylearning.windtone.data.model.Song.stableKey]），
         * 所以「补回来」只是把 mediaId 再当 uri 写一次；分类节点则展开成它下面的歌曲。
         */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = browseValue { it.resolve(mediaItems) }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = browseValue { b ->
            val resolved = b.resolve(mediaItems)
            // 「点了一个歌单」会把 1 项展开成 N 项，原来的 startIndex / 起播位置随之失效
            val expanded = resolved.size != mediaItems.size
            MediaSession.MediaItemsWithStartPosition(
                resolved,
                if (expanded) 0 else startIndex,
                if (expanded) C.TIME_UNSET else startPositionMs,
            )
        }
    }

    /**
     * 浏览查询 → `LibraryResult`（失败统一回 `ERROR_UNKNOWN`，不让车机侧挂住）。
     *
     * `T : Any` 不是为了表达什么语义，而是配合 Java 侧 `<V> LibraryResult<V> ofError(int)`
     * 的签名——没有上界时 Kotlin 会把 `T` 推成 `T & Any`，与 `LibraryResult<T>` 对不上。
     */
    private fun <T : Any> browseResult(
        block: suspend (LibraryBrowser) -> LibraryResult<T>,
    ): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        browseScope.launch {
            try {
                future.set(block(browser))
            } catch (t: Throwable) {
                reportBrowseFailure(t)
                future.set(LibraryResult.ofError<T>(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    /** 浏览查询 → 裸值（播放项解析用；失败时异常交给 Media3 处理） */
    private fun <T> browseValue(block: suspend (LibraryBrowser) -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        browseScope.launch {
            try {
                future.set(block(browser))
            } catch (t: Throwable) {
                reportBrowseFailure(t)
                future.setException(t)
            }
        }
        return future
    }

    private fun reportBrowseFailure(t: Throwable) {
        Log.w(TAG, "车机浏览树查询失败", t)
        CrashLog.record(this, TAG, "车机浏览树查询失败：${t.message}")
    }

    // ================================================================ 小部件

    private fun pushWidget() {
        val player = mediaSession?.player ?: return
        scope.launch {
            val md = player.currentMediaItem?.mediaMetadata
            val title = md?.title?.toString().orEmpty()
            val artist = md?.artist?.toString().orEmpty()
            val playing = player.isPlaying
            val cover = kotlinx.coroutines.withContext(Dispatchers.Default) {
                md?.artworkData?.takeIf { it.isNotEmpty() }?.let { decodeSmall(it) }
            }
            MusicWidgetProvider.push(this@PlaybackService, title, artist, playing, cover)
        }
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= 128 && h / 2 >= 128) {
            sample *= 2
            w /= 2
            h /= 2
        }
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AudioFx.release()
        SleepTimer.cancel()
        PlayerHandle.player = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WindToneBrowse"
    }
}
