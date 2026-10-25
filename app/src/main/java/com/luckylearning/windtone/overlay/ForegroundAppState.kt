package com.luckylearning.windtone.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前前台应用的包名（v1.1.29，设计见 docs/设计-悬浮窗按应用隐藏.md）。
 *
 * 两个「喂料」来源都往这里写，谁先谁后无所谓：
 *  · [com.luckylearning.windtone.overlay.ForegroundAppService]（无障碍服务，事件即时）
 *  · `LyricOverlayService` 里的 UsageStats 轮询（1.5s，兜底）
 *
 * 去重集中在 [report]：只有**真的换了应用**才返回 true，调用方据此写历史 ——
 * 两个来源都报同一个切换时，历史只会记一条（[ForegroundAppLog.shouldRecord] 还有第二道）。
 *
 * 本对象不碰 Context、不碰文件，纯内存状态，单测可直接驱动。
 */
internal object ForegroundAppState {

    private val _currentPkg = MutableStateFlow<String?>(null)

    /**
     * 最近的前台切换（v1.1.86）：内存环形缓冲，最多 [RECENT_MAX] 条，进程结束即清。
     *
     * 为什么需要：车机上「名字选对了但不隐藏」时，必须能回答两个问题 ——
     * 事件到底有没有来？来的时候报的是哪个包？此前只能靠用户开「前台应用记录」
     * 并手动翻，而那个开关默认关，等于现场没有证据。
     */
    data class Entry(val pkg: String, val atMillis: Long)

    private const val RECENT_MAX = 20

    private val recentLog = ArrayDeque<Entry>()

    private val _recentFlow = MutableStateFlow<List<Entry>>(emptyList())

    /**
     * 最近切换的**可观察**版本（v1.1.90）。
     *
     * 诊断页要实时列出「刚才切过哪些应用」，让用户一键把 App 加进隐藏名单 ——
     * 不用先开「前台应用记录」开关（那是落盘历史，默认关），
     * 也不用抄包名去搜索框里粘。
     */
    val recentFlow: StateFlow<List<Entry>> = _recentFlow.asStateFlow()

    /** 最近的前台切换（旧的在前）；供排障报告读取 */
    @Synchronized
    fun recent(): List<Entry> = recentLog.toList()

    /** 当前前台应用包名；null = 还不知道（启动后第一次收到事件前） */
    val currentPkg: StateFlow<String?> = _currentPkg.asStateFlow()

    /**
     * 收到一个「某应用在前台」的信号。
     *
     * @return 是否发生了应用切换（true 时调用方应记一条历史）
     */
    fun report(pkg: String?): Boolean {
        val normalized = pkg?.takeIf { it.isNotBlank() }
        if (normalized == _currentPkg.value) return false
        _currentPkg.value = normalized
        if (normalized != null) {
            com.luckylearning.windtone.diag.DiagLog.log("FG", "前台 → $normalized")
            synchronized(this) {
                recentLog.addLast(Entry(normalized, System.currentTimeMillis()))
                while (recentLog.size > RECENT_MAX) recentLog.removeFirst()
                _recentFlow.value = recentLog.toList()
            }
        }
        return true
    }

    /** 清空（服务销毁 / 用户关掉功能时用，避免残留一个过期包名） */
    fun reset() {
        _currentPkg.value = null
        synchronized(this) {
            recentLog.clear()
            _recentFlow.value = emptyList()
        }
    }
}

/**
 * 这个包名是否只是「输入法弹起」，不该当成前台应用切换（v1.1.90）。
 *
 * 实测（DiLink 诊断包）：在名单里的 360 影像里点搜索框，事件序列会多出
 * `com.iflytek.inputmethod` —— 于是「前台」变成输入法、不再命中名单，
 * 悬浮窗又冒出来，看起来像「隐藏失效」。输入法不是用户要去的地方，
 * 忽略它、保留上一个前台更符合预期。
 */
internal fun shouldIgnoreForegroundEvent(pkg: String?, imePackages: Set<String>): Boolean =
    pkg != null && imePackages.isNotEmpty() && pkg in imePackages

/**
 * 悬浮窗要不要因「前台应用在隐藏名单里」而收起。
 *
 * 纯函数：名单为空时一律 false（功能完全不介入，检测通道都不该启动）。
 */
internal fun overlayHiddenForForeground(pkg: String?, hiddenApps: Set<String>): Boolean =
    pkg != null && hiddenApps.isNotEmpty() && pkg in hiddenApps

/**
 * 前台检测要不要跑。
 *
 * 两个理由才值得跑：① 有隐藏名单要判；② 用户开着「前台应用记录」。
 * 两者都不成立时**一个字节都不查** —— 这是零权限开销的底线。
 */
internal fun foregroundDetectionNeeded(hiddenApps: Set<String>, logEnabled: Boolean): Boolean =
    hiddenApps.isNotEmpty() || logEnabled
