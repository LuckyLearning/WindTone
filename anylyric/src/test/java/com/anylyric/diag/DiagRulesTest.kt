package com.anylyric.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val diagnosis = DiagRules.evaluate(
            ok().copy(listenerGranted = false, listenerConnected = false)
        )
        assertEquals(DiagCode.PERMISSION_LISTENER, diagnosis.code)
    }

    /**
     * 勾着但服务没连上，是「空窗期」而不是「没授权」。
     *
     * 真机反馈：应用关闭后重新打开，设置页显示未授予，进去看却是勾选状态。
     * 判因若也混为一谈，就会把用户赶去系统设置里做无意义的关开动作。
     */
    @Test
    fun grantedButUnboundIsItsOwnDiagnosis() {
        val diagnosis = DiagRules.evaluate(
            ok().copy(listenerGranted = true, listenerConnected = false, hitSource = null)
        )
        assertEquals(DiagCode.LISTENER_UNBOUND, diagnosis.code)
    }

    /**
     * 服务没绑上，但歌词照样取到了 —— 这是降级可用，不是故障。
     *
     * `getActiveSessions` 的权限边界是授权列表，不要求服务实例已连接，因此 L0/L1
     * 在未绑定状态下仍能工作；此时报 LISTENER_UNBOUND 会把正常状态说成坏的。
     */
    @Test
    fun unboundButLyricsArrivedIsDegradedOk() {
        val diagnosis = DiagRules.evaluate(
            ok().copy(listenerGranted = true, listenerConnected = false)
        )
        assertEquals(DiagCode.OK, diagnosis.code)
        // 证据里要留下 degraded，否则报告看起来与「一切正常」无异，掩盖服务未绑定
        assertTrue(diagnosis.evidence.contains("degraded"))
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
        val diagnosis = DiagRules.evaluate(
            ok().copy(positionJumpCount = 5, timedLyrics = true)
        )
        assertEquals(DiagCode.CLOCK_UNSTABLE, diagnosis.code)
    }

    /**
     * 逐行推送的歌词按最后一行定位，与 progress 无关。
     * 此时报 CLOCK_UNSTABLE 只会误导用户去怀疑播放器。
     */
    @Test
    fun untimedLyricsIgnoresPositionJumps() {
        val diagnosis = DiagRules.evaluate(
            ok().copy(positionJumpCount = 9, timedLyrics = false)
        )
        assertEquals(DiagCode.OK, diagnosis.code)
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
