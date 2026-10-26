package com.luckylearning.windtone.playback

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放质量报告口径（A3）：空态诚实，字段齐全。
 * 不启动 ExoPlayer——只钉报告文案，避免听感路径进单测。
 */
class PlaybackQualityProbeTest {

    @Before
    fun setUp() {
        PlaybackQualityProbe.resetForTest()
    }

    @Test
    fun `空态报告含关键字段且不装懂`() {
        val text = PlaybackQualityProbe.report()
        assertTrue(text.contains("[播放质量]"))
        assertTrue(text.contains("解码器"))
        assertTrue(text.contains("未知"))
        assertTrue(text.contains("缓冲欠载次数"))
        assertTrue(text.contains("直通"))
    }
}
