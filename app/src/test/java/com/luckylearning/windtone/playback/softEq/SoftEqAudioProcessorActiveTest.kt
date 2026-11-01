package com.luckylearning.windtone.playback.softEq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B2：SoftEq 关闭时 isActive=false，Media3 跳过（不改滤波核）。 */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class SoftEqAudioProcessorActiveTest {

    @Test
    fun `关闭时即使有增益也 inactive`() {
        val p = SoftEqAudioProcessor()
        p.setCurve(SoftEqEngine.DEFAULT_CENTERS_HZ, intArrayOf(300, 0, 0, 0, 0))
        p.setEnabled(false)
        configure(p)
        assertFalse(p.isActive)
    }

    @Test
    fun `开启且有增益时 active`() {
        val p = SoftEqAudioProcessor()
        p.setEnabled(true)
        p.setCurve(SoftEqEngine.DEFAULT_CENTERS_HZ, intArrayOf(300, 0, 0, 0, 0))
        configure(p)
        assertTrue(p.isActive)
    }

    @Test
    fun `开启但平直曲线 inactive`() {
        val p = SoftEqAudioProcessor()
        p.setEnabled(true)
        p.setCurve(SoftEqEngine.DEFAULT_CENTERS_HZ, IntArray(5))
        configure(p)
        assertFalse(p.isActive)
    }

    private fun configure(p: SoftEqAudioProcessor) {
        p.configure(
            AudioProcessor.AudioFormat(
                /* sampleRate= */ 48_000,
                /* channelCount= */ 2,
                /* encoding= */ C.ENCODING_PCM_16BIT,
            ),
        )
    }
}
