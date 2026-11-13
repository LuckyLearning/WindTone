package com.anylyric.service

/**
 * 静默掉线看门狗的判定（纯函数，便于单测钉住边界）。
 *
 * 看门狗解决的是「服务被系统/ROM 杀掉但没回调 onListenerDisconnected」：
 * 心跳停在被杀之前，下一次周期任务发现心跳过期就 requestRebind。
 */
object WatchdogPolicy {

    /**
     * 心跳超过这个时长没更新，才认为服务可能已经掉线。
     *
     * 取 20 分钟：心跳本身 5 分钟写一次，留 4 倍余量。太短会在车机进入深度休眠、
     * 心跳暂停时误判；太长则掉线后要等半小时才恢复。
     */
    const val STALE_MS = 20 * 60_000L

    /**
     * @param nowMs      当前时间（wall clock）
     * @param lastAliveMs 最近一次心跳时间；0 表示从未记录
     * @param granted    系统设置里是否勾着通知使用权
     * @param enabled    用户是否打开了看门狗开关
     */
    fun shouldRebind(
        nowMs: Long,
        lastAliveMs: Long,
        granted: Boolean,
        enabled: Boolean
    ): Boolean {
        if (!enabled || !granted) return false
        // 从未心跳过：可能是刚装/刚开开关，值得试一次。
        if (lastAliveMs <= 0L) return true
        val age = nowMs - lastAliveMs
        // 时钟回拨（用户改时间 / NTP 校正）：不动作，等下一次心跳。
        if (age < 0L) return false
        return age >= STALE_MS
    }
}
