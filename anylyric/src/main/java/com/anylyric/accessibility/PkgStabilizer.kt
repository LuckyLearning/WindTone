package com.anylyric.accessibility

/**
 * 前台包名滞回：短时闪烁不改「隐藏名单」的判定结果。
 *
 * 车机上很常见：应用来回切时窗口事件会短暂冒出系统浮层、手势层、输入法之类的包名，
 * 若一收到就按名单判定，悬浮窗会「显隐狂闪」。本类要求候选包名连续稳定
 * [stableMs] 才提交；闪一下就回去的包永远进不了判定。
 *
 * 时钟可注入，单测不靠真实 sleep。
 *
 * @param stableMs 稳定窗口。450ms 短于常见系统瞬切（几十～两百毫秒），
 *   又足够短，真切到名单应用时体感仍是「很快收起」。
 */
internal class PkgStabilizer(
    private val stableMs: Long = STABLE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var candidate: String? = null
    private var candidateSinceMs = 0L
    private var stable: String? = null

    /** 当前用于隐藏判定的稳定包名（可能仍是旧值）。 */
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

    /** 仅推进时钟（没有新事件时），检查候选是否已够稳定可提交。 */
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
        const val STABLE_MS = 450L
    }
}
