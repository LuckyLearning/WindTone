package com.luckylearning.windtone.playback

/**
 * N2：快进 / 快退目标位置（纯函数）。
 *
 * UI 双按钮固定 ±10s / ±15s；引擎只认毫秒增量，边界夹到 [0, duration]。
 * duration 未知（≤0）时只能夹到 0（不允许负进度）。
 */
object SeekJump {
    /** 播放页提供的两档秒数（双按钮，不是单档可配置）。 */
    val PRESET_SECONDS: IntArray = intArrayOf(10, 15)

    fun deltaMs(seconds: Int): Long = seconds.toLong() * 1_000L

    /**
     * @param positionMs 当前进度（负值按 0）
     * @param durationMs 总时长；未知或负值按 0，目标只能为 0
     * @param deltaMs 可正可负（+ 快进 / − 快退）
     */
    fun target(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        val dur = durationMs.coerceAtLeast(0L)
        val pos = positionMs.coerceAtLeast(0L)
        return (pos + deltaMs).coerceIn(0L, dur)
    }
}
