package com.luckylearning.windtone.playback.softEq

/**
 * Soft-EQ force-auto override. Docs: `docs/功能-软EQ.md` / `docs/spike-软EQ.md`.
 *
 * E1 hung SoftEqPath.processor into PlaybackService; runtime switch is
 * SoftEqPath.setEnabled (default off).
 *
 * E2 product gate is SoftEqGate + SoftEqPath.setUserConfirmSoft / Prefs.
 * E3 bridges hard-EQ curves via SoftEqCurve / SoftEqPath.syncCurve.
 * E4 is EqSheet SoftEqCopy + confirm switch (Prefs).
 * E5 is SoftEqProbe diagnostics + Prefs key docs.
 * ENABLED is only a force-auto override: when true, DEVICE_REJECTED enables
 * soft without user confirm. Keep false for normal builds.
 */
object SoftEqSpike {
    const val ENABLED: Boolean = false
}
