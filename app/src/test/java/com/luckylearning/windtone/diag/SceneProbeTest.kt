package com.luckylearning.windtone.diag

import com.luckylearning.windtone.playback.SceneProfiles
import com.luckylearning.windtone.playback.SceneStash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[场景]` 段纯函数（不启 Robolectric）：标签、冷启动文案、stash 摘要、日志过滤。
 */
class SceneProbeTest {

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
            "10:00:00.000 [FG] x",
            "10:00:01.000 [SCENE] 套用 drive",
            "10:00:02.000 [OVERLAY] y",
            "10:00:03.000 [SCENE] 退出并还原",
        )
        val recent = SceneProbe.recentSceneLogs(lines)
        assertEquals(2, recent.size)
        assertTrue(recent[0].contains("套用 drive"))
        assertTrue(recent[1].contains("退出并还原"))
    }
}
