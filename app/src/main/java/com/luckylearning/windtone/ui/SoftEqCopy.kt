package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.softEq.SoftEqGate

/**
 * Soft-EQ path user-visible copy (E4).
 *
 * Maps [SoftEqGate.Phase] to string resources shown in [EqSheet]. Hard EQ stays
 * preferred: never claim the system equalizer still works when it was rejected;
 * soft enable needs explicit confirm (Prefs.softEqUserConfirm / SoftEqGate).
 *
 * Docs: docs/soft-eq feature doc (功能-软EQ.md).
 */
internal object SoftEqCopy {

    /** Status paragraph under the soft-EQ section. */
    fun statusRes(phase: SoftEqGate.Phase): Int =
        when (phase) {
            SoftEqGate.Phase.CANDIDATE -> R.string.soft_eq_status_candidate
            SoftEqGate.Phase.SOFT_ACTIVE -> R.string.soft_eq_status_active
            SoftEqGate.Phase.HARD_ACTIVE -> R.string.soft_eq_status_hard
            SoftEqGate.Phase.IDLE -> R.string.soft_eq_status_idle
        }

    /**
     * Show the soft-EQ block when hard EQ was rejected (candidate / soft-active),
     * so the user can confirm. Hard-available / idle keep the block hidden -
     * hard path is the normal UI.
     */
    fun shouldShowConfirmEntry(phase: SoftEqGate.Phase): Boolean =
        phase == SoftEqGate.Phase.CANDIDATE || phase == SoftEqGate.Phase.SOFT_ACTIVE
}