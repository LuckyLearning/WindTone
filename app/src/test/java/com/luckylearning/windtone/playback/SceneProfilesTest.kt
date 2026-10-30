package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SceneProfiles] 窄契约：三档默认、id 稳定、引用真实存在、选择语义。
 */
class SceneProfilesTest {

    @Test
    fun `三档 id 非空且唯一`() {
        val ids = SceneProfiles.ALL.map { it.id }
        assertEquals(3, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `默认 id 钉住发布契约`() {
        assertEquals("drive", SceneProfiles.DRIVE.id)
        assertEquals("commute", SceneProfiles.COMMUTE.id)
        assertEquals("sleep", SceneProfiles.SLEEP.id)
    }

    @Test
    fun `SCHEME 引用的音效方案真实存在`() {
        SceneProfiles.ALL.filter { it.eqSource == SceneProfiles.EqSource.SCHEME }.forEach { p ->
            assertNotNull("场景 ${p.id} 的方案 ${p.eqRefId} 不存在", SoundSchemes.byId(p.eqRefId))
        }
    }

    @Test
    fun `BUILTIN 引用的曲线真实存在`() {
        SceneProfiles.ALL.filter { it.eqSource == SceneProfiles.EqSource.BUILTIN_PRESET }.forEach { p ->
            assertNotNull("场景 ${p.id} 的曲线 ${p.eqRefId} 不存在", EqPresets.byId(p.eqRefId))
        }
    }

    @Test
    fun `驾车用车载驾驶方案且关掉睡眠`() {
        assertEquals(SoundSchemes.CAR_DRIVE.id, SceneProfiles.DRIVE.eqRefId)
        assertEquals(0, SceneProfiles.DRIVE.sleepMinutes)
        assertEquals(1.0f, SceneProfiles.DRIVE.speed)
    }

    @Test
    fun `助眠有睡眠分钟数`() {
        assertTrue((SceneProfiles.SLEEP.sleepMinutes ?: 0) > 0)
    }

    @Test
    fun `MVP 不做歌单绑定`() {
        assertTrue(SceneProfiles.ALL.all { it.playlistId == null })
    }

    @Test
    fun `byId 对未知与空串返回 null`() {
        assertNull(SceneProfiles.byId(""))
        assertNull(SceneProfiles.byId("nope"))
        assertNotNull(SceneProfiles.byId("drive"))
    }

    @Test
    fun `手改清空 active id`() {
        assertEquals("", SceneProfiles.activeIdAfterManualEdit("drive"))
        assertEquals("", SceneProfiles.activeIdAfterManualEdit(""))
    }

    @Test
    fun `选择语义 同档再点或关闭则退出`() {
        assertEquals("", SceneProfiles.nextSelection("drive", "drive"))
        assertEquals("", SceneProfiles.nextSelection("drive", ""))
        assertEquals("sleep", SceneProfiles.nextSelection("drive", "sleep"))
        assertEquals("commute", SceneProfiles.nextSelection("", "commute"))
    }

    @Test
    fun `chipIds 与 UI 芯片同序`() {
        assertEquals(listOf("drive", "commute", "sleep"), SceneProfiles.chipIds())
        assertEquals(SceneProfiles.ALL.map { it.id }, SceneProfiles.chipIds())
    }

    @Test
    fun `选择语义 空到空与跨档`() {
        assertEquals("", SceneProfiles.nextSelection("", ""))
        assertEquals("drive", SceneProfiles.nextSelection("", "drive"))
        assertEquals("drive", SceneProfiles.nextSelection("sleep", "drive"))
        assertEquals("", SceneProfiles.nextSelection("commute", "commute"))
    }

    @Test
    fun `冷启动 无 id 不动`() {
        assertEquals(SceneProfiles.ColdStart.None, SceneProfiles.planColdStart(""))
    }

    @Test
    fun `冷启动 有效 id 重套`() {
        val plan = SceneProfiles.planColdStart("sleep")
        assertTrue(plan is SceneProfiles.ColdStart.Reapply)
        assertEquals("sleep", (plan as SceneProfiles.ColdStart.Reapply).id)
        assertEquals("drive", (SceneProfiles.planColdStart("drive") as SceneProfiles.ColdStart.Reapply).id)
        assertEquals("commute", (SceneProfiles.planColdStart("commute") as SceneProfiles.ColdStart.Reapply).id)
    }

    @Test
    fun `冷启动 未知 id 清标记`() {
        assertEquals(SceneProfiles.ColdStart.ClearInvalid, SceneProfiles.planColdStart("nope"))
        assertEquals(SceneProfiles.ColdStart.ClearInvalid, SceneProfiles.planColdStart("DRIVE"))
    }

    @Test
    fun `冷启动重套不拍 stash 即使用户 stash 空`() {
        assertFalse(SceneProfiles.shouldCaptureBaseline(captureBaseline = false, existingStashRaw = ""))
        assertFalse(SceneProfiles.shouldCaptureBaseline(captureBaseline = false, existingStashRaw = "v1"))
        assertTrue(SceneProfiles.shouldCaptureBaseline(captureBaseline = true, existingStashRaw = ""))
        assertFalse(SceneProfiles.shouldCaptureBaseline(captureBaseline = true, existingStashRaw = "already"))
    }

    // ---- 边界：切档 stash / 未知档回落 / 冷启动重套幂等 ----

    @Test
    fun `切档沿用进场景前基线不重拍 stash`() {
        // 用户点选进第一档：需拍基线
        assertTrue(
            SceneProfiles.shouldCaptureBaseline(
                captureBaseline = true,
                existingStashRaw = "",
            ),
        )
        // 档与档之间切换：调用方仍传 captureBaseline=true，但已有 stash → 不重拍
        val baseline = "v1\u001fstashed"
        assertFalse(
            SceneProfiles.shouldCaptureBaseline(
                captureBaseline = true,
                existingStashRaw = baseline,
            ),
        )
        // 切档选择语义：drive → commute → sleep，始终返回目标 id（非退出）
        assertEquals("commute", SceneProfiles.nextSelection("drive", "commute"))
        assertEquals("sleep", SceneProfiles.nextSelection("commute", "sleep"))
        assertEquals("drive", SceneProfiles.nextSelection("sleep", "drive"))
        // 再点当前档才退出（还原那份进场景前基线）
        assertEquals("", SceneProfiles.nextSelection("sleep", "sleep"))
    }

    @Test
    fun `切档环路后退出语义仍是空串`() {
        var current = ""
        current = SceneProfiles.nextSelection(current, "drive")
        assertEquals("drive", current)
        current = SceneProfiles.nextSelection(current, "commute")
        assertEquals("commute", current)
        current = SceneProfiles.nextSelection(current, "sleep")
        assertEquals("sleep", current)
        // 关闭芯片 / 再点当前档 → exit
        assertEquals("", SceneProfiles.nextSelection(current, ""))
        assertEquals("", SceneProfiles.nextSelection(current, current))
    }

    @Test
    fun `未知档回落 byId 与冷启动一致`() {
        val unknowns = listOf(
            "nope",
            "DRIVE",
            "Drive",
            " drive",
            "drive ",
            "drive\n",
            "custom",
            "null",
            "0",
            "驾车",
        )
        for (id in unknowns) {
            assertNull("byId($id) 应未知", SceneProfiles.byId(id))
            assertEquals(
                "planColdStart($id) 应 ClearInvalid",
                SceneProfiles.ColdStart.ClearInvalid,
                SceneProfiles.planColdStart(id),
            )
        }
        // 已知档不回落
        for (id in SceneProfiles.chipIds()) {
            assertNotNull(SceneProfiles.byId(id))
            val plan = SceneProfiles.planColdStart(id)
            assertTrue(plan is SceneProfiles.ColdStart.Reapply)
            assertEquals(id, (plan as SceneProfiles.ColdStart.Reapply).id)
        }
    }

    @Test
    fun `冷启动重套幂等 多次 plan 同结果`() {
        repeat(3) {
            assertEquals(SceneProfiles.ColdStart.None, SceneProfiles.planColdStart(""))
            val re = SceneProfiles.planColdStart("drive")
            assertTrue(re is SceneProfiles.ColdStart.Reapply)
            assertEquals("drive", (re as SceneProfiles.ColdStart.Reapply).id)
            assertEquals(SceneProfiles.ColdStart.ClearInvalid, SceneProfiles.planColdStart("ghost"))
        }
    }

    @Test
    fun `冷启动重套幂等 反复 shouldCaptureBaseline false 永不拍`() {
        // 杀进程再开：captureBaseline=false；即便 stash 空也不拍，避免把场景听感写成退出基线
        repeat(5) {
            assertFalse(
                SceneProfiles.shouldCaptureBaseline(
                    captureBaseline = false,
                    existingStashRaw = "",
                ),
            )
            assertFalse(
                SceneProfiles.shouldCaptureBaseline(
                    captureBaseline = false,
                    existingStashRaw = "v1\u001fanything",
                ),
            )
        }
        // 与「用户点选进档」对照：仅第一次（无 stash）才拍
        assertTrue(SceneProfiles.shouldCaptureBaseline(true, ""))
        assertFalse(SceneProfiles.shouldCaptureBaseline(true, "kept-baseline"))
        assertFalse(SceneProfiles.shouldCaptureBaseline(true, "kept-baseline"))
    }

    @Test
    fun `三档听感与速度变调契约钉死`() {
        assertEquals(SoundSchemes.VOCAL.id, SceneProfiles.COMMUTE.eqRefId)
        assertEquals(0, SceneProfiles.COMMUTE.sleepMinutes)
        assertEquals(SoundSchemes.SPACE.id, SceneProfiles.SLEEP.eqRefId)
        assertEquals(45, SceneProfiles.SLEEP.sleepMinutes)
        for (p in SceneProfiles.ALL) {
            assertEquals(1.0f, p.speed)
            assertEquals(0, p.pitchSemitones)
            assertEquals(SceneProfiles.EqSource.SCHEME, p.eqSource)
            assertNotNull(p.sleepMinutes) // MVP 都明确写睡眠（0=取消 / >0=启动）
        }
    }
}
