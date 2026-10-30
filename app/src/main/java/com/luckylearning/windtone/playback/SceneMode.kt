package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 场景化模式编排：套用 / 还原 / 手改离开。
 *
 * 音效走 [AudioFx]；变速变调与睡眠通过 [Hooks] 接到 [PlaybackEngine]，
 * 避免本对象直接依赖引擎（也方便以后换实现）。
 *
 * 进入第一档前拍快照；档与档之间切换不重拍；手改 EQ 清空场景并丢弃快照
 * （当前值成为新常态，与离开音效方案同一口径）。
 */
object SceneMode {

    data class Hooks(
        val setSpeed: (Float) -> Unit,
        val setPitchSemitones: (Int) -> Unit,
        val startSleepMs: (Long) -> Unit,
        val cancelSleep: () -> Unit,
        val sleepRemainingMs: () -> Long,
    )

    private var prefs: Prefs? = null
    private var hooks: Hooks? = null

    /** 正在由场景套用音效，避免 [onManualEqEdit] 误清 */
    @Volatile
    private var applying = false

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    fun init(prefs: Prefs) {
        this.prefs = prefs
        _activeId.value = prefs.sceneId
    }

    fun setHooks(hooks: Hooks) {
        this.hooks = hooks
    }

    fun activeIdValue(): String = _activeId.value

    /**
     * 点选场景芯片。[id] 空串 = 关闭并还原。
     * 点当前已激活档 = 关闭。
     */
    fun select(id: String) {
        val next = SceneProfiles.nextSelection(_activeId.value, id)
        if (next.isEmpty()) {
            exit()
        } else {
            apply(next)
        }
    }

    fun apply(id: String) {
        val profile = SceneProfiles.byId(id) ?: return
        val p = prefs ?: return
        val h = hooks
        if (p.sceneStash.isEmpty()) {
            p.sceneStash = SceneStash.encode(capture(p, h))
        }
        applying = true
        try {
            applyEq(profile)
            val speed = profile.speed
            if (speed != null) {
                val v = speed.coerceIn(
                    SettingsDefaults.PLAYBACK_SPEED_RANGE.start,
                    SettingsDefaults.PLAYBACK_SPEED_RANGE.endInclusive,
                )
                h?.setSpeed?.let { fn -> fn(v) }
            }
            val pitch = profile.pitchSemitones
            if (pitch != null) {
                h?.setPitchSemitones?.let { fn -> fn(pitch) }
            }
            when (val m = profile.sleepMinutes) {
                null -> Unit
                0 -> h?.cancelSleep?.let { it() }
                else -> h?.startSleepMs?.let { it(m.coerceAtLeast(1) * 60_000L) }
            }
        } finally {
            applying = false
        }
        p.sceneId = profile.id
        _activeId.value = profile.id
    }

    fun exit() {
        val p = prefs ?: return
        val raw = p.sceneStash
        p.sceneStash = SettingsDefaults.SCENE_STASH
        p.sceneId = SettingsDefaults.SCENE_ID
        _activeId.value = ""
        if (raw.isEmpty()) return
        val snap = SceneStash.decode(raw) ?: return
        restoringSound(snap)
        val h = hooks
        h?.setSpeed?.let { fn ->
            fn(
                snap.speed.coerceIn(
                    SettingsDefaults.PLAYBACK_SPEED_RANGE.start,
                    SettingsDefaults.PLAYBACK_SPEED_RANGE.endInclusive,
                ),
            )
        }
        h?.setPitchSemitones?.let { fn ->
            fn(snap.pitchSemitones.coerceIn(Pitch.MIN_SEMITONES, Pitch.MAX_SEMITONES))
        }
        if (snap.sleepRemainingMs > 0L) {
            h?.startSleepMs?.let { it(snap.sleepRemainingMs) }
        } else {
            h?.cancelSleep?.let { it() }
        }
    }

    /** AudioFx 手改曲线 / 增强 / 另选方案时调用：离开场景，快照作废 */
    fun onManualEqEdit() {
        if (applying) return
        val p = prefs ?: return
        if (p.sceneId.isEmpty() && _activeId.value.isEmpty()) return
        p.sceneId = SettingsDefaults.SCENE_ID
        p.sceneStash = SettingsDefaults.SCENE_STASH
        _activeId.value = SceneProfiles.activeIdAfterManualEdit(_activeId.value)
    }

    private fun capture(p: Prefs, h: Hooks?): SceneStash.Snapshot {
        val schemeId = if (p.carMode) p.carSchemeId else p.soundSchemeId
        return SceneStash.Snapshot(
            enabled = p.eqEnabled,
            presetId = p.eqPresetId,
            bandsCsv = p.eqBands,
            bass = p.eqBassBoost,
            virtual = p.eqVirtualizer,
            loudness = p.eqLoudness,
            reverbId = p.eqReverbId,
            schemeId = schemeId,
            speed = p.playbackSpeed,
            pitchSemitones = p.playbackPitchSemitones,
            sleepRemainingMs = h?.sleepRemainingMs?.invoke() ?: 0L,
        )
    }

    private fun applyEq(profile: SceneProfiles.Profile) {
        when (profile.eqSource) {
            SceneProfiles.EqSource.SCHEME -> AudioFx.setScheme(profile.eqRefId)
            SceneProfiles.EqSource.USER_PRESET -> AudioFx.applyUserPreset(profile.eqRefId)
            SceneProfiles.EqSource.BUILTIN_PRESET -> {
                AudioFx.setEnabled(true)
                AudioFx.setPreset(profile.eqRefId)
            }
        }
    }

    private fun restoringSound(snap: SceneStash.Snapshot) {
        applying = true
        try {
            AudioFx.restoreSoundValues(
                enabled = snap.enabled,
                presetId = snap.presetId,
                bandsCsv = snap.bandsCsv,
                bass = snap.bass,
                virtual = snap.virtual,
                loudness = snap.loudness,
                reverbId = snap.reverbId,
                schemeId = snap.schemeId,
            )
        } finally {
            applying = false
        }
    }
}
