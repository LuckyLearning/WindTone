package com.luckylearning.windtone.playback

import kotlin.math.pow

/**
 * 变调（pitch）。
 *
 * Media3 的 `PlaybackParameters(speed, pitch)` 用**倍率**表示音高（1.0 = 原调），
 * 但倍率对用户没有意义 —— 没人知道「0.89x」是降两个半音。
 * 所以**界面文案与持久化一律用半音（semitone）**，只在写进播放器那一刻换算成倍率。
 *
 * 换算基准是十二平均律：`倍率 = 2^(半音/12)`，±12 半音正好是一个八度（0.5x ~ 2.0x）。
 * 数组预先把 25 个档位的倍率算好，避免每次 tick 都做一次 `pow`。
 */
object Pitch {

    /** 降一个八度 */
    const val MIN_SEMITONES = -12

    /** 升一个八度 */
    const val MAX_SEMITONES = 12

    private val STEPS: FloatArray = FloatArray(MAX_SEMITONES - MIN_SEMITONES + 1) { i ->
        2.0.pow((i + MIN_SEMITONES) / 12.0).toFloat()
    }

    /**
     * 半音 → 播放器要的倍率。
     *
     * 越界一律夹取（而不是抛异常）：值可能来自旧版 Prefs 或界面滑块的边界抖动，
     * 静默夹取比崩掉合适 —— 与 `setSpeed` 的处理口径一致。
     */
    fun factorOf(semitones: Int): Float = STEPS[semitones.coerceIn(MIN_SEMITONES, MAX_SEMITONES) - MIN_SEMITONES]

    /** 界面文案。用 U+2212 之外的原生负号即可，但正数要显式带 `+`（否则「2 半音」看不出升降） */
    fun labelOf(semitones: Int): String = when {
        semitones == 0 -> "原调"
        semitones > 0 -> "+$semitones 半音"
        else -> "$semitones 半音"
    }

    /** 是否偏离默认（界面用它决定摆不摆 ↺ 按钮） */
    fun isDefault(semitones: Int): Boolean = semitones == 0
}
