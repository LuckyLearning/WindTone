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
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ovn_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.ovn_channel_desc)
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

        // v1.1.93：动作按钮「收起 / 展开」。屏上的小圆钮可能点不到（车机触摸被吞），
        // 通知栏这条是稳定可用的兜底入口。
        val toggle = PendingIntent.getService(
            context,
            1,
            Intent(context, LyricOverlayService::class.java)
                .setAction(LyricOverlayService.ACTION_TOGGLE_COLLAPSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_music)
            .setContentTitle(context.getString(R.string.ovn_title))
            .setContentText(context.getString(R.string.ovn_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.ovn_toggle), toggle)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
