package com.luckylearning.windtone.overlay

import android.content.Context

/**
 * 「无障碍此刻是否真的可用」（2026-10-31）。
 *
 * 两个条件缺一不可：
 * 1. 系统设置里勾了听风的无障碍服务（[ForegroundAppDetector.accessibilityEnabled]）；
 * 2. 服务**当前已连接**（`ForegroundAppService.isConnected`）。
 *
 * 为什么不能只看第 1 条：DiLink 一类车机会把服务杀掉而开关仍留着，那种状态下
 * 「已开启」是假的。侧钮三档里的「仅未运行时显示」用它当判据，才不会在真正需要
 * 入口的时候把入口藏掉。
 *
 * ⚠️ 第 2 条依赖进程内静态标记，进程重启后为 false（要等服务重连）—— 这是刻意的：
 * 冷启动那一刻服务确实没连上，此时把红钮摆出来是对的。
 */
object A11yRuntime {

    fun running(context: Context): Boolean =
        ForegroundAppService.isConnected() &&
            ForegroundAppDetector.accessibilityEnabled(context)
}
