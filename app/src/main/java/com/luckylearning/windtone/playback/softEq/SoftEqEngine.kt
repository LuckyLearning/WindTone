package com.luckylearning.windtone.playback.softEq

import kotlin.math.abs

/** Multi-band Soft EQ; gains in mB like AudioFx. Spike only — see docs/spike-软EQ.md */
internal class SoftEqEngine(private val q: Float = 1.0f) {
    private var sampleRate = 0
    private var channelCount = 2
    private var centerHz: IntArray = IntArray(0)
    private var gainsMb: IntArray = IntArray(0)
    private var filters: Array<SoftEqBiquad> = emptyArray()
    private var states: Array<Array<SoftEqBiquad.State>> = emptyArray()
    private var active = false
    val isActive: Boolean get() = active

    fun configure(sampleRateHz: Int, channels: Int, centersHz: IntArray, gainsMb: IntArray) {
        require(centersHz.size == gainsMb.size)
        sampleRate = sampleRateHz
        channelCount = channels.coerceAtLeast(1)
        centerHz = centersHz.copyOf()
        this.gainsMb = gainsMb.copyOf()
        rebuild()
    }
    fun setGainsMb(gainsMb: IntArray) {
        if (gainsMb.size != centerHz.size) return
        this.gainsMb = gainsMb.copyOf()
        if (sampleRate > 0) rebuild()
    }
    fun reset() { for (band in states) for (ch in band) ch.reset() }
    fun processInterleaved(samples: FloatArray, frameCount: Int) {
        if (!active || filters.isEmpty()) return
        val ch = channelCount
        for (i in 0 until frameCount) {
            val base = i * ch
            for (c in 0 until ch) {
                var x = samples[base + c]
                for (b in filters.indices) x = filters[b].process(x, states[b][c])
                samples[base + c] = x
            }
        }
    }
    private fun rebuild() {
        val n = gainsMb.size
        if (n == 0 || sampleRate <= 0) {
            filters = emptyArray(); states = emptyArray(); active = false; return
        }
        filters = Array(n) { i ->
            val db = gainsMb[i] / 100f
            if (abs(db) < 0.05f) SoftEqBiquad.identity()
            else SoftEqBiquad.peaking(sampleRate, centerHz[i].toFloat(), db, q)
        }
        states = Array(n) { Array(channelCount) { SoftEqBiquad.State() } }
        active = gainsMb.any { abs(it) >= 10 }
    }
    companion object {
        val DEFAULT_CENTERS_HZ = intArrayOf(60, 230, 910, 3600, 14000)
    }
}
