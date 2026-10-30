package com.luckylearning.windtone.playback.softEq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Soft-EQ Spike: RBJ peaking biquad. See docs/spike-软EQ.md */
internal data class SoftEqBiquad(
    val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float,
) {
    class State {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
    }
    fun process(sample: Float, s: State): Float {
        val y = b0 * sample + b1 * s.x1 + b2 * s.x2 - a1 * s.y1 - a2 * s.y2
        s.x2 = s.x1; s.x1 = sample; s.y2 = s.y1; s.y1 = y
        return y
    }
    companion object {
        fun peaking(sampleRateHz: Int, centerHz: Float, gainDb: Float, q: Float = 1.0f): SoftEqBiquad {
            require(sampleRateHz > 0)
            val f0 = centerHz.coerceIn(20f, sampleRateHz * 0.45f)
            val A = 10.0.pow((gainDb / 40.0)).toFloat()
            val w0 = (2.0 * PI * f0 / sampleRateHz).toFloat()
            val cosW = cos(w0.toDouble()).toFloat()
            val sinW = sin(w0.toDouble()).toFloat()
            val alpha = sinW / (2f * q.coerceAtLeast(0.1f))
            val b0 = 1f + alpha * A
            val b1 = -2f * cosW
            val b2 = 1f - alpha * A
            val a0 = 1f + alpha / A
            val a1 = -2f * cosW
            val a2 = 1f - alpha / A
            return SoftEqBiquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
        fun identity(): SoftEqBiquad = SoftEqBiquad(1f, 0f, 0f, 0f, 0f)
    }
}
