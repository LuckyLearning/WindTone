package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.AudioFx

/**
 * Soft EQ gate state machine (E2).
 *
 * Policy (hard EQ preferred; never dual-open with soft):
 * 1. [AudioFx.Unavailable.DEVICE_REJECTED] -> auto **candidate**; soft PCM path
 *    enables only when [userConfirmSoft] is true (Prefs / explicit switch skeleton).
 * 2. Hard EQ binds successfully again -> [Phase.HARD_ACTIVE]; force soft off.
 *    [userConfirmSoft] is kept so the next rejection can re-enable soft without
 *    asking again.
 * 3. [SESSION_PENDING] / [NONE] -> not a candidate; soft stays off.
 *
 * Pure decisions only — [SoftEqPath] applies [Snapshot.softShouldEnable] to the
 * Media3 processor. Docs: docs/功能-软EQ.md.
 */
object SoftEqGate {

    enum class Phase {
        /** No DEVICE_REJECTED yet (or reset). Soft off. */
        IDLE,
        /** Hard rejected; soft offered but not running (user has not confirmed). */
        CANDIDATE,
        /** Soft running while hard rejected + user confirmed. */
        SOFT_ACTIVE,
        /** Hard EQ bound successfully; soft must stay off. */
        HARD_ACTIVE,
    }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val lastHard: AudioFx.Unavailable = AudioFx.Unavailable.NONE,
        val userConfirmSoft: Boolean = false,
        /** UI may show try-soft-EQ when true. */
        val candidate: Boolean = false,
        /** Apply to SoftEqPath processor / enabled. */
        val softShouldEnable: Boolean = false,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot()

    fun snapshot(): Snapshot = snapshot

    fun reset() {
        snapshot = Snapshot()
    }

    /**
     * Hard EQ unavailable hook. Only [DEVICE_REJECTED] becomes a soft candidate.
     */
    fun onHardUnavailable(
        reason: AudioFx.Unavailable,
        userConfirmSoft: Boolean = snapshot.userConfirmSoft,
    ): Snapshot {
        val next = when (reason) {
            AudioFx.Unavailable.DEVICE_REJECTED -> {
                val softOn = userConfirmSoft
                Snapshot(
                    phase = if (softOn) Phase.SOFT_ACTIVE else Phase.CANDIDATE,
                    lastHard = reason,
                    userConfirmSoft = userConfirmSoft,
                    candidate = true,
                    softShouldEnable = softOn,
                )
            }
            AudioFx.Unavailable.SESSION_PENDING,
            AudioFx.Unavailable.NONE,
            -> Snapshot(
                phase = Phase.IDLE,
                lastHard = reason,
                userConfirmSoft = userConfirmSoft,
                candidate = false,
                softShouldEnable = false,
            )
        }
        snapshot = next
        return next
    }

    /**
     * Hard EQ bound successfully — soft must turn off (no dual path).
     */
    fun onHardAvailable(
        userConfirmSoft: Boolean = snapshot.userConfirmSoft,
    ): Snapshot {
        val next = Snapshot(
            phase = Phase.HARD_ACTIVE,
            lastHard = AudioFx.Unavailable.NONE,
            userConfirmSoft = userConfirmSoft,
            candidate = false,
            softShouldEnable = false,
        )
        snapshot = next
        return next
    }

    /**
     * User confirm switch skeleton. While candidate / soft-active, toggles soft
     * enable. While hard-active / idle, only remembers the preference.
     */
    fun setUserConfirmSoft(allow: Boolean): Snapshot {
        val cur = snapshot
        val next = when (cur.phase) {
            Phase.CANDIDATE, Phase.SOFT_ACTIVE -> {
                if (allow) {
                    cur.copy(
                        phase = Phase.SOFT_ACTIVE,
                        userConfirmSoft = true,
                        candidate = true,
                        softShouldEnable = true,
                    )
                } else {
                    cur.copy(
                        phase = Phase.CANDIDATE,
                        userConfirmSoft = false,
                        candidate = true,
                        softShouldEnable = false,
                    )
                }
            }
            Phase.HARD_ACTIVE, Phase.IDLE -> cur.copy(
                userConfirmSoft = allow,
                softShouldEnable = false,
                candidate = false,
            )
        }
        snapshot = next
        return next
    }
}
