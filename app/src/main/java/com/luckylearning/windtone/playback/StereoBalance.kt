package com.luckylearning.windtone.playback

/**
 * 左右声道平衡（#9）纯函数。
 *
 * [balance] 取值 -100..100：负数偏左、正数偏右、0 居中。
 * 增益采用「保留较响一侧为 1」的经典算法，避免整轨再被压小。
 * 不碰 [AudioFx] 效果器链；真正乘在 PCM 上的是 [StereoBalanceFx]（Media3
 * [androidx.media3.common.audio.ChannelMixingAudioProcessor]）。
 */
object StereoBalance {

    const val MIN = -100
    const val MAX = 100
    const val DEFAULT = 0

    fun coerce(balance: Int): Int = balance.coerceIn(MIN, MAX)

    /** 左声道线性增益 0..1 */
    fun leftGain(balance: Int): Float {
        val b = coerce(balance)
        return if (b <= 0) 1f else (1f - b / 100f)
    }

    /** 右声道线性增益 0..1 */
    fun rightGain(balance: Int): Float {
        val b = coerce(balance)
        return if (b >= 0) 1f else (1f + b / 100f)
    }

    /** 2×2 对角混音系数：L→L, L→R, R→L, R→R */
    fun mixingCoefficients(balance: Int): FloatArray {
        val l = leftGain(balance)
        val r = rightGain(balance)
        return floatArrayOf(l, 0f, 0f, r)
    }

    fun isCentered(balance: Int): Boolean = coerce(balance) == DEFAULT

    /** UI 旁注：居中 / 偏左 n / 偏右 n */
    fun valueText(balance: Int): String {
        val b = coerce(balance)
        return when {
            b == 0 -> "居中"
            b < 0 -> "偏左 ${-b}"
            else -> "偏右 $b"
        }
    }
}
