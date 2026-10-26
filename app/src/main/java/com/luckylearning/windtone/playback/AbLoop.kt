package com.luckylearning.windtone.playback

/**
 * A-B 区间循环（C2）纯状态机。
 *
 * 与 Media3 变速兼容：比较的是媒体时间轴上的 [positionMs]（ExoPlayer.currentPosition），
 * 不是墙钟；变速只改变到达 B 的快慢，不改变区间语义。
 *
 * D1（逐句循环）扩展点：用 [fromInterval] 把一句歌词的起止灌进同一引擎即可，
 * 不必另开一套 seek 逻辑。
 */
data class AbLoopState(
    val aMs: Long? = null,
    val bMs: Long? = null,
) {
    /** 两端都在且 B 严格大于 A 时才真正循环 */
    val armed: Boolean
        get() {
            val a = aMs
            val b = bMs
            return a != null && b != null && b > a
        }

    fun clear(): AbLoopState = AbLoopState()

    /**
     * 记 A 点。若原 B ≤ 新 A，丢掉 B（避免倒挂区间）。
     */
    fun markA(positionMs: Long): AbLoopState {
        val a = positionMs.coerceAtLeast(0L)
        val b = bMs?.takeIf { it > a }
        return copy(aMs = a, bMs = b)
    }

    /**
     * 记 B 点。尚未设 A、或位置 ≤ A 时保持原状（调用方可用 [canMarkB] 提示）。
     */
    fun markB(positionMs: Long): AbLoopState {
        val a = aMs ?: return this
        val b = positionMs.coerceAtLeast(0L)
        if (b <= a) return this
        return copy(bMs = b)
    }

    fun canMarkB(positionMs: Long): Boolean {
        val a = aMs ?: return false
        return positionMs > a
    }

    /**
     * 若当前位置已到或越过 B，返回应 seek 回的 A；否则 null。
     * 留给 D1：同一函数，只是 A/B 来自歌词行。
     */
    fun seekIfNeeded(positionMs: Long): Long? {
        if (!armed) return null
        val b = bMs!!
        val a = aMs!!
        return if (positionMs >= b) a else null
    }

    companion object {
        /** D1 扩展：一句 / 任意区间 → 同一引擎 */
        fun fromInterval(startMs: Long, endMs: Long): AbLoopState {
            val start = startMs.coerceAtLeast(0L)
            val end = endMs.coerceAtLeast(0L)
            return if (end > start) AbLoopState(aMs = start, bMs = end) else AbLoopState()
        }
    }
}
