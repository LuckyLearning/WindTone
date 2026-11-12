package com.anylyric.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.anylyric.data.Prefs

/**
 * 开机自启（0.2.7）。
 *
 * 通知使用权在重启后仍然有效，但不少车机 ROM 要么延迟绑定、要么干脆忘记绑 ——
 * 表现是「开机后歌词一直不出现，打开一次应用才好」。开关打开时，开机广播到达后
 * 主动 `requestRebind` 补一脚；关着则什么都不做（保持默认的零打扰）。
 *
 * 判定抽成 [BootPolicy] 纯函数：两种开机广播 + 一个开关，组合虽然简单，
 * 但这条链路的失败表现全是「开机后什么都没发生」，值得用单测钉住。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
}
