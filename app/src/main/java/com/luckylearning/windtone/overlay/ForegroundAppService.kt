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
        if (shouldIgnoreForegroundEvent(pkg, imePackages())) return
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
        connected = true
        runCatching { AccessibilityWatchdog.markOn(applicationContext) }
        com.luckylearning.windtone.diag.DiagLog.log("A11Y", "服务已连接（可接收窗口事件）")
    }

    /**
     * 服务是否**此刻真的连着**（2026-10-31）。
     *
     * 与「系统设置里那个勾打没打」（[ForegroundAppDetector.accessibilityEnabled]）是两件事：
     * DiLink 上服务被杀、勾还留着是常态。侧钮的「仅未运行显示」要用这个更严的判据，
     * 否则用户最需要那颗钮时它恰好不在。
     */
    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    /**
     * 当前输入法包名（v1.1.90）。缓存到服务实例上：输入法列表在一次会话里几乎不变，
     * 每次事件都查一遍 InputMethodManager 没必要（事件频率虽低，但没必要更慢）。
     */
    private var imeCache: Set<String>? = null

    private fun imePackages(): Set<String> {
        imeCache?.let { return it }
        val set = runCatching {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val enabled = imm?.enabledInputMethodList.orEmpty().map { it.packageName }
            val default = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )?.substringBefore('/')
            (enabled + listOfNotNull(default)).toSet()
        }.getOrDefault(emptySet())
        imeCache = set
        return set
    }

    override fun onInterrupt() {
        // 系统要求实现；本服务不提供反馈，无事可做
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        // 用户在设置里关掉服务：清掉残留包名，别让下一次开启先继承一个过期值
        ForegroundAppState.reset()
        com.luckylearning.windtone.diag.DiagLog.log("A11Y", "服务被解绑（系统关闭或被杀）")
        // 2026-11-02：勾还开着 = 不是用户主动关的，是系统把服务杀了（DiLink 常态）。
        // 有 WRITE_SECURE_SETTINGS 就立刻写回；没有就提醒点一下重开 —— 否则这条权限
        // 掉了之后没有任何入口提示（侧钮颜色不算入口，用户不会一直盯着它）。
        runCatching {
            val app = applicationContext
            if (ForegroundAppDetector.accessibilityEnabled(app) &&
                !AccessibilityWatchdog.tryAutoRestore(app)
            ) {
                AccessibilityWatchdog.promptServiceDead(app)
            }
        }
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "WindToneFgService"

        /**
         * 进程内「服务此刻已连接」标记。
         *
         * 只活在本进程：进程重启后一定是 false，要等服务重新连上才变 true ——
         * 这正是「真的在跑」该有的语义，不需要额外持久化。
         */
        @Volatile
        private var connected = false

        /** 供 [A11yRuntime] 与诊断/自检读取 */
        fun isConnected(): Boolean = connected
    }
}
