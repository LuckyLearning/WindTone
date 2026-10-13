package com.luckylearning.windtone.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.widget.MusicWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台播放服务。
 * 通过 MediaSessionService + MediaSession 实现：
 *  - 息屏/退到后台继续播放
 *  - 通知栏与锁屏播放控制
 *  - 耳机线控 / 蓝牙按键
 *  - 均衡器音效绑定 / 睡眠定时到点暂停 / 桌面小部件状态推送
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                pushWidget()
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
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

        val session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session

        // audioSessionId 可能尚未就绪，就绪后由监听器再绑
        AudioFx.attach(player.audioSessionId)
        pushWidget()
    }

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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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
}
