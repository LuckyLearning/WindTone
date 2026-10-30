package com.luckylearning.windtone.playback.softEq

/**
 * Soft-EQ Spike gate. Docs: `docs/spike-软EQ.md`.
 * [ENABLED] defaults false and does **not** auto-wire PlaybackService.
 * Wire via DefaultRenderersFactory.buildAudioSink + DefaultAudioSink.setAudioProcessors.
 */
object SoftEqSpike {
    const val ENABLED: Boolean = false
}
