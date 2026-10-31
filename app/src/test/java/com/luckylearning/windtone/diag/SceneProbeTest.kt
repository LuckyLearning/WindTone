package com.luckylearning.windtone.diag

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.playback.SceneProfiles
import com.luckylearning.windtone.playback.SceneStash
import com.luckylearning.windtone.ui.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `[场景]` 段（i18n 第 25 批后正文走 `scn_*`）。
 * Robolectric zh-rCN 对齐中文断言；不测 SceneMode 门控。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class SceneProbeTest {

    @Before
    fun setUp() {
        AppStrings.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `labelOf 已知档附中文`() {
        assertEquals("drive（驾车）", SceneProbe.labelOf("drive"))
        assertEquals("commute（通勤）", SceneProbe.labelOf("commute"))
        assertEquals("sleep（助眠）", SceneProbe.labelOf("sleep"))
        assertEquals("关闭", SceneProbe.labelOf(""))
        assertEquals("foo（未知档）", SceneProbe.labelOf("foo"))
    }

    @Test
    fun `describeCurrent 一致与不一致`() {
        assertEquals("关闭", SceneProbe.describeCurrent("", ""))
        assertEquals("drive（驾车）", SceneProbe.describeCurrent("drive", "drive"))
        assertTrue(SceneProbe.describeCurrent("drive", "sleep").contains("不一致"))
    }

    @Test
    fun `describeColdStart 三态`() {
        assertTrue(SceneProbe.describeColdStart(SceneProfiles.ColdStart.None).contains("无场景"))
        assertTrue(
            SceneProbe.describeColdStart(SceneProfiles.ColdStart.Reapply("drive"))
                .contains("重套"),
        )
        assertTrue(
            SceneProbe.describeColdStart(SceneProfiles.ColdStart.ClearInvalid)
                .contains("清无效"),
        )
    }

    @Test
    fun `stashPresence 与 summarizeStash`() {
        assertEquals("无", SceneProbe.stashPresence(""))
        assertEquals("有（格式损坏，按无快照处理）", SceneProbe.stashPresence("not-a-stash"))
        assertEquals(null, SceneProbe.summarizeStash(""))

        val raw = SceneStash.encode(
            SceneStash.Snapshot(
                enabled = true,
                presetId = "flat",
                bandsCsv = "0,0,0",
                bass = 1,
                virtual = 2,
                loudness = 3,
                reverbId = "",
                schemeId = "car_drive",
                speed = 1.0f,
                pitchSemitones = 0,
                sleepRemainingMs = 0L,
            ),
        )
        assertEquals("有（可解码）", SceneProbe.stashPresence(raw))
        val summary = SceneProbe.summarizeStash(raw)!!
        assertTrue(summary.contains("EQ开"))
        assertTrue(summary.contains("bands长度=5"))
        assertTrue(summary.contains("scheme=car_drive"))
        assertFalse("不应把完整 URL/token 关键字带出", summary.contains("token", ignoreCase = true))
    }

    @Test
    fun `recentSceneLogs 只留 SCENE`() {
        val lines = listOf(
            "[FG] x",
            "12:00 [SCENE] enter drive",
            "[OVERLAY] y",
            "12:01 [SCENE] exit",
        )
        val out = SceneProbe.recentSceneLogs(lines)
        assertEquals(2, out.size)
        assertTrue(out[0].contains("[SCENE]"))
        assertTrue(out[1].contains("[SCENE]"))
    }
}
