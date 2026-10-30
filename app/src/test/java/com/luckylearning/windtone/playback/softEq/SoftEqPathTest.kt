package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.AudioFx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E1 facade + E2 gate wiring through SoftEqPath. */
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
}
