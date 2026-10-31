package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.diag.DiagLog
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 场景化模式编排：套用 / 还原 / 手改离开 / 冷启动重套。
 *
 * 音效走 [AudioFx]；变速变调与睡眠通过 [Hooks] 接到 [PlaybackEngine]，
 * 避免本对象直接依赖引擎（也方便以后换实现）。
 *
 * 进入第一档前拍快照；档与档之间切换不重拍；手改 EQ 清空场景并丢弃快照
 * （当前值成为新常态，与离开音效方案同一口径）。
 * 杀进程再开：若 [Prefs.sceneId] 仍有效，[restoreOnColdStart] 按档重套听感，
 * **不重拍** stash（沿用进档前快照，退出仍还原到进场景前）。
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

    /**
     * 冷启动恢复：杀进程后再开，按已落盘的 sceneId 重套听感。
     *
     * 必须在 [setHooks] 之后调用，否则变速/睡眠挂不上。
     * 重套时不捕获 stash（[apply] 的 captureBaseline=false），避免把「已是场景听感」
     * 误存成退出基线。
     */
    fun restoreOnColdStart() {
        val p = prefs ?: return
        when (val plan = SceneProfiles.planColdStart(p.sceneId)) {
            is SceneProfiles.ColdStart.None -> {
                _activeId.value = ""
                DiagLog.log("SCENE", "冷启动：无场景")
            }
            is SceneProfiles.ColdStart.ClearInvalid -> {
                DiagLog.log("SCENE", "冷启动：清无效 id=${p.sceneId}")
                if (p.sceneStash.isNotEmpty()) {
                    exit()
                } else {
                    p.sceneId = SettingsDefaults.SCENE_ID
                    _activeId.value = ""
                }
            }
            is SceneProfiles.ColdStart.Reapply -> {
                DiagLog.log("SCENE", "冷启动重套 ${plan.id}")
                // force：此时的 activeId 是从 Prefs 读回来的同一个 id，
                // 但音效链是进程重启后全新的，必须真的套一遍
                apply(plan.id, captureBaseline = false, force = true)
            }
        }
    }

    /**
     * @param captureBaseline 为 true 且尚无 stash 时拍进档前快照（用户点选进档）。
     *   冷启动重套传 false，沿用已落盘 stash。
     * @param force 跳过幂等判断。只有冷启动重套需要：那时 [activeId] 已经从 Prefs
     *   恢复成同一个 id，但音效链是进程重启后全新的，必须真的套一遍。
     */
    fun apply(id: String, captureBaseline: Boolean = true, force: Boolean = false) {
        val profile = SceneProfiles.byId(id) ?: return
        val p = prefs ?: return
        // 幂等（v1.2.40）：已经身处这一档就什么都不做。
        // 车机诊断包实测过 2.7 秒内「套用 commute → 套用 drive → 退出还原」三轮，
        // 每一轮都要重建音效链、写两处 Prefs、再推一次状态流。重复套同一档是最
        // 常见的那一类，拦掉它不改变任何可见结果 —— 人已经在档里了，再套一次
        // 得到的完全一样。不同档之间的切换（commute → drive）是真实意图，不拦。
        if (!force && _activeId.value == id && p.sceneId == id) return
        val h = hooks
        if (SceneProfiles.shouldCaptureBaseline(captureBaseline, p.sceneStash)) {
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
        val how = if (captureBaseline) "套用" else "重套"
        DiagLog.log("SCENE", "$how ${profile.id}")
    }

    fun exit() {
        val p = prefs ?: return
        // 本来就不在任何场景里（三者皆空）：不用再走一遍还原动作。
        // 任一非空都照常往下走 —— 那属于状态残留，正是要清掉的东西。
        if (_activeId.value.isEmpty() && p.sceneId.isEmpty() && p.sceneStash.isEmpty()) return
        val raw = p.sceneStash
        p.sceneStash = SettingsDefaults.SCENE_STASH
        p.sceneId = SettingsDefaults.SCENE_ID
        _activeId.value = ""
        DiagLog.log("SCENE", if (raw.isEmpty()) "退出（无快照）" else "退出并还原")
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
        DiagLog.log("SCENE", "手改EQ离开")
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
