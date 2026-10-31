package com.luckylearning.windtone.diag

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.softEq.SoftEqCurve
import com.luckylearning.windtone.playback.softEq.SoftEqGate
import com.luckylearning.windtone.playback.softEq.SoftEqPath
import com.luckylearning.windtone.playback.softEq.SoftEqSpike
import com.luckylearning.windtone.ui.AppStrings

/**
 * Soft-EQ summary for the diagnostics pack (`[软EQ]` / `[Soft EQ]` section, E5).
 *
 * Car HU troubleshooting for "why is EQ flat / why soft path not on" needs
 * phase, user-confirm Prefs, and a short curve digest in one paste — no adb.
 * Read-only; no secrets. Curve gains are the five soft centers (mB), short enough
 * to print. Docs: docs/功能-软EQ.md.
 *
 * i18n 第 24 批：仅用户可见说明进 `sep_*`；Prefs 键名 / 枚举名 / SoftEqSpike 等机器可读字段保持原样。
 * **不改** SoftEq 门控与滤波行为。
 */
@UnstableApi
internal object SoftEqProbe {

    fun buildReport(context: Context): String {
        val prefs = Prefs(context)
        val state = SoftEqPath.state.value
        return buildFrom(
            phase = state.phase,
            prefsConfirm = prefs.softEqUserConfirm,
            memoryConfirm = state.userConfirmSoft,
            enabled = state.enabled,
            candidate = state.candidate,
            lastHard = state.lastHardUnavailable,
            spikeEnabled = SoftEqSpike.ENABLED,
            curveSource = state.curveSource,
            curvePresetId = state.curvePresetId,
            curveGainsMb = state.curveGainsMb,
            centersHz = SoftEqPath.currentCentersHz().toList(),
        )
    }

    /** Pure formatter for unit tests (no Android Prefs / SoftEqPath). */
    fun buildFrom(
        phase: SoftEqGate.Phase,
        prefsConfirm: Boolean,
        memoryConfirm: Boolean,
        enabled: Boolean,
        candidate: Boolean,
        lastHard: AudioFx.Unavailable,
        spikeEnabled: Boolean,
        curveSource: SoftEqCurve.Source,
        curvePresetId: String,
        curveGainsMb: List<Int>,
        centersHz: List<Int>,
    ): String = buildString {
        appendLine(AppStrings.s(R.string.sep_sec))
        appendLine(AppStrings.s(R.string.sep_phase, describePhase(phase)))
        appendLine(AppStrings.s(R.string.sep_prefs_confirm, yn(prefsConfirm)))
        appendLine(
            AppStrings.s(
                R.string.sep_mem_confirm,
                yn(memoryConfirm),
                if (prefsConfirm != memoryConfirm) AppStrings.s(R.string.sep_prefs_mismatch) else "",
            ),
        )
        appendLine(AppStrings.s(R.string.sep_processor, yn(enabled), yn(candidate)))
        appendLine(AppStrings.s(R.string.sep_spike, spikeEnabled.toString()))
        appendLine(AppStrings.s(R.string.sep_hard, describeHard(lastHard)))
        appendLine(AppStrings.s(R.string.sep_curve, describeCurve(curveSource, curvePresetId, curveGainsMb)))
        appendLine(AppStrings.s(R.string.sep_centers, centersHz.joinToString("/")))
        appendLine(AppStrings.s(R.string.sep_note))
    }.trimEnd()

    fun describePhase(phase: SoftEqGate.Phase): String {
        val gloss = when (phase) {
            SoftEqGate.Phase.IDLE -> AppStrings.s(R.string.sep_phase_idle)
            SoftEqGate.Phase.CANDIDATE -> AppStrings.s(R.string.sep_phase_candidate)
            SoftEqGate.Phase.SOFT_ACTIVE -> AppStrings.s(R.string.sep_phase_soft)
            SoftEqGate.Phase.HARD_ACTIVE -> AppStrings.s(R.string.sep_phase_hard)
        }.ifEmpty { AppStrings.s(R.string.sep_phase_unknown) }
        val joined = AppStrings.s(R.string.sep_phase_fmt, phase.name, gloss)
        return joined.ifEmpty { "${phase.name}（$gloss）" }
    }

    fun describeHard(reason: AudioFx.Unavailable): String = when (reason) {
        AudioFx.Unavailable.NONE -> AppStrings.s(R.string.sep_hard_none)
        AudioFx.Unavailable.SESSION_PENDING -> AppStrings.s(R.string.sep_hard_session)
        AudioFx.Unavailable.DEVICE_REJECTED -> AppStrings.s(R.string.sep_hard_device)
    }

    fun describeCurve(
        source: SoftEqCurve.Source,
        presetId: String,
        gainsMb: List<Int>,
    ): String {
        val preset = if (presetId.isEmpty()) {
            AppStrings.s(R.string.sep_no_preset)
        } else {
            AppStrings.s(R.string.sep_preset, presetId)
        }
        val gains = if (gainsMb.isEmpty()) AppStrings.s(R.string.sep_empty) else gainsMb.joinToString(",")
        val audible = SoftEqCurve.isAudible(gainsMb.toIntArray())
        return AppStrings.s(R.string.sep_curve_fmt, source.toString(), preset, gains, yn(audible))
    }

    private fun yn(v: Boolean) =
        AppStrings.s(if (v) R.string.sep_on else R.string.sep_off)
}
