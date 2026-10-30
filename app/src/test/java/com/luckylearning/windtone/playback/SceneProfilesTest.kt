package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
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
}
