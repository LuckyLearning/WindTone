package com.luckylearning.windtone.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B3：居中时处理器须 inactive，避免恒等混音占链。 */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class StereoBalanceAudioProcessorTest {

    @Test
    fun `居中时 isActive 为 false`() {
        val p = StereoBalanceAudioProcessor()
        p.setBalance(0)
        configureStereo(p)
        assertTrue(p.isCentered)
        assertFalse(p.isActive)
    }

    @Test
    fun `偏右时 isActive 为 true`() {
        val p = StereoBalanceAudioProcessor()
        p.setBalance(40)
        configureStereo(p)
        assertFalse(p.isCentered)
        assertTrue(p.isActive)
    }

    @Test
    fun `回到居中重新 inactive`() {
        val p = StereoBalanceAudioProcessor()
        p.setBalance(10)
        configureStereo(p)
        assertTrue(p.isActive)
        p.setBalance(0)
        assertFalse(p.isActive)
    }

    private fun configureStereo(p: StereoBalanceAudioProcessor) {
        p.configure(
            AudioProcessor.AudioFormat(
                /* sampleRate= */ 48_000,
                /* channelCount= */ 2,
                /* encoding= */ C.ENCODING_PCM_16BIT,
            ),
        )
    }
}
