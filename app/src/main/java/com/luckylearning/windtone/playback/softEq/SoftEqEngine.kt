package com.luckylearning.windtone.playback.softEq

import kotlin.math.abs

/** Multi-band Soft EQ; gains in mB like AudioFx. See SoftEqPath / docs/功能-软EQ.md */
internal class SoftEqEngine(private val q: Float = 1.0f) {
    private var sampleRate = 0
    private var channelCount = 2
    private var centerHz: IntArray = IntArray(0)
    private var gainsMb: IntArray = IntArray(0)

    /**
     * 一次完整的处理状态：滤波器系数 + **每条滤波器 × 每个声道**的状态 + 声道数。
     *
     * 三样东西必须同生同灭。原先它们是三个独立的字段，`rebuild()` 先写 `filters`
     * 再写 `states`：音频线程（`processInterleaved`）若在这两次赋值之间读，就会拿到
     * 「新系数 + 旧状态数组」—— 换频段布局时首维长度不同、换声道数时内维长度不同，
     * `states[b][c]` 直接越界（异常抛在音频线程上，播放立刻中断）；长度恰好相同时
     * 不越界但会用错滤波器状态，表现为切曲线瞬间的爆音 / 失真。
     *
     * 现在整体是不可变对象，`rebuild()` 只替换引用（`@Volatile`），音频线程每次
     * 处理前取一次快照，永远看到自洽的一组。
     */
    private class Snapshot(
        val channelCount: Int,
        val filters: Array<SoftEqBiquad>,
        val states: Array<Array<SoftEqBiquad.State>>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    val isActive: Boolean get() = snapshot != null

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

    fun reset() {
        snapshot?.states?.forEach { band -> band.forEach { it.reset() } }
    }

    fun processInterleaved(samples: FloatArray, frameCount: Int) {
        // 一次取快照：本次处理期间哪怕 UI 侧重建了曲线，用的也是同一组自洽的系数与状态
        val snap = snapshot ?: return
        val ch = snap.channelCount
        val filters = snap.filters
        val states = snap.states
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
            snapshot = null
            return
        }
        val filters = Array(n) { i ->
            val db = gainsMb[i] / 100f
            if (abs(db) < 0.05f) SoftEqBiquad.identity()
            else SoftEqBiquad.peaking(sampleRate, centerHz[i].toFloat(), db, q)
        }
        // 全部频段都接近 0 dB 时整条链视为不活跃（调用方据此把 processor 从链路摘下）
        val active = gainsMb.any { abs(it) >= 10 }
        snapshot = if (active) {
            Snapshot(
                channelCount = channelCount,
                filters = filters,
                states = Array(n) { Array(channelCount) { SoftEqBiquad.State() } },
            )
        } else {
            null
        }
    }

    companion object {
        val DEFAULT_CENTERS_HZ = intArrayOf(60, 230, 910, 3600, 14000)
    }
}
