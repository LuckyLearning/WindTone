package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [LoudnessScan] 的数学契约：RMS 换算、增益夹取、衰减因子。
 *
 * 用**已知振幅的正弦波**当基准：满幅正弦的 RMS 是 -3.01 dBFS（教科书值），
 * 0.1 满幅是 -23.01 dBFS —— 断言对着这两个锚点写，公式改坏立刻红。
 */
class LoudnessScanTest {

    /** 生成整幅正弦波（16-bit Short 单位） */
    private fun sine(amplitude: Double, samples: Int = 48000): ShortArray =
        ShortArray(samples) { i -> (amplitude * sin(2.0 * PI * i / 100.0)).toInt().toShort() }

    @Test
    fun `满幅正弦约等于 -3 dBFS`() {
        val dbfs = LoudnessScan.rmsDbfs(sine(32767.0))
        assertEquals("满幅正弦的 RMS 应为 -3.01 dBFS 附近", -3.01, dbfs, 0.1)
    }

    @Test
    fun `十分之一幅正弦低约 20 dB`() {
        val loud = LoudnessScan.rmsDbfs(sine(32767.0))
        val quiet = LoudnessScan.rmsDbfs(sine(3276.7))
        assertEquals("振幅 ÷10 → RMS 应低 20 dB", -20.0, quiet - loud, 0.1)
    }

    @Test
    fun `数字静音返回替身值而不是负无穷`() {
        assertEquals(LoudnessScan.SILENCE_DBFS, LoudnessScan.rmsDbfs(ShortArray(1000)), 1e-9)
        assertEquals(LoudnessScan.SILENCE_DBFS, LoudnessScan.rmsDbfs(ShortArray(0)), 1e-9)
    }

    @Test
    fun `增益按目标差值计算`() {
        // 满幅 -3 dBFS，目标 -20 → 压 17 dB
        assertEquals(-17.0, LoudnessScan.gainDb(-3.0), 0.01)
        // 比目标安静的：正增益（衰减制下会被忽略）
        assertEquals(3.0, LoudnessScan.gainDb(-23.0), 0.01)
    }

    @Test
    fun `增益夹在上下限内`() {
        assertEquals(LoudnessScan.MIN_GAIN_DB, LoudnessScan.gainDb(0.0), 1e-9)
        assertEquals(LoudnessScan.MAX_GAIN_DB, LoudnessScan.gainDb(LoudnessScan.SILENCE_DBFS), 1e-9)
    }

    @Test
    fun `衰减因子只压不抬`() {
        assertEquals("正增益不该动音量（抬不了）", 1f, LoudnessScan.attenuationFactor(6.0))
        // -20 dB → 0.1 倍
        assertEquals(0.1f, LoudnessScan.attenuationFactor(-20.0), 1e-4f)
        assertEquals(0f, LoudnessScan.attenuationFactor(-100.0), 1e-4f)
    }

    @Test
    fun `mB 便捷入口与 dB 口径一致`() {
        assertEquals(LoudnessScan.attenuationFactor(-17.0), LoudnessScan.attenuationFactorMb(-1700), 1e-4f)
        assertEquals(1f, LoudnessScan.attenuationFactorMb(300))
    }

    @Test
    fun `累计器收口与整批计算一致`() {
        val samples = sine(16384.0)
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        val mb = LoudnessScan.gainMbFromAccumulator(sum, samples.size)
        val direct = (LoudnessScan.gainDb(LoudnessScan.rmsDbfs(samples)) * 100).toInt()
        assertTrue("累计器路径与整批路径偏差过大：$mb vs $direct", mb != null && Math.abs(mb - direct) <= 1)
    }

    @Test
    fun `零样本与全静音的累计器给出 null 或上限`() {
        assertNull(LoudnessScan.gainMbFromAccumulator(0.0, 0))
        // 全 0：静音 → 增益顶到上限 +12 dB → +1200 mB（衰减制下最终因子仍是 1）
        assertEquals(1200, LoudnessScan.gainMbFromAccumulator(0.0, 1000))
    }
}
