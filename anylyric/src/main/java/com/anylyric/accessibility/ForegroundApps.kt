package com.anylyric.accessibility

/**
 * 当前前台应用包名（0.2.11 新增，配合「按应用隐藏悬浮窗」）。
 *
 * 只有一个喂料来源：[ForegroundAppService]（无障碍服务）。它独立于界面活着 ——
 * 悬浮窗的价值全在后台时段，界面根本没开的时候也得知道「前台是谁」。
 *
 * 本对象不碰 Context、不碰文件，纯内存状态，单测可直接驱动。
 */
object ForegroundApps {

    /** 当前前台包名；null = 还不知道（服务连上后第一次窗口切换之前） */
    @Volatile
    var currentPkg: String? = null
        private set

    /**
     * 最近的前台切换（0.2.12）：内存环形，最多 [RECENT_MAX] 条，进程结束即清。
     *
     * 存在的理由很实在：车机上「勾了却不隐藏」时，必须先回答「切过去时前台到底是哪个包」。
     * 以前只能靠探测报告的 `[HIDE] foreground=` 事后猜，而那个值在服务没上报时是 unknown。
     * 现在设置页能直接看到这份列表，并一键把它加进隐藏名单。
     *
     * **不落盘**：这是现场证据，不是历史档案；写文件既要权限也会长跑膨胀。
     */
    data class Entry(val pkg: String, val atMillis: Long)

    private const val RECENT_MAX = 20

    private val recentLog = ArrayDeque<Entry>()

    /** 最近的前台切换（旧的在前）。返回副本，调用方可随意遍历。 */
    @Synchronized
    fun recent(): List<Entry> = recentLog.toList()

    /**
     * 无障碍服务**此刻是否真的连着**（由 [ForegroundAppService] 维护）。
     *
     * 与「系统设置里那个勾打没打」是两件事：车机上服务被杀、勾还留着是常态。
     * 只活在本进程 —— 进程重启后一定是 false，要等服务重新连上才变 true，
     * 这正是「真的在跑」该有的语义。
     */
    @Volatile
    var serviceConnected: Boolean = false

    /**
     * 服务实际订阅的事件类型位掩码（`AccessibilityServiceInfo.eventTypes`）。
     *
     * 报告里要能看到它是不是含 `TYPE_WINDOW_STATE_CHANGED`（32）：
     * 「服务连着却一个事件都没来」时，这是唯一能区分「订阅没生效」与「系统不派发」的证据。
     */
    @Volatile
    var serviceEventTypes: Int = 0

    /**
     * 包名变化监听。
     *
     * 用 `CopyOnWriteArrayList`：注册/注销只发生在会话创建与销毁这种低频时刻，
     * 而回调是每次应用切换都要走的路径 —— 读多写少，正好。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String?) -> Unit>()

    fun addListener(listener: (String?) -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: (String?) -> Unit) {
        listeners -= listener
    }

    /**
     * 报告一个**已稳定**的前台包名（滞回由 [PkgStabilizer] 在服务里做）。
     *
     * @return 是否真的换了应用（调用方据此决定要不要重算隐藏态）
     */
    fun report(pkg: String?): Boolean {
        val normalized = pkg?.takeIf { it.isNotBlank() }
        if (normalized == currentPkg) return false
        currentPkg = normalized
        if (normalized != null) {
            synchronized(this) {
                recentLog.addLast(Entry(normalized, System.currentTimeMillis()))
                while (recentLog.size > RECENT_MAX) recentLog.removeFirst()
            }
        }
        // 单个监听器抛异常不能影响其它监听器（也不能把服务带崩）
        for (listener in listeners) {
            runCatching { listener(normalized) }
        }
        return true
    }

    /** 服务被解绑 / 用户关掉功能时清空，避免残留一个过期包名导致悬浮窗一直不显示。 */
    fun reset() {
        report(null)
        synchronized(this) { recentLog.clear() }
    }
}

/**
 * 这个包名是否只是「输入法弹起」，不该当成前台应用切换。
 *
 * 真机教训（WindTone DiLink 诊断包）：在名单应用里点搜索框，事件序列会多出
 * 输入法包名 —— 于是「前台」变成输入法、不再命中名单，悬浮窗又冒出来，
 * 看起来像「隐藏失效」。输入法不是用户要去的地方，忽略它更符合预期。
 */
internal fun shouldIgnoreForegroundEvent(pkg: String?, imePackages: Set<String>): Boolean =
    pkg != null && imePackages.isNotEmpty() && pkg in imePackages

/**
 * 悬浮窗要不要因「前台应用在名单里」而收起。
 *
 * 纯函数：名单为空时一律 false（功能完全不介入）。「本应用自己的界面」不在这里判 ——
 * 那条走 Activity 生命周期，不需要任何权限，也不受这个开关影响。
 */
internal fun overlayHiddenFor(pkg: String?, hiddenApps: Set<String>): Boolean =
    pkg != null && hiddenApps.isNotEmpty() && pkg in hiddenApps

/**
 * 前台检测要不要跑。
 *
 * 名单为空且用户没开「按应用隐藏」时，事件处理**一个字节都不查** ——
 * 这是零权限、零开销的底线；服务即使被用户开着，也只是一次早退。
 */
internal fun foregroundDetectionNeeded(enabled: Boolean, hiddenApps: Set<String>): Boolean =
    enabled && hiddenApps.isNotEmpty()
