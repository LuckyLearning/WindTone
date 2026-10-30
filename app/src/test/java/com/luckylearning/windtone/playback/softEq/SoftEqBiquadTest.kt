package com.luckylearning.windtone.playback.softEq

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftEqBiquadTest {
    @Test fun `0 dB 峰形接近直通`() {
        val b = SoftEqBiquad.peaking(48_000, 1000f, 0f)
        assertEquals(0.5f, b.process(0.5f, SoftEqBiquad.State()), 1e-3f)
    }
    @Test fun `1 kHz 抬 6 dB 时中心能量明显高于 100 Hz`() {
        val engine = SoftEqEngine()
        engine.configure(48_000, 1, intArrayOf(1000), intArrayOf(600))
        assertTrue(engine.isActive)
        val center = rms(engine, 1000.0); val side = rms(engine, 100.0)
        assertTrue("c=$center s=$side", center > side * 1.5f)
        assertTrue(side in 0.25f..0.45f)
    }
    @Test fun `全 0 增益时 engine 不 active`() {
        val e = SoftEqEngine()
        e.configure(48_000, 2, SoftEqEngine.DEFAULT_CENTERS_HZ, IntArray(SoftEqEngine.DEFAULT_CENTERS_HZ.size))
        assertFalse(e.isActive)
    }
    @Test fun `Spike 开关默认关`() { assertFalse(SoftEqSpike.ENABLED) }
    private fun rms(engine: SoftEqEngine, freqHz: Double): Float {
        engine.reset()
        val frames = 48_000; val buf = FloatArray(frames)
        val w = 2.0 * PI * freqHz / 48_000.0
        for (i in 0 until frames) buf[i] = (0.5 * sin(w * i)).toFloat()
        engine.processInterleaved(buf, frames)
        var sum = 0.0; val start = 2048
        for (i in start until frames) sum += buf[i] * buf[i]
        return sqrt(sum / (frames - start)).toFloat()
    }
}
