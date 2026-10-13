package com.luckylearning.windtone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.CrashLog

class MusicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CoverRepository.clear()
        ensureNotificationChannel()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "播放控制与当前曲目信息"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "local_music_player_playback"
    }
}
