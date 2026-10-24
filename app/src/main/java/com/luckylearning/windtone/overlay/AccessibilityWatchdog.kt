package com.luckylearning.windtone.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.R

/**
 * 无障碍服务的「开机掉线」看护（v1.1.85）。
 *
 * 现象：比亚迪 DiLink 重启后会把 `ENABLED_ACCESSIBILITY_SERVICES` 里的第三方服务抹掉，
 * 于是「按应用隐藏」第二天就失效。没有 adb 的应用**无法**把这项系统安全设置写回去
 * （需要 `WRITE_SECURE_SETTINGS`，signature 级），所以这里不做「自动恢复」的假承诺，
 * 只把「恢复」从六步压成**一次点击**：
 *
 *  1. 记住「这台机器上曾经开启过」——由服务真正连上时（[ForegroundAppService.onServiceConnected]）
 *     或开机检查时置位；
 *  2. 开机 / 每次冷启动检查一次：曾经开过、现在关了 → 发一条通知；
 *  3. 通知点击直接落到无障碍设置页（DiLink 上是 `com.byd.carsettings` 那一页，
 *     由 [ForegroundAppDetector.accessibilitySettingsIntents] 解析），不用再记路径。
 *
 * 为什么不用轮询「自动重开」：系统设置写不进去，且反复弹通知比掉线更烦人。
 * 这里只在「确实掉过」且「当前没开」时提醒一次，开启后自动撤销。
 */
internal object AccessibilityWatchdog {

    private const val TAG = "WindToneA11yWatch"
    private const val SP = "fg_accessibility"
    private const val KEY_SEEN_ON = "seen_on"

    /** 服务上次被系统连上的时间（v1.1.86）：用来区分「设置里开着」与「实际在跑」 */
    private const val KEY_SEEN_AT = "seen_at"

    private const val CHANNEL_ID = "fg_recovery"

    /** 与前台服务通知（0x7701）分开，避免互相顶掉 */
    const val NOTIFICATION_ID = 0x7702

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 这台机器上是否**见过**无障碍服务处于开启状态（掉线恢复的前提） */
    fun seenOn(context: Context): Boolean = sp(context).getBoolean(KEY_SEEN_ON, false)

    /** 服务上次连上的时间戳；0 = 从未连上 */
    fun lastConnectedAt(context: Context): Long = sp(context).getLong(KEY_SEEN_AT, 0L)

    /** 服务连上 / 检查到已开启时调用：记住「曾经开过」，并撤销可能存在的提醒 */
    fun markOn(context: Context) {
        sp(context).edit()
            .putBoolean(KEY_SEEN_ON, true)
            .putLong(KEY_SEEN_AT, System.currentTimeMillis())
            .apply()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /**
     * 纯决策：是否该提醒用户「辅助功能掉了」。
     *
     * 只有「以前开过、现在没开」才提醒 —— 从没开过的用户是被这个功能打扰过，
     * 不该在开机时收到一条莫名其妙的通知。
     */
    fun shouldPromptRecovery(seenOn: Boolean, currentlyEnabled: Boolean): Boolean =
        seenOn && !currentlyEnabled

    /**
     * 开机 / 冷启动时调用：状态自检 +（必要时）提醒。
     *
     * @return 是否发了提醒（单测与诊断页可以直接读这个结果）
     */
    fun checkAndNotify(context: Context): Boolean {
        val enabled = ForegroundAppDetector.accessibilityEnabled(context)
        if (enabled) {
            markOn(context)
            return false
        }
        if (!shouldPromptRecovery(seenOn(context), enabled)) return false
        return runCatching { notifyRecovery(context) }.getOrElse {
            Log.w(TAG, "提醒发送失败（可能未授予通知权限）", it)
            false
        }
    }

    private fun notifyRecovery(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        // 已经有一条同样的提醒挂着就不再发（冷启动可能连续触发两次检查）
        if (nm.activeNotifications.any { it.id == NOTIFICATION_ID }) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.a11y_watch_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.a11y_watch_channel_desc)
                },
            )
        }

        // 点击直接进无障碍设置页；解析不到就退回主界面（不让通知点不动）
        val target = ForegroundAppDetector.accessibilitySettingsIntents(context)
            .firstOrNull { intent ->
                runCatching {
                    context.packageManager.resolveActivity(intent, 0) != null
                }.getOrDefault(false)
            } ?: Intent(context, MainActivity::class.java)

        val pending = PendingIntent.getActivity(
            context,
            0,
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_music)
            .setContentTitle(context.getString(R.string.a11y_watch_title))
            .setContentText(context.getString(R.string.a11y_watch_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.a11y_watch_text)),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        return true
    }
}
