package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.AudioFx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E2: SoftEqGate state machine — candidate + user confirm; hard recover forces soft off. */
class SoftEqGateTest {

    @Before
    fun setUp() {
        SoftEqGate.reset()
    }

    @After
    fun tearDown() {
        SoftEqGate.reset()
    }

    @Test
    fun defaultIdleSoftOff() {
        val s = SoftEqGate.snapshot()
        assertEquals(SoftEqGate.Phase.IDLE, s.phase)
        assertFalse(s.candidate)
        assertFalse(s.softShouldEnable)
        assertFalse(s.userConfirmSoft)
    }

    @Test
    fun deviceRejectedBecomesCandidateWithoutAutoEnable() {
        val s = SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = false)
        assertEquals(SoftEqGate.Phase.CANDIDATE, s.phase)
        assertTrue(s.candidate)
        assertFalse(s.softShouldEnable)
        assertEquals(AudioFx.Unavailable.DEVICE_REJECTED, s.lastHard)
    }

    @Test
    fun deviceRejectedWithUserConfirmEnablesSoft() {
        val s = SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = true)
        assertEquals(SoftEqGate.Phase.SOFT_ACTIVE, s.phase)
        assertTrue(s.candidate)
        assertTrue(s.softShouldEnable)
    }

    @Test
    fun sessionPendingNotCandidate() {
        val s = SoftEqGate.onHardUnavailable(AudioFx.Unavailable.SESSION_PENDING, userConfirmSoft = true)
        assertEquals(SoftEqGate.Phase.IDLE, s.phase)
        assertFalse(s.candidate)
        assertFalse(s.softShouldEnable)
    }

    @Test
    fun userConfirmWhileCandidateTurnsSoftOn() {
        SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = false)
        val s = SoftEqGate.setUserConfirmSoft(true)
        assertEquals(SoftEqGate.Phase.SOFT_ACTIVE, s.phase)
        assertTrue(s.softShouldEnable)
        assertTrue(s.userConfirmSoft)
    }

    @Test
    fun userRevokeWhileSoftActiveReturnsToCandidate() {
        SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = true)
        val s = SoftEqGate.setUserConfirmSoft(false)
        assertEquals(SoftEqGate.Phase.CANDIDATE, s.phase)
        assertTrue(s.candidate)
        assertFalse(s.softShouldEnable)
    }

    @Test
    fun hardAvailableForcesSoftOffAndClearsCandidate() {
        SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = true)
        assertTrue(SoftEqGate.snapshot().softShouldEnable)
        val s = SoftEqGate.onHardAvailable(userConfirmSoft = true)
        assertEquals(SoftEqGate.Phase.HARD_ACTIVE, s.phase)
        assertFalse(s.candidate)
        assertFalse(s.softShouldEnable)
        assertTrue(s.userConfirmSoft)
    }

    @Test
    fun afterHardRecoverNextRejectReEnablesIfConfirmKept() {
        SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = true)
        SoftEqGate.onHardAvailable(userConfirmSoft = true)
        val s = SoftEqGate.onHardUnavailable(AudioFx.Unavailable.DEVICE_REJECTED, userConfirmSoft = true)
        assertEquals(SoftEqGate.Phase.SOFT_ACTIVE, s.phase)
        assertTrue(s.softShouldEnable)
    }

    @Test
    fun setUserConfirmWhileHardActiveOnlyRemembers() {
        SoftEqGate.onHardAvailable(userConfirmSoft = false)
        val s = SoftEqGate.setUserConfirmSoft(true)
        assertEquals(SoftEqGate.Phase.HARD_ACTIVE, s.phase)
        assertTrue(s.userConfirmSoft)
        assertFalse(s.softShouldEnable)
        assertFalse(s.candidate)
    }
}
