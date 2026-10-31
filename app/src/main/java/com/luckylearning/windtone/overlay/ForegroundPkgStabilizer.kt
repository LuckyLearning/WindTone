package com.luckylearning.windtone.overlay

/**
 * 前台包名滞回（v1.2.43）：短时闪烁不改「隐藏名单」判定用的包名。
 *
 * 车机（DiLink）上常见：地图 ↔ 听风 时窗口事件会短暂冒出 `com.byd.avc` /
 * gesture / sr 等包名，若立刻按名单收起悬浮窗，就会「显隐狂闪」。
 * 本类要求候选包名连续稳定 [stableMs] 才提交；闪一下就回去的包永远进不了判定。
 *
 * 真切到名单应用（例如打开 360）并停留超过窗口后，仍会隐藏——不是放行名单。
 * [ForegroundAppState.currentPkg] 仍即时更新（诊断 / 历史不受影响）；
 * 只有悬浮窗显隐走稳定包名。
 *
 * 时钟可注入，单测不靠真实 sleep。
 */
internal class ForegroundPkgStabilizer(
    private val stableMs: Long = STABLE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var candidate: String? = null
    private var candidateSinceMs: Long = 0L
    private var stable: String? = null

    /** 当前用于隐藏名单判定的稳定包名（可能仍是旧值）。 */
    fun stablePkg(): String? {
        tryCommit(clock())
        return stable
    }

    /**
     * 收到原始前台包名。
     * @return 此刻用于隐藏判定的稳定包名
     */
    fun onRaw(pkg: String?): String? {
        val normalized = pkg?.takeIf { it.isNotBlank() }
        val now = clock()
        if (normalized != candidate) {
            candidate = normalized
            candidateSinceMs = now
        }
        tryCommit(now)
        return stable
    }

    /** 仅推进时钟（无新事件时），检查候选是否已够稳定可提交。 */
    fun tick(): String? {
        tryCommit(clock())
        return stable
    }

    fun reset() {
        candidate = null
        candidateSinceMs = 0L
        stable = null
    }

    private fun tryCommit(now: Long) {
        if (candidate == stable) return
        if (now - candidateSinceMs >= stableMs) {
            stable = candidate
        }
    }

    companion object {
        /**
         * 稳定窗口：短于常见系统瞬切（几十～两百毫秒），又明显短于
         * UsageStats 轮询 1.5s，真开 360 时体感仍是「很快收起」。
         */
        const val STABLE_MS = 450L
    }
}
