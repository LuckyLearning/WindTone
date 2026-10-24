package com.luckylearning.windtone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.luckylearning.windtone.data.BootAction
import com.luckylearning.windtone.data.BootStatus
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.PlaybackService

/**
 * 开机自启。
 *
 * 两种形态，由 [BootAction.decide] 决定（纯函数、有单测）：
 *
 *  1. **后台播放**（v1.1.24，设置里「开机后台播放」）：只把 [PlaybackService] 拉起来，
 *     服务自己从数据库恢复上次队列并起播，**不打开主界面**。车机用户要的是
 *     「上车通电就有歌」，弹一个全屏界面会挡住导航和桌面 —— 2026-10-18 用户原话
 *     「可以增强为开机自启后后台自启播放，不打开主页面吗」。
 *  2. **打开主界面**（车载模式的老行为）：`ACTION_BOOT_COMPLETED` 属于系统列出的
 *     「可从后台启动 Activity」豁免场景，车机 ROM 通常也放行。
 *
 * 广播处理必须在 10 秒内返回，所以这里只做「读两个布尔 + 一次 startService」，
 * 数据库与播放链路都在服务里异步做。
 */
class CarBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = Prefs(context)
        val kind = BootAction.decide(prefs.autoPlayOnBoot, prefs.carMode)
        // 先落一条「广播到了」再动手：这条链路的失败表现全是「开机后什么都没发生」，
        // 有这行记录才能区分「广播没到」与「到了但没播起来」（诊断页会念出来）。
        when (kind) {
            BootAction.Kind.PLAY_IN_BACKGROUND -> BootStatus.markReceived(context, BootStatus.ACTION_AUTOPLAY)
            BootAction.Kind.OPEN_UI -> BootStatus.markReceived(context, BootStatus.ACTION_OPEN_UI)
            BootAction.Kind.NONE -> BootStatus.markReceived(
                context,
                BootStatus.ACTION_NONE,
                "「开机后台播放」与「车载模式」都没打开",
            )
        }
        // 无障碍掉线自检（v1.1.85）：车机会在重启时抹掉第三方无障碍服务，
        // 这里开机就提示一次，用户点通知即可回到那个（藏在车辆设置里的）无障碍页。
        runCatching {
            com.luckylearning.windtone.overlay.AccessibilityWatchdog.checkAndNotify(context)
        }

        when (kind) {
            BootAction.Kind.PLAY_IN_BACKGROUND -> runCatching {
                BootTrace.mark(context, "boot:autoplay")
                // ⚠️ 这里**必须用 startService 而不是 startForegroundService**。
                // 用后者时系统要求 5 秒内调用 startForeground()，而「恢复队列」在冷启动的
                // 车机上可能超过 5 秒（读库 + 建 MediaController），一旦超时就是
                // 「Context.startForegroundService() did not then call Service.startForeground()」
                // 的 ANR —— 2026-10-18 在模拟器上就是这样复现的（真机上更慢）。
                // BOOT_COMPLETED 接收器自带临时白名单，允许直接 startService；
                // 通知由 Media3 在真正起播时自己贴（它内部会调 startForeground）。
                context.startService(
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_AUTOPLAY),
                )
            }.onFailure {
                Log.w(TAG, "开机后台播放启动失败", it)
                BootTrace.mark(context, "boot:autoplay-failed")
                BootStatus.markResult(context, false, "服务没起来：" + (it.message ?: it.javaClass.simpleName))
            }

            BootAction.Kind.OPEN_UI -> runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure {
                Log.w(TAG, "开机自启失败", it)
                BootStatus.markResult(context, false, "界面没起来：" + (it.message ?: it.javaClass.simpleName))
            }.onSuccess { BootStatus.markResult(context, true) }

            BootAction.Kind.NONE -> Unit
        }
    }

    private companion object {
        const val TAG = "CarBootReceiver"
    }
}
