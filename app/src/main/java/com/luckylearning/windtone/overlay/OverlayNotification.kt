package com.luckylearning.windtone.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.R

/**
 * 悬浮歌词前台服务的常驻通知。
 *
 * 从 `LyricOverlayService` 抽出：通知的通道创建、点击回到 App、低优先级样式
 * 与服务的窗口管理无关，单独放一处，改文案/图标不必在 1600 行的 Service 里翻。
 */
internal object OverlayNotification {

    private const val CHANNEL_ID = "lyric_overlay"

    /** 前台服务通知 id（同一服务只允许一个常驻通知） */
    const val ID = 0x7701

    fun build(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm?.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮歌词", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "在桌面最上层显示当前歌词行"
                    setShowBadge(false)
                },
            )
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_music)
            .setContentTitle("悬浮歌词已开启")
            .setContentText("退到桌面后显示当前歌词行")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
