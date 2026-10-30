package com.luckylearning.windtone.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.softEq.SoftEqGate
import com.luckylearning.windtone.playback.softEq.SoftEqSpike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E4: EqSheet soft-path copy - candidate / active status, confirm entry,
 * never claim hard EQ still works after DEVICE_REJECTED.
 */
@RunWith(RobolectricTestRunner::class)
class SoftEqCopyTest {

    @Test
    fun phaseMapsToExpectedRes() {
        assertEquals(R.string.soft_eq_status_candidate, SoftEqCopy.statusRes(SoftEqGate.Phase.CANDIDATE))
        assertEquals(R.string.soft_eq_status_active, SoftEqCopy.statusRes(SoftEqGate.Phase.SOFT_ACTIVE))
        assertEquals(R.string.soft_eq_status_hard, SoftEqCopy.statusRes(SoftEqGate.Phase.HARD_ACTIVE))
        assertEquals(R.string.soft_eq_status_idle, SoftEqCopy.statusRes(SoftEqGate.Phase.IDLE))
    }

    @Test
    fun confirmEntryOnlyWhenCandidateOrSoftActive() {
        assertTrue(SoftEqCopy.shouldShowConfirmEntry(SoftEqGate.Phase.CANDIDATE))
        assertTrue(SoftEqCopy.shouldShowConfirmEntry(SoftEqGate.Phase.SOFT_ACTIVE))
        assertFalse(SoftEqCopy.shouldShowConfirmEntry(SoftEqGate.Phase.HARD_ACTIVE))
        assertFalse(SoftEqCopy.shouldShowConfirmEntry(SoftEqGate.Phase.IDLE))
    }

    @Test
    fun spikeStaysDisabled() {
        assertFalse(SoftEqSpike.ENABLED)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun zhCandidateCopyOffersSoftRequiresConfirmDoesNotClaimHardWorks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = context.getString(R.string.soft_eq_status_candidate)
        assertTrue(copy, copy.contains("系统硬均衡器不可用"))
        assertTrue(copy, copy.contains("软件均衡"))
        assertTrue(copy, copy.contains("确认"))
        assertFalse("must not claim hard EQ still available: $copy", copy.contains("硬均衡器仍然可用"))
        assertFalse(copy, copy.contains("硬均衡器可用，"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun zhActiveCopySaysSoftOnAndNotDual() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = context.getString(R.string.soft_eq_status_active)
        assertTrue(copy, copy.contains("已启用"))
        assertTrue(copy, copy.contains("不会同时开启"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun zhConfirmHintMentionsHardRecoverTurnsSoftOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = context.getString(R.string.soft_eq_confirm_title)
        val hint = context.getString(R.string.soft_eq_confirm_hint)
        assertTrue(title, title.contains("软件均衡"))
        assertTrue(hint, hint.contains("硬均衡器"))
        assertTrue(hint, hint.contains("自动关掉软路径"))
    }

    @Test
    fun englishCandidateCopyRequiresConfirmDoesNotClaimHardWorks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = context.getString(R.string.soft_eq_status_candidate)
        assertTrue(copy, copy.lowercase().contains("unavailable"))
        assertTrue(copy, copy.contains("confirm"))
        assertFalse(copy, copy.contains("hard equalizer is still available"))
        assertFalse(copy, copy.contains("hard equalizer still works"))
    }

    @Test
    fun englishActiveCopySaysSoftOnWithoutDualPath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = context.getString(R.string.soft_eq_status_active)
        assertTrue(copy, copy.contains("Software EQ"))
        assertTrue(copy, copy.contains("never runs together"))
    }
}
