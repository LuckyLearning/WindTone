package com.luckylearning.windtone.playback

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.ui.AppStrings
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放质量报告口径（A3；i18n 第 25 批后正文走 `pq_*`）：空态诚实，字段齐全。
 * 不启动 ExoPlayer——只钉报告文案，避免听感路径进单测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class PlaybackQualityProbeTest {

    @Before
    fun setUp() {
        AppStrings.init(ApplicationProvider.getApplicationContext())
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
