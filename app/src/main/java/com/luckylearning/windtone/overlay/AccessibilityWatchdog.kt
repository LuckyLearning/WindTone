package com.luckylearning.windtone.overlay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.luckylearning.windtone.MainActivity
import com.luckylearning.windtone.R

/**
 * 无障碍服务的「开机掉线」看护（v1.1.85；v1.2.36 补自动恢复尝试）。
 *
 * 现象：比亚迪 DiLink（含 宋 L DM-i 2026 / DiLink 100）重启后会把
 * `ENABLED_ACCESSIBILITY_SERVICES` 里的第三方服务抹掉，于是「按应用隐藏」
 * 第二天就失效。没有 adb 的应用**无法**把这项系统安全设置写回去
 * （需要 `WRITE_SECURE_SETTINGS`，signature 级），所以默认不做「自动恢复」的假承诺。
 *
 * 能做的三件事：
 *  1. 记住「这台机器上曾经开启过」——由服务真正连上时（[ForegroundAppService.onServiceConnected]）
 *     或检查到已开启时置位；
 *  2. 若用户曾用 adb `pm grant … WRITE_SECURE_SETTINGS`（开发机 / 仍可扫码的车机），
 *     开机时**尝试**把本服务写回列表（[tryAutoRestore]）；普通车机拿不到该权限，立刻跳过；
 *  3. 仍关着 → 发一条通知，点击直达无障碍设置页（DiLink 上是 `com.byd.carsettings`）。
 *
 * 为什么不用轮询「假装自动重开」：系统设置写不进去，且反复弹通知比掉线更烦人。
 * 这里只在「确实掉过」且「当前没开」时提醒一次，开启后自动撤销。
 *
 * **稳定方案（推荐）**：车机上同时开「使用情况访问」——DiLink 不会像抹无障碍那样
 * 清掉 UsageStats 授权，1.5 秒轮询同样能按应用隐藏。无障碍仍是即时通道，掉了再点通知补上。
 */
internal object AccessibilityWatchdog {

    private const val TAG = "WindToneA11yWatch"
    private const val SP = "fg_accessibility"
    private const val KEY_SEEN_ON = "seen_on"

    /** 服务上次被系统连上的时间（v1.1.86）：用来区分「设置里开着」与「实际在跑」 */
    private const val KEY_SEEN_AT = "seen_at"

    /** 曾开启时记下的组件扁平名（包名/类名），供有 WRITE_SECURE_SETTINGS 时写回 */
    private const val KEY_COMPONENT = "component"

    /** 「已开启」记录多久刷新一次时间戳：一天一次足够支撑「最近有没有连上」的判断 */
    private const val REFRESH_MS = 24 * 60 * 60 * 1000L

    private const val CHANNEL_ID = "fg_recovery"

    /** 与前台服务通知（0x7701）分开，避免互相顶掉 */
    const val NOTIFICATION_ID = 0x7702

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 这台机器上是否**见过**无障碍服务处于开启状态（掉线恢复的前提） */
    fun seenOn(context: Context): Boolean = sp(context).getBoolean(KEY_SEEN_ON, false)

    /** 服务上次连上的时间戳；0 = 从未连上 */
    fun lastConnectedAt(context: Context): Long = sp(context).getLong(KEY_SEEN_AT, 0L)

    fun rememberedComponent(context: Context): String =
        sp(context).getString(KEY_COMPONENT, null).orEmpty()

    fun serviceComponent(context: Context): String =
        "${context.packageName}/${ForegroundAppService::class.java.name}"

    /** 服务连上 / 检查到已开启时调用：记住「曾经开过」，并撤销可能存在的提醒 */
    fun markOn(context: Context) {
        val sp = sp(context)
        // 已经记过就不再写：apply() 虽是异步落盘，但每次都会把整个 XML 重写一遍，
        // 而 markOn 在每次回到前台、每次无障碍服务连上都会被调用（车机上很频繁）。
        // 只有「首次记」「组件名变了」「距上次超过一天」才真正落盘。
        val now = System.currentTimeMillis()
        val stale = !sp.getBoolean(KEY_SEEN_ON, false) ||
            sp.getString(KEY_COMPONENT, null) != serviceComponent(context) ||
            now - sp.getLong(KEY_SEEN_AT, 0L) > REFRESH_MS
        if (stale) {
            sp.edit()
                .putBoolean(KEY_SEEN_ON, true)
                .putLong(KEY_SEEN_AT, now)
                .putString(KEY_COMPONENT, serviceComponent(context))
                .apply()
        }
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

    /** 是否已拿到 WRITE_SECURE_SETTINGS（通常靠 adb pm grant；普通安装永远是 false） */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 把本服务组件并进 `ENABLED_ACCESSIBILITY_SERVICES` 列表（纯函数，可单测）。
     * 已存在则原样返回；空列表则只写本服务。
     */
    fun mergeEnabledServices(existing: String?, component: String): String {
        val parts = existing.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.any { it.equals(component, ignoreCase = true) }) {
            return parts.joinToString(":")
        }
        return (parts + component).joinToString(":")
    }

    /**
     * 有 WRITE_SECURE_SETTINGS 时尝试写回；成功返回 true。
     * 普通车机（含宋 L DM-i 2026 收紧 adb 后）几乎总是 false —— 调用方继续走通知。
     */
    fun tryAutoRestore(context: Context): Boolean {
        if (!seenOn(context)) return false
        if (ForegroundAppDetector.accessibilityEnabled(context)) {
            markOn(context)
            return true
        }
        if (!hasWriteSecureSettings(context)) return false
        val component = rememberedComponent(context).ifBlank { serviceComponent(context) }
        return runCatching {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val merged = mergeEnabledServices(current, component)
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged,
            )
            // 部分 ROM 还要打开总开关，否则列表写了也不绑服务
            runCatching {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            val ok = ForegroundAppDetector.accessibilityEnabled(context)
            if (ok) {
                markOn(context)
                Log.i(TAG, "已用 WRITE_SECURE_SETTINGS 写回无障碍服务")
            } else {
                Log.w(TAG, "写回 ENABLED_ACCESSIBILITY_SERVICES 后仍未生效")
            }
            ok
        }.getOrElse {
            Log.w(TAG, "自动写回无障碍失败", it)
            false
        }
    }

    /**
     * 开机 / 冷启动 / 回到前台时调用：先尝试有权限的自动恢复，再（必要时）提醒。
     *
     * @return 是否发了提醒（单测与诊断页可以直接读这个结果）
     */
    fun checkAndNotify(context: Context): Boolean {
        if (tryAutoRestore(context)) return false
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
