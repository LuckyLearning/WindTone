package com.luckylearning.windtone.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.luckylearning.windtone.data.CoverRepository
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
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
import com.luckylearning.windtone.playback.parallel.ParallelDownloadRegistry
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunDrive
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

    /**
     * 云盘曲目（稳定键 mediaId）跨 Binder 回来时，起播那一项现取真直链用。
     * 其余项回占位地址，由引擎的滑动窗口补链（见 LibraryBrowser.resolveOne）。
     */
    private val cloudUrlResolver: suspend (driveId: String, fileId: String) -> String? =
    { driveId, fileId -> AliyunDrive(AliyunAuth(applicationContext)).downloadUrl(driveId, fileId).getOrNull() }

    private val browser by lazy {
        LibraryBrowser(
            LibraryBrowseStore((application as MusicApp).container.db),
            cloudUrlResolver,
        )
    }

    override fun onCreate() {
        super.onCreate()

        // A7 无缝：单 ExoPlayer + 连续 MediaItem 队列；Media3 读分轨 encoder delay/padding。
        // 平台无关闭 API；设置页为只读说明（Prefs.gaplessPlayback 为 C1 死键）。
        // E1 Soft EQ + #9 stereo balance: see PlaybackAudioProcessors (B2/B3 isActive unload).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    // A6/B2/B3：清单见 PlaybackAudioProcessors；关/居中靠 isActive 卸链，不重建 sink。
                    .setAudioProcessors(PlaybackAudioProcessors.forAudioSink())
                    .build()
            }
        }
        val sceneLoadControl = SceneLoadControl()
        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            // 在线音频的磁盘缓存（v1.1.38）：http(s) 走 CacheDataSource，本地文件不受影响。
            // 放在这里而不是 PlaybackEngine，是因为 ExoPlayer 只在这个服务里创建 ——
            // 换实例就得跟着换缓存源，否则缓存只对创建它的那个实例有效。
            .setMediaSourceFactory(OnlineMediaCache.mediaSourceFactory(this))
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
            // B1 分场景 LoadControl：本地 file/content 小缓冲；http(s)/网盘仍 30s/120s。
            // Media3 默认上限只有 50 秒，弱网无损易卡；远程侧保持既有大垫。
            .setLoadControl(sceneLoadControl)
            .build()

        PlayerHandle.player = player
        PlaybackQualityProbe.attach(player)
        SleepTimer.fireAction = { player.pause() }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 音效补试（v1.1.22）：部分机型要等第一次真正输出音频才有
                // audioSessionId。此前只在 onAudioSessionIdChanged 里绑一次，
                // 「先开 App 后放歌」的用户会一直停在「不可用」，只能杀进程重来。
                if (isPlaying) AudioFx.retryIfUnavailable(player.audioSessionId)
                pushWidget()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                sceneLoadControl.updateFromMediaItem(mediaItem)
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

        // H4：通知栏第二行优先显示 MediaMetadata.description（当前歌词）
        setMediaNotificationProvider(LyricMediaNotificationProvider(this))

        val session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session

        // audioSessionId 可能尚未就绪，就绪后由监听器再绑
        AudioFx.attach(player.audioSessionId)
        // 切歌淡入淡出 + 曲间响度平衡（v1.1.52）：挂自己的 Player.Listener 管理 player.volume
        TrackVolume.attach(player, this)
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
         * 本地歌曲节点的 `mediaId` 本来就是音频 URI（见 [com.luckylearning.windtone.data.model.Song.stableKey]），
         * 所以「补回来」只是把 mediaId 再当 uri 写一次；分类节点则展开成它下面的歌曲；
         * **云盘曲目**的 mediaId 是稳定键（`aliyun:ay=…`），不能当 URI 写 —— 由
         * [LibraryBrowser.resolveOne] 重建占位 / 备忘录 / 现取的播放地址。
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
            val resolved = b.resolve(mediaItems, startIndex)
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

    /**
     * 上次推给小部件的内容指纹。
     *
     * 存在的理由见 [pushWidget] 的去重注释：换一行歌词也会走到 pushWidget，
     * 不去重就是每换一行重解一次封面。
     */
    @Volatile
    private var lastWidgetKey: String? = null

    private fun pushWidget() {
        val player = mediaSession?.player ?: return
        val item = player.currentMediaItem
        val md = item?.mediaMetadata
        val title = md?.title?.toString().orEmpty()
        val artist = md?.artist?.toString().orEmpty()
        val playing = player.isPlaying
        val artworkSize = md?.artworkData?.size ?: 0
        // 去重（v1.2.40）：锁屏歌词靠 `replaceMediaItem` 改 description 表达当前行，
        // 每换一行都会触发 `onMediaMetadataChanged` → 走到这里。不去重的话，
        // 一首 61 行的歌就是 61 次封面解码 + 61 次 RemoteViews 全量重建
        // （每次还带 7 个 PendingIntent 的 IPC）；一小时播放下来几百次。
        //
        // 指纹里带 artworkSize 是为了不误伤「封面异步加载完成」那一次更新：
        // 那时 mediaId / 标题 / 播放态都没变，只有封面字节从无到有。
        val key = "${item?.mediaId.orEmpty()}|$title|$artist|$playing|$artworkSize"
        if (key == lastWidgetKey) return
        lastWidgetKey = key
        scope.launch {
            val cover = kotlinx.coroutines.withContext(Dispatchers.Default) {
                md?.artworkData?.takeIf { it.isNotEmpty() }?.let { decodeSmall(it) }
            }
            MusicWidgetProvider.push(this@PlaybackService, title, artist, playing, cover)
        }
    }

    /** 小部件封面：统一走 CoverRepository（RGB_565 + cachePxFor），勿本地 BitmapFactory。 */
    private fun decodeSmall(bytes: ByteArray): Bitmap? =
        CoverRepository.decodeForWidget(bytes)

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
        TrackVolume.release()
        // 缓存持有目录锁与索引文件：不释放的话，进程内下次建 SimpleCache 会直接抛
        // 「Another SimpleCache instance uses the folder」，在线播放从此永久没缓存。
        OnlineMediaCache.release()
        // 并行下载的分段读句柄：服务没了就该 reap，否则下次重建会撞目录锁 /
        // 句柄悬空（与上面两条 native 资源同一口径）
        ParallelDownloadRegistry.setCurrentKey(null)
        SleepTimer.cancel()
        PlayerHandle.player = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * 开机广播专用的动作（v1.1.24）：后台恢复上次队列并起播，不涉及任何界面。
     *
     * 为什么走 `onStartCommand` 而不是在 Receiver 里直接操作播放器：
     * Receiver 的存活时间只有 10 秒且没有保活，恢复队列要读数据库、建 MediaController；
     * 交给服务做，链路与「用户在界面上点播放」完全一致，出问题也只在同一处。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_AUTOPLAY) {
            runCatching {
                (application as MusicApp).container.playback.startPlaybackInBackground()
            }.onFailure { Log.w(TAG, "开机后台播放启动失败", it) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        private const val TAG = "WindToneBrowse"

        /** 开机后台起播（见 [onStartCommand]） */
        const val ACTION_AUTOPLAY = "com.luckylearning.windtone.action.AUTOPLAY"
    }
}
