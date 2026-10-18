package com.luckylearning.windtone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.luckylearning.windtone.data.BootAction
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
        when (BootAction.decide(prefs.autoPlayOnBoot, prefs.carMode)) {
            BootAction.Kind.PLAY_IN_BACKGROUND -> runCatching {
                BootTrace.mark(context, "boot:autoplay")
                // 用 startForegroundService：媒体服务会在起播时自己贴通知。
                // BOOT_COMPLETED 是系统允许「后台启动前台服务」的豁免场景。
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_AUTOPLAY),
                )
            }.onFailure {
                Log.w(TAG, "开机后台播放启动失败", it)
                BootTrace.mark(context, "boot:autoplay-failed")
            }

            BootAction.Kind.OPEN_UI -> runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure { Log.w(TAG, "开机自启失败", it) }

            BootAction.Kind.NONE -> Unit
        }
    }

    private companion object {
        const val TAG = "CarBootReceiver"
    }
}
