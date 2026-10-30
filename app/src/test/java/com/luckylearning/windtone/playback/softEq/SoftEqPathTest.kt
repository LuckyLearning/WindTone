package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.EqPresets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E1 facade + E2 gate + E3 curve bridge through SoftEqPath. */
class SoftEqPathTest {

    @Before
    fun setUp() {
        SoftEqPath.resetForTest()
    }

    @After
    fun tearDown() {
        SoftEqPath.resetForTest()
    }

    @Test
    fun defaultOff() {
        assertFalse(SoftEqPath.enabled)
        assertEquals(AudioFx.Unavailable.NONE, SoftEqPath.state.value.lastHardUnavailable)
        assertFalse(SoftEqSpike.ENABLED)
        assertFalse(SoftEqPath.candidate)
        assertEquals(SoftEqGate.Phase.IDLE, SoftEqPath.state.value.phase)
        assertEquals(SoftEqCurve.Source.FLAT, SoftEqPath.state.value.curveSource)
    }

    @Test
    fun setEnabledToggle() {
        SoftEqPath.setEnabled(true)
        assertTrue(SoftEqPath.enabled)
        SoftEqPath.setEnabled(false)
        assertFalse(SoftEqPath.enabled)
    }

    @Test
    fun deviceRejectedBecomesCandidateWithoutAutoEnable() {
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        assertEquals(
            AudioFx.Unavailable.DEVICE_REJECTED,
            SoftEqPath.state.value.lastHardUnavailable,
        )
        assertTrue(SoftEqPath.candidate)
        assertEquals(SoftEqGate.Phase.CANDIDATE, SoftEqPath.state.value.phase)
        assertFalse(SoftEqPath.enabled)
    }

    @Test
    fun userConfirmThenRejectedEnablesSoft() {
        SoftEqPath.setUserConfirmSoft(true)
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        assertTrue(SoftEqPath.enabled)
        assertEquals(SoftEqGate.Phase.SOFT_ACTIVE, SoftEqPath.state.value.phase)
    }

    @Test
    fun confirmWhileCandidateEnables() {
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        assertFalse(SoftEqPath.enabled)
        SoftEqPath.setUserConfirmSoft(true)
        assertTrue(SoftEqPath.enabled)
        assertEquals(SoftEqGate.Phase.SOFT_ACTIVE, SoftEqPath.state.value.phase)
    }

    @Test
    fun hardRecoverForcesSoftOff() {
        SoftEqPath.setUserConfirmSoft(true)
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        assertTrue(SoftEqPath.enabled)
        SoftEqPath.onHardEqAvailable()
        assertFalse(SoftEqPath.enabled)
        assertFalse(SoftEqPath.candidate)
        assertEquals(SoftEqGate.Phase.HARD_ACTIVE, SoftEqPath.state.value.phase)
        assertTrue(SoftEqPath.state.value.userConfirmSoft)
    }

    @Test
    fun sessionPendingNotCandidate() {
        SoftEqPath.setUserConfirmSoft(true)
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.SESSION_PENDING)
        assertEquals(
            AudioFx.Unavailable.SESSION_PENDING,
            SoftEqPath.state.value.lastHardUnavailable,
        )
        assertFalse(SoftEqPath.enabled)
        assertFalse(SoftEqPath.candidate)
    }

    @Test
    fun processorSingleton() {
        val a = SoftEqPath.processor
        val b = SoftEqPath.processor
        assertTrue(a === b)
    }

    // ---- E3 curve bridge ----

    @Test
    fun applyBuiltinPresetPushesCurveToProcessor() {
        val r = SoftEqPath.applyBuiltinPreset(EqPresets.BASS_BOOST.id)
        assertTrue(r != null)
        assertEquals(SoftEqCurve.Source.BUILTIN, SoftEqPath.state.value.curveSource)
        assertEquals(EqPresets.BASS_BOOST.id, SoftEqPath.state.value.curvePresetId)
        val expected = EqPresets.curveFor(
            EqPresets.BASS_BOOST,
            SoftEqEngine.DEFAULT_CENTERS_HZ.toList(),
        )
        assertArrayEquals(expected, SoftEqPath.currentGainsMb())
        assertEquals(expected.toList(), SoftEqPath.state.value.curveGainsMb)
    }

    @Test
    fun syncCurveCustomCsvOntoSoftCenters() {
        val r = SoftEqPath.syncCurve(
            presetId = "",
            bandsCsv = "120,-40,0,80,-60",
        )
        assertEquals(SoftEqCurve.Source.BANDS_CSV, r.source)
        assertArrayEquals(intArrayOf(120, -40, 0, 80, -60), SoftEqPath.currentGainsMb())
    }

    @Test
    fun syncCurveRemembersHardLayoutForRemap() {
        val ten = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        SoftEqPath.onHardBandLayout(ten.toList())
        val r = SoftEqPath.syncCurve(
            presetId = "",
            bandsCsv = "600,500,300,100,0,-50,-100,-200,-300,-400",
        )
        assertEquals(SoftEqCurve.Source.REMAPPED, r.source)
        assertTrue(SoftEqCurve.isAudible(r.gainsMb))
        assertEquals(5, SoftEqPath.currentGainsMb().size)
    }

    @Test
    fun softActiveKeepsAppliedCurve() {
        SoftEqPath.applyBuiltinPreset(EqPresets.ROCK.id)
        SoftEqPath.setUserConfirmSoft(true)
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        assertTrue(SoftEqPath.enabled)
        assertEquals(EqPresets.ROCK.id, SoftEqPath.state.value.curvePresetId)
        assertArrayEquals(
            EqPresets.curveFor(EqPresets.ROCK, SoftEqEngine.DEFAULT_CENTERS_HZ.toList()),
            SoftEqPath.currentGainsMb(),
        )
    }

    @Test
    fun hardRecoverKeepsCurveReadyButSoftOff() {
        SoftEqPath.applyBuiltinPreset(EqPresets.WARM.id)
        SoftEqPath.setUserConfirmSoft(true)
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        SoftEqPath.onHardEqAvailable()
        assertFalse(SoftEqPath.enabled)
        assertEquals(EqPresets.WARM.id, SoftEqPath.state.value.curvePresetId)
        assertTrue(SoftEqCurve.isAudible(SoftEqPath.currentGainsMb()))
    }

    @Test
    fun unknownBuiltinIdReturnsNull() {
        assertTrue(SoftEqPath.applyBuiltinPreset("no_such_preset") == null)
    }
}
