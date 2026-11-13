package com.anylyric.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.anylyric.data.Prefs
import com.anylyric.diag.Probe

/**
 * 前台应用检测（0.2.11，「按应用隐藏悬浮窗」的唯一检测通道）。
 *
 * 用户在系统设置 → 辅助功能里开启本服务后，它**独立于界面活着** ——
 * 这正是「后台也要知道前台是谁」的关键：悬浮窗的价值全在后台时段。
 *
 * 订阅面见 `res/xml/foreground_app_service.xml`：只收窗口状态变化、
 * **canRetrieveWindowContent=false，不读屏幕内容**。每个事件只做四件事：
 *  1. 过滤输入法（弹起键盘不是「切换到了别的应用」）；
 *  2. 交给 [PkgStabilizer] 做 450ms 滞回，挡掉车机上的包名闪烁；
 *  3. 稳定包名真的变了才喂给 [ForegroundApps]（由 SessionHub 重算显隐）；
 *  4. 别的什么都不做 —— 不拦截触摸、不自动点击、不碰其它应用。
 *
 * 延迟提交用「有事件才挂一次 postDelayed」实现，**不轮询**：没有应用切换时
 * 本服务连一个定时器都没有，闲置开销为零。
 */
class ForegroundAppService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val stabilizer = PkgStabilizer()

    /** 懒加载一次就复用：事件频率虽低，但每来一次就新建一个 Prefs 只是白分配。 */
    private val prefs: Prefs by lazy { Prefs(applicationContext) }

    /** 输入法包名缓存：一次会话里几乎不变，没必要每个事件都查一遍系统服务。 */
    private var imeCache: Set<String>? = null

    /** 上一次真正上报过的稳定包名，用来挡掉重复上报。 */
    private var lastReported: String? = null

    /**
     * 候选包名稳定 450ms 后提交一次。
     *
     * 必须靠这个延迟回调：`onRaw` 只更新候选，没有新事件时没人推进时钟，
     * 候选就永远提交不了（表现为「切了应用但不隐藏」）。
     */
    private val commitTick = Runnable {
        val stable = stabilizer.tick()
        if (stable != lastReported) {
            lastReported = stable
            ForegroundApps.report(stable)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 开关关着 / 名单为空：一次早退，连包名都不解析。
        // 服务可能是用户早就开着、后来清空了名单，这时不该继续做无用功。
        if (!foregroundDetectionNeeded(prefs.hideByAppEnabled, prefs.hiddenApps)) return

        val pkg = runCatching { event.packageName?.toString() }.getOrNull() ?: return
        if (shouldIgnoreForegroundEvent(pkg, imePackages())) return

        stabilizer.onRaw(pkg)
        handler.removeCallbacks(commitTick)
        handler.postDelayed(commitTick, PkgStabilizer.STABLE_MS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForegroundApps.serviceConnected = true
        Probe.write("权限", "无障碍服务已连接（可接收窗口切换）")
    }

    override fun onDestroy() {
        ForegroundApps.serviceConnected = false
        handler.removeCallbacks(commitTick)
        // 进程内状态一起清：服务被销毁后那个包名不再可信，留着会让悬浮窗一上来就被藏住。
        ForegroundApps.reset()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundApps.serviceConnected = false
        handler.removeCallbacks(commitTick)
        stabilizer.reset()
        lastReported = null
        // 清掉残留包名：否则下次开启会先继承一个过期值，悬浮窗可能一上来就被藏住。
        ForegroundApps.reset()
        Probe.write("权限", "无障碍服务被解绑（系统关闭或被杀）")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // 系统要求实现；本服务不提供反馈，无事可做。
    }

    /** 当前输入法包名（含系统默认）。查不到就返回空集，此时不做输入法过滤。 */
    private fun imePackages(): Set<String> {
        imeCache?.let { return it }
        val set = runCatching {
            val imm = getSystemService(InputMethodManager::class.java)
            val enabled = imm?.enabledInputMethodList.orEmpty().map { it.packageName }
            val default = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')
            (enabled + listOfNotNull(default)).toSet()
        }.getOrDefault(emptySet())
        imeCache = set
        return set
    }
}
