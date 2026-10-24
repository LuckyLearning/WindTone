package com.luckylearning.windtone.overlay

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.luckylearning.windtone.data.Prefs

/**
 * 前台应用检测的主通道（v1.1.29，设计见 docs/设计-悬浮窗按应用隐藏.md）。
 *
 * 用户在系统设置 → 辅助功能里开启本服务后，它**独立于界面活着** ——
 * 这正是「听风在后台也要知道前台是谁」的关键：悬浮窗的价值全在后台时段。
 *
 * 订阅面见 `res/xml/foreground_app_service.xml`：只收窗口状态变化、
 * **不读屏幕内容**。每个事件只做三件事：
 *  1. 取包名喂给 [ForegroundAppState]（去重后若真换了应用）；
 *  2. 用户开着「前台应用记录」时记一条历史；
 *  3. 别的什么都不做 —— 不拦截、不自动点击、不碰其它应用。
 *
 * ⚠️ 事件可能来自任意线程，且频率极低（应用切换才来一次），
 * 所以每次都直接读一次偏好而不是缓存 —— 简单且永远新鲜。
 */
class ForegroundAppService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = runCatching { event.packageName?.toString() }.getOrNull() ?: return
        if (!ForegroundAppState.report(pkg)) return

        if (Prefs(applicationContext).foregroundLogEnabled) {
            ForegroundAppLog.append(
                applicationContext,
                pkg,
                ForegroundAppLog.labelOf(applicationContext, pkg),
            )
        }
    }

    /**
     * 服务真正被系统连上 = 无障碍确实在工作，记住这一点（v1.1.85）。
     *
     * [AccessibilityWatchdog] 靠这个标记判断「以前开过、现在掉了」，
     * 再去发那条一次点击就能恢复的通知。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { AccessibilityWatchdog.markOn(applicationContext) }
        com.luckylearning.windtone.diag.DiagLog.log("A11Y", "服务已连接（可接收窗口事件）")
    }

    override fun onInterrupt() {
        // 系统要求实现；本服务不提供反馈，无事可做
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 用户在设置里关掉服务：清掉残留包名，别让下一次开启先继承一个过期值
        ForegroundAppState.reset()
        com.luckylearning.windtone.diag.DiagLog.log("A11Y", "服务被解绑（系统关闭或被杀）")
        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "WindToneFgService"
    }
}
