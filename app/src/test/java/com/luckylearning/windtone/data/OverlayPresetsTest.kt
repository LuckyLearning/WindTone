package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮歌词「样式预设」的数据层测试（v1.1.78）。
 *
 * 覆盖的是方案 §八的参数分层模型（R1~R5）在数据侧的关键不变量：
 *  - 七套预设快照完整、id 唯一、手机 4 + 车机 3（G3 口径）；
 *  - 「套用 → 反推」闭环：任意预设转成参数快照后必须精确命中自身
 *    （套用即写盘 R1 与反推匹配 R5 共用 toParams，两边走样 = 功能坏了）；
 *  - 展示名三分：命中预设 / 出厂默认「默认预设」/ 其余「自定义」（FR-01 / RK-4）；
 *  - 车机预设带 carOnly 标记与 🚗 展示名（D3 / RK-6）。
 */
class OverlayPresetsTest {

    // ---------------------------------------------------------- 池完整性

    @Test
    fun `presets pool is phone four plus car three with unique ids`() {
        assertEquals(7, OverlayPreset.ALL.size)
        assertEquals(4, OverlayPreset.PHONE.size)
        assertEquals(3, OverlayPreset.CAR.size)
        assertEquals(OverlayPreset.ALL.size, OverlayPreset.ALL.map { it.id }.toSet().size)
        assertTrue(OverlayPreset.PHONE.none { it.carOnly })
        assertTrue(OverlayPreset.CAR.all { it.carOnly })
    }

    @Test
    fun `default preset is P2 and exists in pool`() {
        val p2 = OverlayPreset.byId(OverlayPreset.DEFAULT_ID)
        assertEquals("P2", p2?.id)
        assertEquals("毛玻璃双行", p2?.name)
        assertNotNull(OverlayPreset.byId("P1"))
        assertNotNull(OverlayPreset.byId("P4"))
        assertNotNull(OverlayPreset.byId("C1"))
        assertNull(OverlayPreset.byId("X9"))
    }

    // ---------------------------------------------------------- 套用 ↔ 反推闭环（R1 / R5）

    @Test
    fun `every preset round-trips through params and matches itself`() {
        OverlayPreset.ALL.forEach { preset ->
            assertEquals(
                "预设 ${preset.id} 的快照应能精确反推回自身",
                preset.id,
                currentOverlayPreset(preset.toParams())?.id,
            )
        }
    }

    @Test
    fun `car presets are carOnly and display with car badge`() {
        OverlayPreset.CAR.forEach { preset ->
            assertTrue(preset.carOnly)
            assertTrue(preset.displayName.startsWith("🚗"))
        }
        OverlayPreset.PHONE.forEach { preset ->
            assertFalse(preset.carOnly)
            assertFalse(preset.displayName.startsWith("🚗"))
        }
    }

    // ---------------------------------------------------------- 展示名三分（FR-01 / RK-4）

    @Test
    fun `factory params are labeled as default preset not custom`() {
        assertEquals("默认预设", overlayPresetLabel(OVERLAY_FACTORY_PARAMS))
    }

    @Test
    fun `factory params do not accidentally match any preset`() {
        // 出厂默认若与某套快照重合，「默认预设」分支就永远走不到 —— 保持二者分离
        // 是刻意设计（老用户升级无感 + FR-01 的「默认预设」口径）。
        assertNull(currentOverlayPreset(OVERLAY_FACTORY_PARAMS))
    }

    @Test
    fun `modified params fall back to custom label`() {
        val tweaked = OVERLAY_FACTORY_PARAMS.copy(cornerRadiusDp = 20)
        assertEquals("自定义", overlayPresetLabel(tweaked))
        // 回到与某套预设完全一致 → 立即命中该预设
        assertEquals("P2", currentOverlayPreset(OverlayPreset.byId("P2")!!.toParams())?.id)
    }

    @Test
    fun `single field drift breaks matching`() {
        val p3 = OverlayPreset.byId("P3")!!
        val params = p3.toParams()
        assertEquals(p3.id, currentOverlayPreset(params)?.id)
        // 任意一项（这里用行距）偏离 1 个单位就不再命中 → 「自定义」+ 恢复原值闭环
        assertEquals(
            null,
            currentOverlayPreset(params.copy(lineSpacingDp = params.lineSpacingDp + 1))?.id,
        )
    }

    // ---------------------------------------------------------- 摘要与人话

    @Test
    fun `summary mentions lines font and backdrop`() {
        val s = OverlayPreset.byId("C1")!!.summary()
        assertTrue(s.contains("1 行"))
        assertTrue(s.contains("28sp"))
        assertTrue(s.contains("90%"))
        assertTrue(s.contains("车机"))
    }

    @Test
    fun `tracker starts empty and is settable`() {
        // OverlayPresetTracker 是进程级会话记忆（R5：不落盘）；测试只保证读写通路
        val before = OverlayPresetTracker.lastAppliedId
        try {
            OverlayPresetTracker.lastAppliedId = "P1"
            assertEquals("P1", OverlayPresetTracker.lastAppliedId)
        } finally {
            OverlayPresetTracker.lastAppliedId = before
        }
    }
}
