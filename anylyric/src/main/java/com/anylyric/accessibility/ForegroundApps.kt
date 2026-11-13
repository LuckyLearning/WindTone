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
     * 无障碍服务**此刻是否真的连着**（由 [ForegroundAppService] 维护）。
     *
     * 与「系统设置里那个勾打没打」是两件事：车机上服务被杀、勾还留着是常态。
     * 只活在本进程 —— 进程重启后一定是 false，要等服务重新连上才变 true，
     * 这正是「真的在跑」该有的语义。
     */
    @Volatile
    var serviceConnected: Boolean = false

    /**
     * 包名变化监听。
     *
     * 用 `CopyOnWriteArrayList`：注册/注销只发生在服务建连与销毁这种低频时刻，
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
        // 单个监听器抛异常不能影响其它监听器（也不能把服务带崩）
        for (listener in listeners) {
            runCatching { listener(normalized) }
        }
        return true
    }

    /** 服务被解绑 / 用户关掉功能时清空，避免残留一个过期包名导致悬浮窗一直不显示。 */
    fun reset() {
        report(null)
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
