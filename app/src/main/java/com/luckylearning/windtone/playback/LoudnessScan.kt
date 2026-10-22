package com.luckylearning.windtone.playback

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 曲间响度平衡的**纯数学**（v1.1.52）。
 *
 * ## 原理与边界
 * 对曲目样本算 RMS（均方根），与目标响度做差得到增益；播放时把增益
 * 换算成**播放器音量因子**乘上去。v1 是**衰减制**：只压响度大的歌，
 * 不抬响度小的 —— 因为 `ExoPlayer.volume` 只有 0..1 一档可调，抬升要靠
 * `LoudnessEnhancer`，而它跟用户手调的「响度增强」共用一条效果链，
 * 两处互相覆盖会把设置页搞乱。衰减制已经解决了真正的痛点
 * （「切歌突然炸耳」），抬升留给后续版本再议。
 *
 * 目标 -20 dBFS（RMS 口径）是对流行音乐响度的保守取值：
 * 现代流行的整轨 RMS 普遍在 -12 ~ -18 dBFS，取 -20 意味着大多数
 * 响的歌会被压 2~8 dB，而古典 / 现场这类本来响度低的录音不会被压。
 */
object LoudnessScan {

    /** 目标整轨响度（RMS，dBFS） */
    const val TARGET_DBFS = -20.0

    /** 单曲增益的下限（dB）：再大的差也最多压 20 dB，避免把录音压没 */
    const val MIN_GAIN_DB = -20.0

    /** 单曲增益的上限（dB）：留作未来「抬升」的语义位，衰减制下不会用到正值 */
    const val MAX_GAIN_DB = 12.0

    /** 数字静音的替身值：全 0 样本的 RMS 是 -Inf，钳到这个值参与计算 */
    const val SILENCE_DBFS = -90.0

    /**
     * 样本 → 整轨代表响度（dBFS，RMS 口径）。
     *
     * [samples] 是**混到单声道后**的 16-bit PCM。全 0（数字静音）返回
     * [SILENCE_DBFS] 而不是 -Inf：静音不该拿到无限的增益。
     */
    fun rmsDbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return SILENCE_DBFS
        var sumSquares = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSquares += v * v
        }
        val meanSquare = sumSquares / samples.size / (32768.0 * 32768.0)
        if (meanSquare <= 0.0) return SILENCE_DBFS
        return 10.0 * log10(meanSquare)
    }

    /** 实测响度 → 应施加的增益（dB）。差值越大压得越多，夹在 [MIN_GAIN_DB]..[MAX_GAIN_DB]。 */
    fun gainDb(measuredDbfs: Double): Double =
        (TARGET_DBFS - measuredDbfs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    /**
     * 增益（dB）→ 播放器音量因子（0..1）。
     *
     * 衰减制：正增益（该抬的）一律返回 1 —— 抬不了就别动；
     * 负增益按 `10^(gain/20)` 换算（dB 是对数单位，音量因子是线性倍率）。
     */
    fun attenuationFactor(gainDb: Double): Float {
        if (gainDb >= 0.0) return 1f
        return 10.0.pow(gainDb / 20.0).toFloat().coerceIn(0f, 1f)
    }

    /** 便捷入口：缓存的增益（mB）直接换算音量因子 */
    fun attenuationFactorMb(gainMb: Int): Float = attenuationFactor(gainMb / 100.0)

    /** 一段实测量（RMS 平方和 + 样本数）→ 增益 mB。供解码侧流式累计后收口。 */
    fun gainMbFromAccumulator(sumSquares: Double, sampleCount: Int): Int? {
        if (sampleCount <= 0) return null
        val meanSquare = sumSquares / sampleCount / (32768.0 * 32768.0)
        val dbfs = if (meanSquare <= 0.0) SILENCE_DBFS else 10.0 * log10(meanSquare)
        return (gainDb(dbfs) * 100).roundToInt()
    }
}
