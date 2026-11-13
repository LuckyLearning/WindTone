package com.anylyric.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.anylyric.data.Permissions
import com.anylyric.data.Prefs

/**
 * 静默掉线看门狗（默认关闭）。
 *
 * `NotificationListenerService` 由系统绑定，我们没法像普通 Service 那样拉活；
 * 但可以周期性地在「已授权 + 心跳过期」时 `requestRebind`。真机里省电策略把服务
 * 收掉后 `onListenerDisconnected` 不一定回调 —— 表现就是「歌词忽然不更新」。
 *
 * 用 `JobScheduler` 而不是 `AlarmManager`：
 *  · 15 分钟周期是系统允许的最小值，不需要精确闹钟（minSdk 31 起精确闹钟要额外权限）；
 *  · `setPersisted(true)` 能跨重启存活，不需要自己在 BOOT_COMPLETED 里重排。
 *
 * 开关关闭时 [sync] 会 cancel 任务，保证「默认零打扰」。
 */
object ServiceWatchdog {

    const val JOB_ID = 2103

    /** JobScheduler 周期任务的最小间隔（15 分钟）。 */
    const val PERIOD_MS = 15 * 60_000L

    /** 按开关状态注册 / 取消周期任务。可重复调用。 */
    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!Prefs(context).watchdogEnabled) {
            runCatching { scheduler.cancel(JOB_ID) }
            return
        }
        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, WatchdogJobService::class.java)
        )
            .setPeriodic(PERIOD_MS)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { scheduler.schedule(info) }
    }
}

/**
 * 看门狗的执行体：只做一件事 —— 心跳过期就请求重绑。
 *
 * 跑在独立进程之外（由系统按 JobScheduler 拉起），不常驻、不持锁，执行完立即结束。
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Prefs(this)
        val should = WatchdogPolicy.shouldRebind(
            nowMs = System.currentTimeMillis(),
            lastAliveMs = prefs.lastAliveAt,
            granted = Permissions.isListenerGranted(this) == true,
            enabled = prefs.watchdogEnabled
        )
        if (should) Permissions.requestRebind(this)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
