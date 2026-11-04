package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「假无损」识别（竞品借鉴：Salt Player）。
 *
 * 判据是「有损编码砍高频」，所以测试用合成信号直接验证边界：
 * - 白噪声 → 截止接近奈奎斯特，判「真无损」；
 * - 10 kHz 正弦（模拟 128k MP3 砍掉的素材）→ 截止 10 kHz，判「疑似有损来源」；
 * - 有损容器 / 低采样率 / 无数据 → 不误报。
 */
class FakeLosslessTest {

    private val rate = 44100

    @Test
    fun whiteNoiseLooksLossless() {
        val cutoff = Spectrum.cutoffHz(Spectrum.noise(16384), rate)
        assertTrue("截止应高于阈值，实测 $cutoff", cutoff != null && cutoff >= FakeLossless.LOSSY_CUTOFF_HZ)
    }

    @Test
    fun pureLowToneLooksLikeLossySource() {
        // 10 kHz 正弦：有损编码砍到 10k 的文件就是这个频谱形状
        val cutoff = Spectrum.cutoffHz(Spectrum.sine(10_000, rate, 16384), rate)
        assertTrue("截止应贴近 10 kHz，实测 $cutoff", cutoff != null && cutoff in 9_000..11_500)
    }

    @Test
    fun nearThresholdToneIsNotLossless() {
        val cutoff = Spectrum.cutoffHz(Spectrum.sine(16_000, rate, 16384), rate)
        assertTrue("16 kHz 截止应判为可疑，实测 $cutoff", cutoff != null && cutoff < FakeLossless.LOSSY_CUTOFF_HZ)
    }

    @Test
    fun silenceReturnsNull() {
        assertEquals(null, Spectrum.cutoffHz(FloatArray(8192), rate))
    }

    @Test
    fun tooFewSamplesReturnsNull() {
        assertEquals(null, Spectrum.cutoffHz(FloatArray(100), rate))
    }

    @Test
    fun judgeLosslessContainerWithHighCutoff() {
        val r = FakeLossless.judge("flac", "audio/flac", 20_000, rate)
        assertEquals(FakeLossless.Verdict.LIKELY_LOSSLESS, r.verdict)
    }

    @Test
    fun judgeLosslessContainerWithLowCutoffIsSuspicious() {
        val r = FakeLossless.judge("flac", "audio/flac", 15_800, rate)
        assertEquals(FakeLossless.Verdict.SUSPECTED_LOSSY_SOURCE, r.verdict)
    }

    @Test
    fun judgeLossyContainerIsNotApplicable() {
        assertEquals(FakeLossless.Verdict.NOT_APPLICABLE, FakeLossless.judge("mp3", "audio/mpeg", 16_000, rate).verdict)
        assertEquals(FakeLossless.Verdict.NOT_APPLICABLE, FakeLossless.judge("m4a", "audio/mp4a-latm", 19_000, rate).verdict)
    }

    @Test
    fun judgeLowSampleRateAvoidsFalsePositive() {
        // 22.05 kHz 素材的上限就是 11 kHz，拿 16.5k 判会全线误报 → 返回 UNKNOWN
        assertEquals(FakeLossless.Verdict.UNKNOWN, FakeLossless.judge("flac", "audio/flac", 10_500, 22_050).verdict)
    }

    @Test
    fun judgeMissingCutoffIsUnknown() {
        assertEquals(FakeLossless.Verdict.UNKNOWN, FakeLossless.judge("wav", "audio/raw", null, rate).verdict)
    }
}
