package com.anylyric.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.anylyric.accessibility.AccessibilityWatchdog
import com.anylyric.data.Prefs

/**
 * 开机自启（0.2.7）。
 *
 * 通知使用权在重启后仍然有效，但不少车机 ROM 要么延迟绑定、要么干脆忘记绑 ——
 * 表现是「开机后歌词一直不出现，打开一次应用才好」。开关打开时，开机广播到达后
 * 主动 `requestRebind` 补一脚；关着则什么都不做（保持默认的零打扰）。
 *
 * 服务绑回后还有「让悬浮窗出现」的下半场（0.2.7.1）：刚开机的窗口期内连接成功时，
 * `SessionHub.maybeBootStandby()` 会画一条「已启动，等待播放」—— 否则开机后屏上
 * 毫无动静，用户无从判断自启是否生效。
 *
 * 判定抽成 [BootPolicy] 纯函数：两种开机广播 + 一个开关，组合虽然简单，
 * 但这条链路的失败表现全是「开机后什么都没发生」，值得用单测钉住。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 先同步看门狗任务：它有自己的开关，与「开机自启」无关。
        // setPersisted 通常能跨重启存活，这里只是兜一层（开关关闭时是 cancel）。
        ServiceWatchdog.sync(context)
        // 无障碍服务在部分 ROM（HONOR/MagicOS）更新应用后会被关掉；开过机就试一次写回。
        // 没有 WRITE_SECURE_SETTINGS 时内部直接返回 false，不做任何事。
        AccessibilityWatchdog.tryAutoRestore(context)
        if (!BootPolicy.shouldRebind(intent.action, Prefs(context).bootAutostart)) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context, MediaWatchService::class.java)
            )
        }
    }
}

object BootPolicy {

    /** 车机与部分盒子的快速开机广播：语义与 BOOT_COMPLETED 相同。 */
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

    fun shouldRebind(action: String?, enabled: Boolean): Boolean = when (action) {
        Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> enabled
        else -> false
    }

    /**
     * 「开机后台启动、让悬浮窗出现」的窗口判定。
     *
     * [uptimeMs] 是 `SystemClock.elapsedRealtime()` —— 它本身就是本次开机的时长，
     * 判「刚开机」不需要状态文件。超过窗口期才连上（比如系统拖了五分钟才绑）就不补
     * 待播提示：那时用户多半已经在用手机了，凭空冒出一个悬浮窗反而打扰。
     */
    fun shouldShowStandby(uptimeMs: Long, enabled: Boolean, windowMs: Long): Boolean =
        enabled && uptimeMs in 0..windowMs
}
