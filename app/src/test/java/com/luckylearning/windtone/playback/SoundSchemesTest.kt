package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SoundSchemes] 的窄契约：id 稳定唯一、引用的曲线 / 混响 id 真实存在、
 * 强度都在合法区间内、「原声」与出厂值一致。
 *
 * 方案 id 一旦发布就进了用户 Prefs（勿改），这里钉住的不只是现在的正确，
 * 还有「将来有人加方案时不会顺手把契约改坏」。
 */
class SoundSchemesTest {

    @Test
    fun `id 全部非空且唯一`() {
        val ids = SoundSchemes.ALL.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals("方案 id 重复：${ids.groupBy { it }.filterValues { it.size > 1 }.keys}",
            ids.size, ids.toSet().size)
    }

    @Test
    fun `引用的均衡器曲线与混响档位都真实存在`() {
        SoundSchemes.ALL.forEach { s ->
            assertNotNull("方案 ${s.id} 的曲线 ${s.eqPresetId} 不存在", EqPresets.byId(s.eqPresetId))
            assertNotNull("方案 ${s.id} 的混响 ${s.reverbId} 不存在", ReverbPresets.byId(s.reverbId))
        }
    }

    @Test
    fun `强度都在各自合法区间内`() {
        SoundSchemes.ALL.forEach { s ->
            assertTrue("方案 ${s.id} 低音越界：${s.bass}", s.bass in 0..1000)
            assertTrue("方案 ${s.id} 环绕越界：${s.virtual}", s.virtual in 0..1000)
            assertTrue("方案 ${s.id} 响度越界：${s.loudness}", s.loudness in 0..1500)
        }
    }

    @Test
    fun `原声方案与出厂值完全一致`() {
        val off = SoundSchemes.OFF
        assertEquals("flat", off.eqPresetId)
        assertEquals(0, off.bass)
        assertEquals(0, off.virtual)
        assertEquals(0, off.loudness)
        assertEquals("off", off.reverbId)
        assertFalse(off.forCar)
    }

    @Test
    fun `只有车载方案带车载标`() {
        val cars = SoundSchemes.ALL.filter { it.forCar }
        assertEquals("车载方案应只有一条（多了会让界面标失去信息量）", 1, cars.size)
        assertEquals("car_drive", cars.single().id)
    }

    @Test
    fun `byId 对未知与空串返回 null`() {
        assertEquals(null, SoundSchemes.byId("nope"))
        assertEquals(null, SoundSchemes.byId(""))
        assertNotNull(SoundSchemes.byId("bass"))
    }

    @Test
    fun `方案数量足够铺开一个选择面`() {
        assertTrue("方案太少，一键包就失去意义了", SoundSchemes.ALL.size >= 8)
    }
}
