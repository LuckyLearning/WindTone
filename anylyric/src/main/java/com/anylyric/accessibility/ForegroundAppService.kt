package com.anylyric.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.anylyric.data.Prefs
import com.anylyric.diag.Probe
import com.anylyric.session.SessionHub

/** 提交未成功时的补排间隔：只需跨过 Handler 与 elapsedRealtime 的几毫秒误差。 */
private const val COMMIT_RETRY_MS = 120L

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

    /**
     * 用**单调时钟**（`elapsedRealtime`）而不是墙钟：系统对时、跨时区、休眠恢复
     * 都可能让 `currentTimeMillis` 跳一下，跳负了滞回就永远提交不了。
     */
    private val stabilizer = PkgStabilizer(clock = { SystemClock.elapsedRealtime() })

    /** 懒加载一次就复用：事件频率虽低，但每来一次就新建一个 Prefs 只是白分配。 */
    private val prefs: Prefs by lazy { Prefs(applicationContext) }

    /** 输入法包名缓存：一次会话里几乎不变，没必要每个事件都查一遍系统服务。 */
    private var imeCache: Set<String>? = null

    /** 上一次真正上报过的稳定包名，用来挡掉重复上报。 */
    private var lastReported: String? = null

    /** 上一次处理过的媒体通知内容（标题 + 正文），用来挡掉播放器每秒重发的那一批。 */
    private var lastNotificationKey: String? = null

    /**
     * 候选包名稳定 450ms 后提交。
     *
     * 必须靠这个延迟回调：`onRaw` 只更新候选，没有新事件时没人推进时钟，
     * 候选就永远提交不了（表现为「切了应用但不隐藏」）。
     *
     * 提交失败时**再排一次**（[PkgStabilizer.hasPending]）：`postDelayed` 的基准是
     * uptime、判定用的是 elapsedRealtime，两者可能差几毫秒 —— 只排一次的话，
     * 那几毫秒就足以让候选永久卡住，这正是 0.2.11 真机 `foreground=unknown` 的成因之一。
     */
    private val commitTick = object : Runnable {
        override fun run() {
            val stable = stabilizer.tick()
            if (stable != lastReported) {
                lastReported = stable
                if (Probe.enabled) Probe.write("前台", "稳定 → ${stable ?: "未知"}")
                ForegroundApps.report(stable)
                return
            }
            if (stabilizer.hasPending()) handler.postDelayed(this, COMMIT_RETRY_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 媒体通知先处理：它不受「按应用隐藏是否开启」影响 —— 那条权限被车厂拦掉时，
        // 这条通道是唯一还能拿到歌名的路，不能因为用户没开隐藏功能就被跳过。
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            onNotificationEvent(event)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 开关关着 / 名单为空：一次早退，连包名都不解析。
        // 服务可能是用户早就开着、后来清空了名单，这时不该继续做无用功。
        if (!foregroundDetectionNeeded(prefs.hideByAppEnabled, prefs.hiddenApps)) return

        val pkg = runCatching { event.packageName?.toString() }.getOrNull() ?: return
        if (shouldIgnoreForegroundEvent(pkg, imePackages())) return
        // 调试开着时留一条：真机上「一个事件都没来」和「来了但没提交」是两种完全不同的故障。
        if (Probe.enabled) Probe.write("前台", "事件 $pkg")

        stabilizer.onRaw(pkg)
        handler.removeCallbacks(commitTick)
        handler.postDelayed(commitTick, PkgStabilizer.STABLE_MS)
    }

    /**
     * 媒体通知 → 歌名 / 歌手（或逐行歌词）。
     *
     * **只处理媒体通知**：其余通知在 `isMedia` 判断处就返回，一个字段都不读。
     * 这是订阅 `typeNotificationStateChanged` 的唯一理由，也是它的边界 ——
     * 微信、短信、验证码这类通知永远进不到这里。
     */
    private fun onNotificationEvent(event: AccessibilityEvent) {
        val pkg = runCatching { event.packageName?.toString() }.getOrNull() ?: return
        if (pkg == packageName) return
        val notification = runCatching {
            event.parcelableData as? Notification
        }.getOrNull() ?: return
        val extras = notification.extras ?: return
        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (!isMedia) return

        val content = MediaNotification.split(
            extras.getCharSequence(Notification.EXTRA_TITLE) ?: event.text.firstOrNull(),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )
        if (content.title == null && content.body == null) return
        // 播放器每秒重发同一条通知（进度变化也算变化）：内容没变就不必再跑一遍刷新。
        val key = "${content.title}\u0000${content.body}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        if (Probe.enabled) {
            Probe.write("通知通道", "$pkg title=${content.title} body=${content.body?.length ?: 0}字")
        }
        SessionHub.get(this).onA11yNotification(pkg, content.title, content.body)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForegroundApps.serviceConnected = true
        // 连上过一次 = 用户确实开过这个服务。记下来，以后系统把它关掉时
        // AccessibilityWatchdog 才有依据去尝试自动写回（否则等于替没开过的人做主）。
        runCatching { prefs.accessibilityRequested = true }
        // XML 里已经声明过一次订阅面，这里**再设一遍**：真机上见过 ROM 覆盖或
        // 资源解析导致 `accessibilityEventTypes` 没生效的情况 —— 那时服务是连着的
        // （a11yConnected=true）却一个窗口事件都收不到，探针表现正是 foreground=unknown。
        // 只在连接时跑一次，不进热路径。
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            if (info.feedbackType == 0) info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            serviceInfo = info
            ForegroundApps.serviceEventTypes = info.eventTypes
        }
        Probe.write("前台", "无障碍服务已连接，订阅 eventTypes=${ForegroundApps.serviceEventTypes}")
    }

    override fun onDestroy() {
        ForegroundApps.serviceConnected = false
        handler.removeCallbacks(commitTick)
        lastNotificationKey = null
        // 进程内状态一起清：服务被销毁后那个包名不再可信，留着会让悬浮窗一上来就被藏住。
        ForegroundApps.reset()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundApps.serviceConnected = false
        handler.removeCallbacks(commitTick)
        stabilizer.reset()
        lastReported = null
        lastNotificationKey = null
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
