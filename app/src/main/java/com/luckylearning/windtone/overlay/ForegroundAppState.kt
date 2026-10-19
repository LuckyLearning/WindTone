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
        return true
    }

    /** 清空（服务销毁 / 用户关掉功能时用，避免残留一个过期包名） */
    fun reset() {
        _currentPkg.value = null
    }
}

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
