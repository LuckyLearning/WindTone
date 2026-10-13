package com.luckylearning.windtone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.luckylearning.windtone.data.Prefs

/**
 * 车载模式开机自启。
 *
 * 场景：比亚迪 DiLink 车机每次上电都会走一遍完整的开机流程，
 * 上次会话里开着的第三方 App 不会被系统自动拉起。开着「车载模式」的用户
 * 显然希望上车通电就进播放页（歌词悬浮窗、媒体控制都随主界面就绪），
 * 这个 Receiver 就是补上这一环。
 *
 * 行为约束：
 *  - **只在车载模式开启时动作**。关闭时收到广播直接返回，手机用户零打扰；
 *  - 用 `FLAG_ACTIVITY_NEW_TASK` 拉主界面——Receiver 环境里没有 Activity 栈；
 *  - 广播处理必须在 10 秒内返回，所以只做「读一个布尔 + startActivity」，
 *    不碰数据库、不起服务（播放链路由主界面启动后按需拉起）。
 *
 * 关于后台启动限制：`ACTION_BOOT_COMPLETED` 属于系统文档明确列出的
 * 「可从后台启动 Activity」的豁免场景；DiLink 等车机 ROM 对此也更宽松。
 * 若个别 ROM 拦截，用户从桌面图标点开即可，不影响其他功能。
 */
class CarBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Prefs(context).carMode) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { Log.w(TAG, "开机自启失败", it) }
    }

    private companion object {
        const val TAG = "CarBootReceiver"
    }
}
