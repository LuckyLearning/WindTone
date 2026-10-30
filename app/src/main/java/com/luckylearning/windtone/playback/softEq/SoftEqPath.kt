package com.luckylearning.windtone.playback.softEq

import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.AudioFx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Soft EQ main-path facade (E1 skeleton + E2 DEVICE_REJECTED gate).
 *
 * - Default **off** ([enabled]=false). Processor stays in the Media3 chain;
 *   when inactive it is PCM passthrough.
 * - [onHardEqUnavailable] records the hard-EQ reason and runs [SoftEqGate]:
 *   only [AudioFx.Unavailable.DEVICE_REJECTED] becomes a soft **candidate**;
 *   soft enables when the user confirm switch is on (Prefs skeleton).
 * - [onHardEqAvailable] forces soft off so hard + soft never dual-open.
 * - [SoftEqSpike.ENABLED] remains a force-auto override (still false); normal
 *   product path is gate + [setUserConfirmSoft] / Prefs.
 * - Curve bridge / settings copy / full diagnostics = E3-E5.
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
        /** Last hard-EQ unavailable reason (diagnostics; E5 pack later). */
        val lastHardUnavailable: AudioFx.Unavailable = AudioFx.Unavailable.NONE,
        /** Gate phase from [SoftEqGate]. */
        val phase: SoftEqGate.Phase = SoftEqGate.Phase.IDLE,
        /** True when hard rejected — UI may offer soft EQ (E4). */
        val candidate: Boolean = false,
        /** User confirm switch (Prefs [Prefs.softEqUserConfirm]). */
        val userConfirmSoft: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: Prefs? = null

    /** ExoPlayer audio-chain singleton; UI / AudioFx only flip switch + curve. */
    val processor: SoftEqAudioProcessor = SoftEqAudioProcessor()

    val enabled: Boolean get() = _state.value.enabled

    val candidate: Boolean get() = _state.value.candidate

    /**
     * Bind Prefs so the user-confirm skeleton survives process death.
     * Does not enable soft until hard EQ is DEVICE_REJECTED.
     */
    fun bindPrefs(p: Prefs) {
        prefs = p
        SoftEqGate.setUserConfirmSoft(p.softEqUserConfirm)
        applySnapshot(SoftEqGate.snapshot(), forceSoft = false)
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
     * Hard EQ unavailable hook (E1 + E2 gate).
     *
     * Records [reason]. [DEVICE_REJECTED] -> candidate; soft on only if user
     * confirmed (or [SoftEqSpike.ENABLED] force-auto, still false).
     */
    fun onHardEqUnavailable(reason: AudioFx.Unavailable) {
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

    private fun applySnapshot(snap: SoftEqGate.Snapshot, forceSoft: Boolean) {
        val softOn = snap.softShouldEnable || forceSoft
        processor.setEnabled(softOn)
        _state.value = State(
            enabled = softOn,
            lastHardUnavailable = snap.lastHard,
            phase = snap.phase,
            candidate = snap.candidate || forceSoft,
            userConfirmSoft = snap.userConfirmSoft,
        )
    }

    /** Test / cold-start reset: soft off, clear gate + hook record. */
    fun resetForTest() {
        prefs = null
        SoftEqGate.reset()
        processor.setEnabled(false)
        processor.setCurve(
            SoftEqEngine.DEFAULT_CENTERS_HZ,
            IntArray(SoftEqEngine.DEFAULT_CENTERS_HZ.size),
        )
        _state.value = State()
    }
}
