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

    /**
     * 两组各 8 个、合计 16 个，且 id / 名字都不重复。
     *
     * 数字写死是有意的：预设表是**用户能直接感知**的功能面，
     * 少一个多一个都该在评审时被看见，而不是被 forEach 悄悄放过。
     */
    @Test
    fun `两组各八个预设且 id 与名字唯一`() {
        assertEquals(8, EqPresets.group(EqPresets.Group.FEEL).size)
        assertEquals(8, EqPresets.group(EqPresets.Group.GENRE).size)
        assertEquals(16, EqPresets.ALL.size)
        assertEquals(EqPresets.ALL.size, EqPresets.ALL.map { it.id }.distinct().size)
        assertEquals(EqPresets.ALL.size, EqPresets.ALL.map { it.name }.distinct().size)
    }

    /**
     * 「温暖 / 明亮 / 通透 / 电子」这四条 id 必须**一直在**。
     *
     * 它们最早出现在 v1.1.5 的发版公告里，但当时只写了文案没写代码（公告里没有的预设
     * 用户找不到）。2026-10-17 补齐后钉一条测试，防止哪天被当成"没用的预设"删掉又退回那个坑。
     */
    @Test
    fun `v1_1_5公告里承诺过的四条预设已补齐`() {
        listOf("warm", "bright", "transparent", "electronic").forEach {
            assertTrue("预设 $it 缺失", EqPresets.byId(it) != null)
        }
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

    // ================================================================ 曲线预览归一化

    @Test
    fun `预览高度以设备量程归一化且全平曲线落在中线`() {
        // 量程 ±1500、频段全 0 → 五个点全在 0.5（中线）
        val flat = EqPresets.previewHeights(IntArray(5), -1500, 1500)
        assertEquals(5, flat.size)
        flat.forEach { assertEquals(0.5f, it, 1e-6f) }
    }

    @Test
    fun `预览高度把量程端点映射到 0 与 1`() {
        assertEquals(listOf(0f), EqPresets.previewHeights(intArrayOf(-1500), -1500, 1500))
        assertEquals(listOf(1f), EqPresets.previewHeights(intArrayOf(1500), -1500, 1500))
    }

    @Test
    fun `预览高度对超量程的值夹取而不是溢出`() {
        // 设备量程比 ±1500 窄时（少数机器是 ±1200），越界的增益必须夹住，
        // 否则折线会画到画布外面去
        val heights = EqPresets.previewHeights(intArrayOf(-2000, 0, 2000), -1200, 1200)
        assertEquals(listOf(0f, 0.5f, 1f), heights)
    }

    @Test
    fun `空频段返回空预览而不是崩`() {
        assertEquals(emptyList<Float>(), EqPresets.previewHeights(IntArray(0)))
    }
}
