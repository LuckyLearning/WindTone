package com.luckylearning.windtone.diag

import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.softEq.SoftEqCurve
import com.luckylearning.windtone.playback.softEq.SoftEqGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Soft-EQ diagnostics lines (E5): phase labels, curve digest, confirm flags.
 * Pure JVM — no Robolectric.
 */
class SoftEqProbeTest {

    @Test
    fun `describePhase 四态`() {
        assertEquals("IDLE（空闲）", SoftEqProbe.describePhase(SoftEqGate.Phase.IDLE))
        assertEquals(
            "CANDIDATE（候选未启用）",
            SoftEqProbe.describePhase(SoftEqGate.Phase.CANDIDATE),
        )
        assertEquals(
            "SOFT_ACTIVE（软路径启用中）",
            SoftEqProbe.describePhase(SoftEqGate.Phase.SOFT_ACTIVE),
        )
        assertEquals(
            "HARD_ACTIVE（硬 EQ 优先）",
            SoftEqProbe.describePhase(SoftEqGate.Phase.HARD_ACTIVE),
        )
    }

    @Test
    fun `describeHard 三态`() {
        assertTrue(SoftEqProbe.describeHard(AudioFx.Unavailable.NONE).contains("NONE"))
        assertTrue(
            SoftEqProbe.describeHard(AudioFx.Unavailable.SESSION_PENDING)
                .contains("SESSION_PENDING"),
        )
        assertTrue(
            SoftEqProbe.describeHard(AudioFx.Unavailable.DEVICE_REJECTED)
                .contains("DEVICE_REJECTED"),
        )
    }

    @Test
    fun `describeCurve 含源与可听`() {
        val flat = SoftEqProbe.describeCurve(SoftEqCurve.Source.FLAT, "", listOf(0, 0, 0, 0, 0))
        assertTrue(flat.contains("FLAT"))
        assertTrue(flat.contains("可听=关"))
        val rock = SoftEqProbe.describeCurve(
            SoftEqCurve.Source.BUILTIN,
            "rock",
            listOf(0, 300, -100, 200, 0),
        )
        assertTrue(rock.contains("BUILTIN"))
        assertTrue(rock.contains("preset=rock"))
        assertTrue(rock.contains("300"))
        assertTrue(rock.contains("可听=开"))
    }

    @Test
    fun `buildFrom 字段齐全且无 token`() {
        val text = SoftEqProbe.buildFrom(
            phase = SoftEqGate.Phase.CANDIDATE,
            prefsConfirm = false,
            memoryConfirm = false,
            enabled = false,
            candidate = true,
            lastHard = AudioFx.Unavailable.DEVICE_REJECTED,
            spikeEnabled = false,
            curveSource = SoftEqCurve.Source.BUILTIN,
            curvePresetId = "rock",
            curveGainsMb = listOf(0, 100, 0, 0, 0),
            centersHz = listOf(60, 230, 910, 3600, 14000),
        )
        assertTrue(text.startsWith("[软EQ]"))
        assertTrue(text.contains("相位：CANDIDATE"))
        assertTrue(text.contains("Prefs.softEqUserConfirm：关"))
        assertTrue(text.contains("内存确认：关"))
        assertTrue(text.contains("处理器启用：关"))
        assertTrue(text.contains("候选：开"))
        assertTrue(text.contains("SoftEqSpike.ENABLED：false"))
        assertTrue(text.contains("DEVICE_REJECTED"))
        assertTrue(text.contains("曲线：BUILTIN"))
        assertTrue(text.contains("中心Hz：60/230/910/3600/14000"))
        assertTrue(text.contains("soft_eq_user_confirm"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("http://"))
    }

    @Test
    fun `Prefs 与内存确认不一致时标明`() {
        val text = SoftEqProbe.buildFrom(
            phase = SoftEqGate.Phase.IDLE,
            prefsConfirm = true,
            memoryConfirm = false,
            enabled = false,
            candidate = false,
            lastHard = AudioFx.Unavailable.NONE,
            spikeEnabled = false,
            curveSource = SoftEqCurve.Source.FLAT,
            curvePresetId = "",
            curveGainsMb = listOf(0, 0, 0, 0, 0),
            centersHz = listOf(60, 230, 910, 3600, 14000),
        )
        assertTrue(text.contains("与 Prefs 不一致"))
    }
}