package com.luckylearning.windtone.playback.softEq

/**
 * Soft-EQ force-auto override. Docs: `docs/功能-软EQ.md` / `docs/spike-软EQ.md`.
 *
 * E1 hung SoftEqPath.processor into PlaybackService; runtime switch is
 * SoftEqPath.setEnabled (default off).
 *
 * E2 product gate is SoftEqGate + SoftEqPath.setUserConfirmSoft / Prefs.
 * ENABLED is only a force-auto override: when true, DEVICE_REJECTED enables
 * soft without user confirm. Keep false for normal builds.
 */
object SoftEqSpike {
    const val ENABLED: Boolean = false
}
