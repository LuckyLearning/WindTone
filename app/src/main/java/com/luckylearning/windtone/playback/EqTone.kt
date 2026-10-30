package com.luckylearning.windtone.playback

import kotlin.math.roundToInt

/**
 * 低音 / 高音快捷旋钮和频段的对应关系。
 *
 *  - **低音**不另做一条曲线，直接驱动 [android.media.audiofx.BassBoost]
 *    （0..1000，和「低音增强」滑块是同一个数）；
 *  - **高音**写成高频搁架：中心频率 ≥ [TREBLE_MIN_HZ] 的每一段都设成同一个增益。
 *    五段机上只有最上面一段落在这个范围里，十段机上 4 kHz 往上的几段一起动。
 *    读数是这些段的平均值，所以旋钮和下面的频段滑块永远是同一条曲线。
 */
object EqTone {

    /** 高音搁架的下限（Hz）。低于此的段不动。 */
    const val TREBLE_MIN_HZ = 4000

    /** 低音快捷键一档 = 10%（BassBoost 量程 0..1000） */
    const val BASS_STEP = 100

    /** 高音快捷键一档 = 1.5 dB */
    const val TREBLE_STEP_MB = 150

    fun stepBass(current: Int, delta: Int, max: Int = 1000): Int =
        (current + delta).coerceIn(0, max)

    fun stepTreble(currentMb: Int, deltaMb: Int, minMb: Int, maxMb: Int): Int =
        (currentMb + deltaMb).coerceIn(minMb, maxMb)

    /**
     * 高音要写的段。没有一段 ≥ [TREBLE_MIN_HZ] 时退到最后一段，
     * 避免「点了高音什么都没发生」。
     */
    fun highBandIndices(centerHz: List<Int>): List<Int> {
        if (centerHz.isEmpty()) return emptyList()
        val highs = centerHz.indices.filter { centerHz[it] >= TREBLE_MIN_HZ }
        return if (highs.isNotEmpty()) highs else listOf(centerHz.lastIndex)
    }

    /** 当前曲线上的高音读数（mB）。几段不一致时取平均，点一下会收成搁架。 */
    fun readTrebleMb(levels: IntArray, centerHz: List<Int>): Int {
        val idx = highBandIndices(centerHz).filter { it in levels.indices }
        if (idx.isEmpty()) return 0
        val sum = idx.sumOf { levels[it] }
        return (sum.toDouble() / idx.size).roundToInt()
    }

    /**
     * 把高音搁架写进曲线。低频 / 中频原样返回，所以和「只动高音」的听感一致。
     */
    fun applyTreble(
        levels: IntArray,
        centerHz: List<Int>,
        gainMb: Int,
        minMb: Int,
        maxMb: Int,
    ): IntArray {
        if (levels.isEmpty()) return levels
        val idx = highBandIndices(centerHz).filter { it in levels.indices }.toSet()
        if (idx.isEmpty()) return levels
        val g = gainMb.coerceIn(minMb, maxMb)
        return IntArray(levels.size) { i -> if (i in idx) g else levels[i] }
    }
}
