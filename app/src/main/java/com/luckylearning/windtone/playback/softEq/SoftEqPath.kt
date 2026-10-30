package com.luckylearning.windtone.playback.softEq

import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.AudioFx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Soft EQ main-path facade (E1 skeleton + E2 DEVICE_REJECTED gate + E3 curve bridge).
 *
 * - Default **off** ([enabled]=false). Processor stays in the Media3 chain;
 *   when inactive it is PCM passthrough.
 * - [onHardEqUnavailable] records the hard-EQ reason and runs [SoftEqGate]:
 *   only [AudioFx.Unavailable.DEVICE_REJECTED] becomes a soft **candidate**;
 *   soft enables when the user confirm switch is on (Prefs skeleton).
 * - [onHardEqAvailable] forces soft off so hard + soft never dual-open.
 * - [SoftEqSpike.ENABLED] remains a force-auto override (still false); normal
 *   product path is gate + [setUserConfirmSoft] / Prefs.
 * - **E3 curve bridge**: [syncCurve] / [applyBuiltinPreset] / [applyResolved]
 *   push the same hard-EQ preset or custom gains into [processor] via
 *   [SoftEqCurve]. Soft filters that curve when [SOFT_ACTIVE].
 * - Settings copy / confirm entry = E4 (EqSheet SoftEqCopy); diagnostics SoftEqProbe = E5.
 *
 * Wiring: PlaybackService -> DefaultRenderersFactory.buildAudioSink ->
 * DefaultAudioSink.setAudioProcessors (soft EQ before stereo balance).
 *
 * Docs: docs/功能-软EQ.md; spike: docs/spike-软EQ.md.
 */
@UnstableApi
object SoftEqPath {

    data class State(
        /** Runtime master switch applied to [processor]. */
        val enabled: Boolean = false,
        /** Last hard-EQ unavailable reason (diagnostics SoftEqProbe / pack). */
        val lastHardUnavailable: AudioFx.Unavailable = AudioFx.Unavailable.NONE,
        /** Gate phase from [SoftEqGate]. */
        val phase: SoftEqGate.Phase = SoftEqGate.Phase.IDLE,
        /** True when hard rejected — UI may offer soft EQ (E4). */
        val candidate: Boolean = false,
        /** User confirm switch (Prefs [Prefs.softEqUserConfirm]). */
        val userConfirmSoft: Boolean = false,
        /** Last applied curve source (E3). */
        val curveSource: SoftEqCurve.Source = SoftEqCurve.Source.FLAT,
        /** Builtin / user preset id for the applied curve; empty = custom/flat. */
        val curvePresetId: String = "",
        /** Soft-center gains (mB) currently on the processor. */
        val curveGainsMb: List<Int> = SoftEqEngine.DEFAULT_CENTERS_HZ.map { 0 },
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: Prefs? = null

    /**
     * Last hard-EQ band centers (Hz) seen in a successful bind.
     * Used to remap custom `eqBands` CSV onto soft centers after DEVICE_REJECTED.
     */
    @Volatile
    private var lastHardCentersHz: IntArray? = null

    /** ExoPlayer audio-chain singleton; UI / AudioFx only flip switch + curve. */
    val processor: SoftEqAudioProcessor = SoftEqAudioProcessor()

    val enabled: Boolean get() = _state.value.enabled

    val candidate: Boolean get() = _state.value.candidate

    /**
     * Bind Prefs so the user-confirm skeleton survives process death.
     * Also syncs the soft curve from Prefs (E3); does not enable soft until
     * hard EQ is DEVICE_REJECTED (+ confirm).
     */
    fun bindPrefs(p: Prefs) {
        prefs = p
        SoftEqGate.setUserConfirmSoft(p.softEqUserConfirm)
        applySnapshot(SoftEqGate.snapshot(), forceSoft = false)
        syncCurveFromPrefs()
    }

    /**
     * Remember hard-EQ band layout from a successful [AudioFx] bind (E3).
     * Does not change enable state.
     */
    fun onHardBandLayout(centersHz: List<Int>) {
        if (centersHz.isEmpty()) return
        lastHardCentersHz = centersHz.toIntArray()
    }

    /**
     * Direct runtime switch (dev / tests). Prefer [setUserConfirmSoft] for the
     * product gate path so Prefs + phase stay consistent.
     */
    fun setEnabled(on: Boolean) {
        processor.setEnabled(on)
        _state.value = _state.value.copy(enabled = on)
    }

    /**
     * Explicit user confirm switch skeleton (E2). Persists to Prefs when bound.
     * While candidate: toggles soft on/off. While hard available: remembers only.
     */
    fun setUserConfirmSoft(on: Boolean) {
        prefs?.softEqUserConfirm = on
        applySnapshot(SoftEqGate.setUserConfirmSoft(on), forceSoft = false)
    }

    /**
     * Hard EQ unavailable hook (E1 + E2 gate + E3 curve sync).
     *
     * Records [reason]. [DEVICE_REJECTED] -> candidate; soft on only if user
     * confirmed (or [SoftEqSpike.ENABLED] force-auto, still false).
     * Always refreshes the soft curve from Prefs so SOFT_ACTIVE filters the
     * same shape the user last chose.
     */
    fun onHardEqUnavailable(reason: AudioFx.Unavailable) {
        syncCurveFromPrefs()
        val confirm = prefs?.softEqUserConfirm ?: SoftEqGate.snapshot().userConfirmSoft
        val snap = SoftEqGate.onHardUnavailable(reason, confirm)
        val force =
            reason == AudioFx.Unavailable.DEVICE_REJECTED && SoftEqSpike.ENABLED
        applySnapshot(snap, forceSoft = force)
    }

    /**
     * Hard EQ bound successfully — leave soft path (no dual-open).
     */
    fun onHardEqAvailable() {
        val confirm = prefs?.softEqUserConfirm ?: SoftEqGate.snapshot().userConfirmSoft
        applySnapshot(SoftEqGate.onHardAvailable(confirm), forceSoft = false)
    }

    /**
     * Push hard-EQ preset id + bands CSV onto the soft processor (E3).
     * Safe to call while soft is off — curve is ready for the next SOFT_ACTIVE.
     */
    fun syncCurve(
        presetId: String,
        bandsCsv: String,
        hardCentersHz: IntArray? = lastHardCentersHz,
    ): SoftEqCurve.Resolved {
        if (hardCentersHz != null && hardCentersHz.isNotEmpty()) {
            lastHardCentersHz = hardCentersHz.copyOf()
        }
        return applyResolved(
            SoftEqCurve.resolve(
                presetId = presetId,
                bandsCsv = bandsCsv,
                hardCentersHz = lastHardCentersHz,
            ),
        )
    }

    /** Resolve + apply from bound Prefs (no-op if Prefs unbound). */
    fun syncCurveFromPrefs(): SoftEqCurve.Resolved? {
        val p = prefs ?: return null
        return syncCurve(p.eqPresetId, p.eqBands, lastHardCentersHz)
    }

    /** Apply a builtin [EqPresets] curve onto soft centers. */
    fun applyBuiltinPreset(presetId: String): SoftEqCurve.Resolved? {
        val resolved = SoftEqCurve.fromBuiltinId(presetId) ?: return null
        return applyResolved(resolved)
    }

    /** Apply an already-resolved curve to the processor + state. */
    fun applyResolved(resolved: SoftEqCurve.Resolved): SoftEqCurve.Resolved {
        processor.setCurve(resolved.centersHz, resolved.gainsMb)
        _state.value = _state.value.copy(
            curveSource = resolved.source,
            curvePresetId = resolved.presetId,
            curveGainsMb = resolved.gainsMb.toList(),
        )
        return resolved
    }

    /** Apply explicit mB gains on soft default centers. */
    fun applyGainsMb(gainsMb: IntArray, presetId: String = ""): SoftEqCurve.Resolved =
        applyResolved(SoftEqCurve.fromGainsMb(gainsMb, SoftEqCurve.CENTERS_HZ, presetId))

    fun currentGainsMb(): IntArray = processor.currentGainsMb()

    fun currentCentersHz(): IntArray = processor.currentCentersHz()

    private fun applySnapshot(snap: SoftEqGate.Snapshot, forceSoft: Boolean) {
        val softOn = snap.softShouldEnable || forceSoft
        processor.setEnabled(softOn)
        _state.value = _state.value.copy(
            enabled = softOn,
            lastHardUnavailable = snap.lastHard,
            phase = snap.phase,
            candidate = snap.candidate || forceSoft,
            userConfirmSoft = snap.userConfirmSoft,
        )
    }

    /** Test / cold-start reset: soft off, clear gate + curve + hook record. */
    fun resetForTest() {
        prefs = null
        lastHardCentersHz = null
        SoftEqGate.reset()
        processor.setEnabled(false)
        processor.setCurve(
            SoftEqEngine.DEFAULT_CENTERS_HZ,
            IntArray(SoftEqEngine.DEFAULT_CENTERS_HZ.size),
        )
        _state.value = State()
    }
}
