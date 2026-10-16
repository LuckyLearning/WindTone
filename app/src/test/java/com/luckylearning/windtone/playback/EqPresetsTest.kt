package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置预设曲线引擎测试：插值、夹取、分组与稳定性（id 不能变，变了用户的已选预设会丢）。
 */
class EqPresetsTest {

    /** 典型 5 段机的频段中心（与效果图一致） */
    private val fiveBands = listOf(60, 230, 910, 3600, 14000)

    @Test
    fun `原声预设对所有频段全平`() {
        val curve = EqPresets.curveFor(EqPresets.FLAT, fiveBands)
        assertEquals(listOf(0, 0, 0, 0, 0), curve.toList())
    }

    @Test
    fun `锚点频点上的增益等于锚点定义值`() {
        // 5 段机的中心频率就是锚点频率，增益应精确落在锚点上（无插值误差）
        EqPresets.ALL.forEach { preset ->
            val curve = EqPresets.curveFor(preset, fiveBands)
            assertEquals("预设 ${preset.id}", preset.anchors.map { it.second }, curve.toList())
        }
    }

    @Test
    fun `增益全部落在均衡器量程内`() {
        EqPresets.ALL.forEach { preset ->
            EqPresets.curveFor(preset, fiveBands).forEach { gain ->
                assertTrue("预设 ${preset.id} 增益 $gain 越界", gain in -1500..1500)
            }
        }
    }

    @Test
    fun `超出锚点范围的频率取端点不外推`() {
        // 10 段机常出现 31Hz（低于最低锚 60Hz）与 16kHz（高于最高锚 14kHz）
        assertEquals(EqPresets.BASS_BOOST.anchors.first().second, EqPresets.gainAt(EqPresets.BASS_BOOST, 31))
        assertEquals(EqPresets.BASS_BOOST.anchors.last().second, EqPresets.gainAt(EqPresets.BASS_BOOST, 16000))
    }

    @Test
    fun `插值落在两端增益之间且随频率单调`() {
        // 低音增强：60Hz=+550 → 230Hz=+350，中间频点应夹在两者之间
        val mid = EqPresets.gainAt(EqPresets.BASS_BOOST, 120)
        assertTrue("中点增益 $mid 应在 350..550 之间", mid in 350..550)
        assertTrue(EqPresets.gainAt(EqPresets.BASS_BOOST, 90) > EqPresets.gainAt(EqPresets.BASS_BOOST, 150))
    }

    @Test
    fun `任意分段数都能落出完整曲线`() {
        // 10 段机（频点仅示意）——只验证形状完整、不越界
        val tenBands = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        EqPresets.ALL.forEach { preset ->
            val curve = EqPresets.curveFor(preset, tenBands)
            assertEquals("预设 ${preset.id} 应输出 10 段", 10, curve.size)
        }
    }

    @Test
    fun `两组共九个预设且 id 唯一`() {
        assertEquals(5, EqPresets.group(EqPresets.Group.FEEL).size)
        assertEquals(4, EqPresets.group(EqPresets.Group.GENRE).size)
        assertEquals(EqPresets.ALL.size, EqPresets.ALL.map { it.id }.distinct().size)
        assertEquals(EqPresets.ALL.size, EqPresets.ALL.map { it.name }.distinct().size)
    }

    @Test
    fun `byId 能找回每个预设且未知 id 返回 null`() {
        EqPresets.ALL.forEach { assertEquals(it, EqPresets.byId(it.id)) }
        assertEquals(null, EqPresets.byId("no_such_preset"))
        assertEquals(null, EqPresets.byId("")) // 空串 = 自定义，不是预设
    }

    @Test
    fun `每个预设都有一句描述`() {
        EqPresets.ALL.forEach { preset ->
            assertTrue("预设 ${preset.id} 缺描述", preset.desc.isNotBlank())
        }
    }
}
