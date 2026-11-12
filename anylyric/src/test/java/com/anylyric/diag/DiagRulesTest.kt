package com.anylyric.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagRulesTest {

    private fun ok() = ProbeSnapshot(
        listenerConnected = true,
        overlayGranted = true,
        sessionCount = 1,
        playingCount = 1,
        hitSource = "L1",
        viewAttached = true,
        viewMeasured = true
    )

    @Test
    fun missingNotificationAccessComesFirst() {
        val diagnosis = DiagRules.evaluate(ok().copy(listenerConnected = false))
        assertEquals(DiagCode.PERMISSION_LISTENER, diagnosis.code)
    }

    @Test
    fun missingOverlayPermissionIsDetected() {
        val diagnosis = DiagRules.evaluate(ok().copy(overlayGranted = false))
        assertEquals(DiagCode.PERMISSION_OVERLAY, diagnosis.code)
    }

    @Test
    fun zeroSessionsMeansPlayerSideProblem() {
        val diagnosis = DiagRules.evaluate(ok().copy(sessionCount = 0))
        assertEquals(DiagCode.NO_SESSION, diagnosis.code)
    }

    @Test
    fun sessionsWithoutPlaybackAreInactive() {
        val diagnosis = DiagRules.evaluate(ok().copy(playingCount = 0))
        assertEquals(DiagCode.SESSION_INACTIVE, diagnosis.code)
    }

    @Test
    fun avrcpGateIsRecognised() {
        val diagnosis = DiagRules.evaluate(
            ok().copy(pseudoTitle = true, hitSource = "L1", l1OnlyOnPause = true)
        )
        assertEquals(DiagCode.AVRCP_GATE, diagnosis.code)
    }

    @Test
    fun noChannelIsReportedAsPlayerSideFact() {
        val diagnosis = DiagRules.evaluate(ok().copy(hitSource = null))
        assertEquals(DiagCode.NO_CHANNEL, diagnosis.code)
    }

    @Test
    fun unstablePositionIsDetected() {
        val diagnosis = DiagRules.evaluate(ok().copy(positionJumpCount = 5))
        assertEquals(DiagCode.CLOCK_UNSTABLE, diagnosis.code)
    }

    @Test
    fun missingOverlayViewIsDetected() {
        val diagnosis = DiagRules.evaluate(ok().copy(viewAttached = false))
        assertEquals(DiagCode.OVERLAY_NOT_ATTACHED, diagnosis.code)
    }

    @Test
    fun unmeasuredOverlayViewIsDetected() {
        val diagnosis = DiagRules.evaluate(ok().copy(viewMeasured = false))
        assertEquals(DiagCode.OVERLAY_NOT_MEASURED, diagnosis.code)
    }

    @Test
    fun hiddenByRuleIsNotAFailure() {
        val diagnosis = DiagRules.evaluate(ok().copy(hiddenByRule = true))
        assertEquals(DiagCode.HIDDEN_BY_RULE, diagnosis.code)
    }

    @Test
    fun healthyPathIsOk() {
        val diagnosis = DiagRules.evaluate(ok())
        assertEquals(DiagCode.OK, diagnosis.code)
    }
}
