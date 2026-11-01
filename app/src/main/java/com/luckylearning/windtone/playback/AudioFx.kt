package com.luckylearning.windtone.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.luckylearning.windtone.playback.softEq.SoftEqPath

/**
 * 均衡器与音效控制。
 * UI 与播放服务在同一进程，直接通过此单例共享状态：
 *  - 服务端：[attach] 绑定 ExoPlayer 的 audioSessionId
 *  - UI 端：读写 [state]，变更即时生效并持久化到 Prefs
 */
object AudioFx {

    data class EqState(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val bandCount: Int = 0,
        val bandMinMb: Int = -1500,
        val bandMaxMb: Int = 1500,
        val bandLevels: IntArray = IntArray(0),
        val centerFreqs: List<String> = emptyList(),
        /** 各频段中心频率（Hz），供内置预设曲线插值用 */
        val bandCenterHz: List<Int> = emptyList(),
        /** 当前内置预设 id（[EqPresets.byId]）；空串 = 自定义 */
        val presetId: String = "",
        val bassStrength: Int = 0,
        val virtualStrength: Int = 0,
        val bassMax: Int = 1000,
        /** 响度增强目标增益（mB，0 表示关闭） */
        val loudnessGain: Int = 0,
        val loudnessMax: Int = 1500,
        /** 当前混响 id（[ReverbPresets.byId]）；空串 = 未选过（= 关闭） */
        val reverbId: String = "",
        /**
         * 本机是否有可用的混响效果器。
         *
         * 与 [available] **分开报**：均衡器可用但混响不可用的机器是存在的
         * （效果器是各自独立的 `AudioEffect`，AOSP 之外的 ROM 常裁剪混响），
         * 混成一个布尔会让整页在那种机器上消失。
         */
        val reverbAvailable: Boolean = false,
        /**
         * 不可用的**原因**（v1.1.22）。
         *
         * 原先只有一个 `available=false`，界面只能猜「大概是还没开始播放」，
         * 于是一辆正在放歌的车机上弹出了「播放器尚未启动」——一句话把用户引到
         * 完全错误的方向。现在把两种情况分开报：
         *  - [Unavailable.SESSION_PENDING]：还没拿到 audioSessionId（确实没开始播放）；
         *  - [Unavailable.DEVICE_REJECTED]：会话有了，但本机音频系统不给建效果器。
         */
        val unavailable: Unavailable = Unavailable.NONE,
        /** 构造失败时的原始异常文案，只用于诊断展示 */
        val unavailableDetail: String = "",
        /**
         * 是否绕道「系统输出」（audioSession 0）建的效果器（v1.1.23）。
         *
         * 不少车机的媒体会话根本没挂效果链（HAL 把音效交给整车 DSP），
         * 按会话建 `Equalizer` 必然抛异常；而挂到全局输出上通常还能用。
         * 代价是作用范围变成整个系统混音，界面上必须说清楚。
         */
        val globalEffects: Boolean = false,
        /** 当前音效方案（[SoundSchemes] 的 id）；空串 = 没选过方案 / 手动微调过（v1.1.52） */
        val schemeId: String = "",
        /** 用户命名预设（[UserEqPresets]）。和当前有没有选中它无关，列表本身要活过杀进程。 */
        val userPresets: List<UserEqPresets.Preset> = emptyList(),
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    enum class Unavailable { NONE, SESSION_PENDING, DEVICE_REJECTED }

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var prefs: Prefs? = null
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null
    private var sessionId: Int = 0

    /** A/B 试听（按住对比原声）：true 期间所有效果器临时旁路，状态与落盘值不动 */
    @Volatile private var bypassed = false

    fun init(context: android.content.Context) {
        if (prefs == null) {
            prefs = Prefs(context.applicationContext)
            // E2: soft-EQ user-confirm skeleton from Prefs
            SoftEqPath.bindPrefs(prefs!!)
        }
        _state.value = _state.value.copy(userPresets = loadUserPresets())
    }

    private fun loadUserPresets(): List<UserEqPresets.Preset> =
        UserEqPresets.decode(prefs?.eqUserPresets.orEmpty())

    private fun storeUserPresets(list: List<UserEqPresets.Preset>) {
        prefs?.eqUserPresets = UserEqPresets.encode(list)
        _state.value = _state.value.copy(userPresets = list)
    }

    /** 只动增强、曲线没变：用户预设离开，内置曲线预设留着。方案一律离开。 */
    private fun leaveEnhancementEdit() {
        clearActiveSchemeId()
        SceneMode.onManualEqEdit()
        val nextPreset = UserEqPresets.presetIdAfterManualEnhancement(_state.value.presetId)
        if (nextPreset != _state.value.presetId) prefs?.eqPresetId = nextPreset
        _state.value = _state.value.copy(presetId = nextPreset, schemeId = "")
    }

    // ------------------------------------------------------- 音效方案（v1.1.52）

    /** 当前生效的方案 id：车载 / 手机两个存储位按模式取用；空串 = 没选过方案 */
    private fun activeSchemeId(p: Prefs): String = if (p.carMode) p.carSchemeId else p.soundSchemeId

    /** 手动微调任一参数 = 离开当前方案（与「拖频段滑块即自定义」同一口径） */
    private fun clearActiveSchemeId() {
        val p = prefs ?: return
        if (p.carMode) p.carSchemeId = "" else p.soundSchemeId = ""
    }

    /** 手动改曲线 / 另选预设或方案时离开场景（[setScheme] 由场景自己套用时已设 applying 旗） */
    private fun leaveSceneIfManual() {
        SceneMode.onManualEqEdit()
    }

    /** 方案值 → Prefs（启用总开关；频段曲线由 [pushValuesToEffects] 按设备频段生成回写） */
    private fun writeSchemeValues(p: Prefs, scheme: SoundSchemes.Scheme) {
        p.eqEnabled = true
        p.eqPresetId = scheme.eqPresetId
        p.eqBassBoost = scheme.bass.coerceIn(0, 1000)
        p.eqVirtualizer = scheme.virtual.coerceIn(0, 1000)
        p.eqLoudness = scheme.loudness.coerceIn(0, 1500)
        p.eqReverbId = scheme.reverbId
    }

    /** 把一组音效值推到已绑定的效果器与状态上；效果器未绑定时只更新状态（值已在 Prefs，绑定时会重建） */
    private fun pushValuesToEffects(
        enabled: Boolean,
        presetId: String,
        bandsCsvOverride: String?,
        bassMb: Int,
        virtualMb: Int,
        loudnessMb: Int,
        reverbId: String,
        schemeId: String,
    ) {
        val p = prefs ?: return
        val s = _state.value
        if (!s.available || s.bandCount <= 0) {
            _state.value = s.copy(enabled = enabled, presetId = presetId, schemeId = schemeId)
            syncSoftEqCurve()
            return
        }
        val preset = EqPresets.byId(presetId)
        val bands = when {
            preset != null -> EqPresets.curveFor(preset, s.bandCenterHz)
            else -> decodeBands(bandsCsvOverride ?: p.eqBands, s.bandCount, s.bandMinMb, s.bandMaxMb)
        }
        equalizer?.let { applyBands(it, bands) }
        // 曲线生成后回写频段：bindEffects 与「恢复默认」都依赖 eqBands 是当前曲线的真实值
        if (preset != null) p.eqBands = encodeBands(bands)
        runCatching { bass?.setStrength(bassMb.coerceIn(0, 1000).toShort()) }
        runCatching { virtualizer?.setStrength(virtualMb.coerceIn(0, 1000).toShort()) }
        runCatching { loudness?.setTargetGain(loudnessMb.coerceIn(0, 1500)) }
        runCatching {
            reverb?.setPreset(ReverbPresets.byId(reverbId)?.platform ?: ReverbPresets.OFF.platform)
        }
        _state.value = s.copy(
            enabled = enabled,
            presetId = presetId,
            bandLevels = bands,
            bassStrength = bassMb.coerceIn(0, 1000),
            virtualStrength = virtualMb.coerceIn(0, 1000),
            loudnessGain = loudnessMb.coerceIn(0, 1500),
            reverbId = reverbId,
            schemeId = schemeId,
        )
        syncSoftEqCurve()
    }

    /**
     * 一键应用音效方案（[SoundSchemes.byId]）。
     *
     * 方案的语义强于单选预设：应用即**打开总开关**（用户选的是「我要这个声音」，
     * 替他把开关打开比「选了方案却没声音」的困惑小得多）。应用后每个参数
     * 仍可单独微调，微调即离开方案（id 清空，界面不再显示选中态）。
     */
    fun setScheme(id: String) {
        leaveSceneIfManual()
        val p = prefs ?: return
        val scheme = SoundSchemes.byId(id) ?: return
        writeSchemeValues(p, scheme)
        if (p.carMode) p.carSchemeId = scheme.id else p.soundSchemeId = scheme.id
        pushValuesToEffects(
            true, scheme.eqPresetId, null,
            scheme.bass, scheme.virtual, scheme.loudness, scheme.reverbId, scheme.id,
        )
        apply(true)
    }

    /**
     * 进入车载模式（[com.luckylearning.windtone.playback.PlaybackEngine.setCarMode] 调用）。
     *
     * 先把手机侧的音效值**整体快照**（[SoundStash]），再套用车载方案 ——
     * 快照让「车载模式里调的声音只在车载模式生效」，退出时原样还回来，
     * 手机听感不会被车载方案覆盖。没选过车载方案（`carSchemeId` 空）则完全不动。
     */
    fun enterCarProfile() {
        val p = prefs ?: return
        val scheme = SoundSchemes.byId(p.carSchemeId) ?: return
        if (p.soundStash.isEmpty()) {
            p.soundStash = SoundStash.encode(
                enabled = p.eqEnabled,
                presetId = p.eqPresetId,
                bandsCsv = p.eqBands,
                bass = p.eqBassBoost,
                virtual = p.eqVirtualizer,
                loudness = p.eqLoudness,
                reverbId = p.eqReverbId,
            )
        }
        writeSchemeValues(p, scheme)
        pushValuesToEffects(
            true, scheme.eqPresetId, null,
            scheme.bass, scheme.virtual, scheme.loudness, scheme.reverbId, scheme.id,
        )
        apply(true)
    }

    /** 退出车载模式：还原进车前的音效值快照；坏快照按「没快照」处理，保住现状不清值 */
    fun exitCarProfile() {
        val p = prefs ?: return
        if (p.soundStash.isEmpty()) return
        val snap = SoundStash.decode(p.soundStash)
        p.soundStash = SettingsDefaults.SOUND_STASH
        if (snap == null) return
        p.eqEnabled = snap.enabled
        p.eqPresetId = snap.presetId
        p.eqBands = snap.bandsCsv
        p.eqBassBoost = snap.bass.coerceIn(0, 1000)
        p.eqVirtualizer = snap.virtual.coerceIn(0, 1000)
        p.eqLoudness = snap.loudness.coerceIn(0, 1500)
        p.eqReverbId = snap.reverbId
        pushValuesToEffects(
            snap.enabled, snap.presetId, snap.bandsCsv,
            snap.bass, snap.virtual, snap.loudness, snap.reverbId, activeSchemeId(p),
        )
        apply(snap.enabled)
    }

    /**
     * 还原一组音效值（场景退出 / 外部快照）。
     * [schemeId] 写回当前模式对应的方案位（车载 / 手机）。
     */
    fun restoreSoundValues(
        enabled: Boolean,
        presetId: String,
        bandsCsv: String,
        bass: Int,
        virtual: Int,
        loudness: Int,
        reverbId: String,
        schemeId: String,
    ) {
        val p = prefs ?: return
        p.eqEnabled = enabled
        p.eqPresetId = presetId
        p.eqBands = bandsCsv
        p.eqBassBoost = bass.coerceIn(0, 1000)
        p.eqVirtualizer = virtual.coerceIn(0, 1000)
        p.eqLoudness = loudness.coerceIn(0, 1500)
        p.eqReverbId = reverbId
        if (p.carMode) p.carSchemeId = schemeId else p.soundSchemeId = schemeId
        pushValuesToEffects(
            enabled, presetId, bandsCsv,
            bass, virtual, loudness, reverbId, schemeId,
        )
        apply(enabled)
    }

    /**
     * A/B 试听（按住对比原声）：[bypassed] 期间所有效果器临时旁路。
     * 只动**运行态开关**，不碰任何落盘值 —— 松手即按用户原设置恢复，
     * 中途杀进程也只会丢掉「正在按着」这个瞬态。
     */
    fun setBypassed(value: Boolean) {
        bypassed = value
        val s = _state.value
        if (!s.available) return
        apply(if (value) false else s.enabled)
    }

    /** 绑定播放会话（服务端调用；sessionId 未就绪时传入 0 延后绑定） */
    fun attach(audioSessionId: Int) {
        prefs ?: return
        if (audioSessionId != 0) sessionId = audioSessionId
        if (sessionId == 0) {
            // 还没有会话：明确标成「等播放器」，别让界面把它说成「设备不支持」
            _state.value = EqState(
                available = false,
                unavailable = Unavailable.SESSION_PENDING,
                userPresets = loadUserPresets(),
            )
            return
        }
        releaseEffects()
        // 先按播放会话建；车机上多半会失败，失败就退到 audioSession 0（系统输出）。
        // 两条路都失败才判定「设备不支持」。
        val bound = runCatching { bindEffects(sessionId, global = false) }
        if (bound.isFailure && sessionId != 0) {
            val fallback = runCatching { bindEffects(0, global = true) }
            if (fallback.isSuccess) return
            reportFailure(fallback.exceptionOrNull() ?: bound.exceptionOrNull())
            return
        }
        bound.onFailure { reportFailure(it) }
    }

    /**
     * 建一条效果链并写状态；**失败直接抛**，由 [attach] 决定是退到全局还是判定不支持。
     *
     * [global] 只给状态留痕（界面要提示「作用范围是整个系统输出」），不参与构造。
     */
    private fun bindEffects(session: Int, global: Boolean) {
        val p = prefs ?: return
        val eq = Equalizer(0, session)
        val bassMax = runCatching { BassBoost(0, session) }.getOrNull()
        val virt = runCatching { Virtualizer(0, session) }.getOrNull()
        val loud = runCatching { LoudnessEnhancer(session) }.getOrNull()
        // 混响单独 runCatching：拿不到就只少一档功能，不能连带把均衡器整页判成不可用
        val rev = runCatching { PresetReverb(0, session) }.getOrNull()

        // 从这里往下任一步抛异常，上面这五个**已经构造成功**的效果器就再也没人
        // 能 release 了：字段赋值在最后，抛出时引用还没挂上去，而它们占的是
        // native 侧的 binder 句柄 + audioflinger 实例，不是普通 Java 对象。
        // 车机上效果链常常建不起来（retryIfUnavailable 每次起播都重跑一整轮），
        // 漏一套就是漏一套，且会随起播次数累加。所以先兜住、回收、再原样抛出
        // —— 抛出的语义不变，[attach] 仍然照旧退到全局或判定不支持。
        try {
            bindEffectsConfigured(p, eq, bassMax, virt, loud, rev, global)
        } catch (t: Throwable) {
            runCatching { eq.release() }
            runCatching { bassMax?.release() }
            runCatching { virt?.release() }
            runCatching { loud?.release() }
            runCatching { rev?.release() }
            throw t
        }
    }

    /** [bindEffects] 里「构造之后」的那一段，抽出来只为让异常路径能回收效果器 */
    private fun bindEffectsConfigured(
        p: com.luckylearning.windtone.data.Prefs,
        eq: Equalizer,
        bassMax: BassBoost?,
        virt: Virtualizer?,
        loud: LoudnessEnhancer?,
        rev: PresetReverb?,
        global: Boolean,
    ) {
        val bandCount = eq.numberOfBands.toInt()
        val min: Int = eq.bandLevelRange[0].toInt()
        val max: Int = eq.bandLevelRange[1].toInt()
        val centers = (0 until bandCount).map { i ->
            val hz = eq.getCenterFreq(i.toShort()) / 100 // -> Hz
            if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
        }
        val centerHz = (0 until bandCount).map { i -> eq.getCenterFreq(i.toShort()) / 100 }

        // 内置预设按设备频段重算曲线；用户预设 / 手调曲线用存下来的频段
        val builtin = EqPresets.byId(p.eqPresetId)
        val users = loadUserPresets()
        val user = users.firstOrNull { it.id == p.eqPresetId }
        if (UserEqPresets.isUserId(p.eqPresetId) && user == null) p.eqPresetId = ""
        val bands = if (builtin != null) {
            EqPresets.curveFor(builtin, centerHz)
        } else {
            decodeBands(p.eqBands, bandCount, min, max)
        }
        applyBands(eq, bands)

        bassMax?.setStrength(p.eqBassBoost.coerceIn(0, 1000).toShort())
        virt?.setStrength(p.eqVirtualizer.coerceIn(0, 1000).toShort())
        runCatching { loud?.setTargetGain(p.eqLoudness.coerceIn(0, 1500)) }

        // 混响：存的是 id，平台要的是档位；存下来的 id 认不出来就退回「关闭」
        val reverbPreset = ReverbPresets.byId(p.eqReverbId) ?: ReverbPresets.OFF
        runCatching { rev?.setPreset(reverbPreset.platform) }

        equalizer = eq
        bass = bassMax
        virtualizer = virt
        loudness = loud
        reverb = rev

        _state.value = EqState(
            available = true,
            enabled = p.eqEnabled,
            bandCount = bandCount,
            bandMinMb = min,
            bandMaxMb = max,
            bandLevels = bands,
            centerFreqs = centers,
            bandCenterHz = centerHz,
            presetId = builtin?.id ?: user?.id.orEmpty(),
            bassStrength = p.eqBassBoost,
            virtualStrength = p.eqVirtualizer,
            bassMax = 1000,
            loudnessGain = p.eqLoudness,
            reverbId = p.eqReverbId,
            reverbAvailable = rev != null,
            globalEffects = global,
            schemeId = activeSchemeId(p),
            userPresets = users,
        )
        SoftEqPath.onHardBandLayout(centerHz)
        SoftEqPath.syncCurve(p.eqPresetId, p.eqBands, centerHz.toIntArray())
        SoftEqPath.onHardEqAvailable()
        if (p.eqEnabled) {
            apply(true)
            if (bypassed) apply(false)
        } else {
            // A6：读完频段元数据后立刻拆掉，避免关闭态仍占 AudioEffect 会话
            releaseEffects()
        }
    }

    /**
     * 记录「为什么建不出来」。
     *
     * 会话与系统输出两条路都失败才会走到这里。原始异常文案留着，
     * 设置页只在诊断行里展示，正常界面不吓人。
     */
    private fun reportFailure(t: Throwable?) {
        _state.value = EqState(
            available = false,
            unavailable = Unavailable.DEVICE_REJECTED,
            unavailableDetail = t?.let { it.javaClass.simpleName + ": " + (it.message ?: "") }.orEmpty(),
            userPresets = loadUserPresets(),
        )
        // E2 soft-EQ gate: DEVICE_REJECTED -> candidate; soft on only if user confirm / SoftEqSpike
        SoftEqPath.onHardEqUnavailable(Unavailable.DEVICE_REJECTED)
    }

    /**
     * 播放真正开始后再试一次绑定（v1.1.22）。
     *
     * 为什么需要：`audioSessionId` 在部分机型上要等第一次真正输出音频才拿到，
     * 而 [attach] 在拿到 0 时只能先记「等播放器」。此前没有任何补试机制，
     * 于是「先开 App 后放歌」的用户会一直停在不可用状态，只有杀进程重来才好。
     */
    fun retryIfUnavailable(audioSessionId: Int) {
        val s = _state.value
        if (s.available) return
        if (audioSessionId == 0 && sessionId == 0) return
        if (audioSessionId != 0) sessionId = audioSessionId
        attach(sessionId)
    }

    // ------------------------------------------------------- 用户预设

    /** 选中一条用户预设：曲线和增强一起套上，并打开总开关。方案选中态清掉。 */
    fun applyUserPreset(id: String) {
        val p = prefs ?: return
        val preset = loadUserPresets().firstOrNull { it.id == id } ?: return
        p.eqEnabled = true
        p.eqPresetId = preset.id
        p.eqBands = preset.bandsCsv
        p.eqBassBoost = preset.bass
        p.eqVirtualizer = preset.virtualizer
        p.eqLoudness = preset.loudness
        p.eqReverbId = preset.reverbId
        clearActiveSchemeId()
        leaveSceneIfManual()
        pushValuesToEffects(
            true, preset.id, preset.bandsCsv,
            preset.bass, preset.virtualizer, preset.loudness, preset.reverbId, "",
        )
        apply(true)
    }

    /**
     * 把当前曲线和增强存成一条新的用户预设，并选中它。
     * 名字为空、或已经存满时返回 null，调用方给一句说明即可。
     */
    fun saveUserPreset(name: String): UserEqPresets.Preset? {
        val p = prefs ?: return null
        val s = _state.value
        val bands = if (s.bandLevels.isNotEmpty()) encodeBands(s.bandLevels) else p.eqBands
        val created = UserEqPresets.create(
            name, bands, s.bassStrength, s.virtualStrength, s.loudnessGain, s.reverbId,
        ) ?: return null
        val list = UserEqPresets.add(loadUserPresets(), created) ?: return null
        p.eqPresetId = created.id
        p.eqBands = bands
        p.eqBassBoost = created.bass
        p.eqVirtualizer = created.virtualizer
        p.eqLoudness = created.loudness
        p.eqReverbId = created.reverbId
        clearActiveSchemeId()
        storeUserPresets(list)
        _state.value = _state.value.copy(presetId = created.id, schemeId = "", userPresets = list)
        return created
    }

    fun renameUserPreset(id: String, name: String): Boolean {
        val list = UserEqPresets.rename(loadUserPresets(), id, name) ?: return false
        storeUserPresets(list)
        return true
    }

    /** 用当前曲线和增强覆盖这条用户预设，名字和 id 不动，选中态留着。 */
    fun updateUserPreset(id: String): Boolean {
        val p = prefs ?: return false
        val s = _state.value
        val bands = if (s.bandLevels.isNotEmpty()) encodeBands(s.bandLevels) else p.eqBands
        val list = UserEqPresets.rewrite(
            loadUserPresets(), id, bands,
            s.bassStrength, s.virtualStrength, s.loudnessGain, s.reverbId,
        ) ?: return false
        p.eqPresetId = id
        p.eqBands = bands
        clearActiveSchemeId()
        storeUserPresets(list)
        _state.value = _state.value.copy(presetId = id, schemeId = "", userPresets = list)
        return true
    }

    /** 删掉一条。正在用它时回到「自定义」，当前声音保留，不把曲线清掉。 */
    fun deleteUserPreset(id: String) {
        val list = UserEqPresets.remove(loadUserPresets(), id)
        storeUserPresets(list)
        if (_state.value.presetId == id) {
            prefs?.eqPresetId = ""
            _state.value = _state.value.copy(presetId = "", userPresets = list)
        }
    }

    /** 当前「我的」预设的导出 JSON（可空列表）。 */
    fun exportUserPresetsJson(): String =
        UserEqPresets.toExportJson(loadUserPresets())

    /**
     * 从导出 JSON 导入。解析失败返回 null；成功则按 [mode] 合并或覆盖并落盘。
     * 若当前选中的用户预设在覆盖后不在新列表里，选中态回到「自定义」。
     */
    fun importUserPresetsFromJson(
        raw: String,
        mode: UserEqPresets.ImportMode,
    ): UserEqPresets.ImportOutcome? {
        val imported = UserEqPresets.fromExportJson(raw) ?: return null
        val outcome = UserEqPresets.importPresets(loadUserPresets(), imported, mode)
        storeUserPresets(outcome.presets)
        val cur = _state.value.presetId
        if (UserEqPresets.isUserId(cur) && outcome.presets.none { it.id == cur }) {
            prefs?.eqPresetId = ""
            _state.value = _state.value.copy(presetId = "", userPresets = outcome.presets)
        }
        return outcome
    }

    /**
     * 高音快捷旋钮：把 4 kHz 及以上的频段收成同一增益。
     * 曲线变了，内置预设和用户预设、方案一并离开。
     */
    fun setTrebleGainMb(gainMb: Int) {
        val eq = equalizer ?: return
        val s = _state.value
        if (s.bandCount <= 0) return
        val levels = EqTone.applyTreble(s.bandLevels, s.bandCenterHz, gainMb, s.bandMinMb, s.bandMaxMb)
        applyBands(eq, levels)
        prefs?.eqPresetId = ""
        prefs?.eqBands = encodeBands(levels)
        clearActiveSchemeId()
        _state.value = s.copy(bandLevels = levels, presetId = "", schemeId = "")
    }

    fun release() {
        releaseEffects()
        sessionId = 0
        _state.value = EqState(
            available = false,
            unavailable = Unavailable.SESSION_PENDING,
            userPresets = loadUserPresets(),
        )
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        runCatching { reverb?.release() }
        equalizer = null
        bass = null
        virtualizer = null
        loudness = null
        reverb = null
    }

    // ------------------------------------------------------------- UI 入口

    fun setEnabled(enabled: Boolean) {
        prefs?.eqEnabled = enabled
        _state.value = _state.value.copy(enabled = enabled)
        if (enabled) {
            // 关闭时 A6 已 release；再开按会话重建，避免空指针 apply
            if (equalizer == null && sessionId != 0) {
                attach(sessionId)
            } else {
                apply(!bypassed)
            }
        } else if (bypassed) {
            // A/B 旁路期间只熄效果，不拆实例（松手还要立刻回到原设置）
            apply(false)
        } else {
            apply(false)
            releaseEffects()
        }
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        val eq = equalizer ?: return
        val s = _state.value
        val clamped = levelMb.coerceIn(s.bandMinMb, s.bandMaxMb)
        val levels = s.bandLevels.copyOf().also { it[band] = clamped }
        applyBands(eq, levels)
        // 手动拖滑块 = 离开预设，进入自定义
        prefs?.eqPresetId = ""
        prefs?.eqBands = encodeBands(levels)
        clearActiveSchemeId()
        leaveSceneIfManual()
        _state.value = s.copy(bandLevels = levels, presetId = "", schemeId = "")
        syncSoftEqCurve()
    }

    /**
     * 应用内置预设：按设备实际频段中心频率插值出曲线，直接写频段
     * （不再走 [Equalizer.usePreset]——平台预设名各 ROM 不同且无法本地化）。
     */
    fun setPreset(id: String) {
        val eq = equalizer ?: return
        val s = _state.value
        val preset = EqPresets.byId(id) ?: return
        val levels = EqPresets.curveFor(preset, s.bandCenterHz)
        applyBands(eq, levels)
        prefs?.eqPresetId = preset.id
        prefs?.eqBands = encodeBands(levels)
        clearActiveSchemeId()
        leaveSceneIfManual()
        _state.value = s.copy(presetId = preset.id, bandLevels = levels, schemeId = "")
        syncSoftEqCurve()
    }

    fun setBassStrength(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        prefs?.eqBassBoost = v
        bass?.setStrength(v.toShort())
        leaveEnhancementEdit()
        _state.value = _state.value.copy(bassStrength = v)
    }

    fun setVirtualStrength(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        prefs?.eqVirtualizer = v
        virtualizer?.setStrength(v.toShort())
        leaveEnhancementEdit()
        _state.value = _state.value.copy(virtualStrength = v)
    }

    /** 响度增强：0 = 关闭，单位 mB（毫贝） */
    fun setLoudnessGain(gainMb: Int) {
        val v = gainMb.coerceIn(0, 1500)
        prefs?.eqLoudness = v
        runCatching { loudness?.setTargetGain(v) }
        leaveEnhancementEdit()
        _state.value = _state.value.copy(loudnessGain = v)
        apply(_state.value.enabled)
    }

    /**
     * 选择混响档位（[ReverbPresets] 的 id）。
     *
     * 与均衡器预设的差别：混响**没有滑块**，档位完全由平台效果器给死，
     * 所以这里只有「换一档」这一种交互，`ReverbPresets.OFF` 即关闭。
     * 本机没有混响效果器时（`reverb` 为 null）只落盘，界面照常记住选择。
     */
    fun setReverbPreset(id: String) {
        val preset = ReverbPresets.byId(id) ?: ReverbPresets.OFF
        prefs?.eqReverbId = preset.id
        runCatching { reverb?.setPreset(preset.platform) }
        leaveEnhancementEdit()
        _state.value = _state.value.copy(reverbId = preset.id)
        apply(_state.value.enabled)
    }

    /**
     * 恢复出厂：关掉均衡器、频段全平、三处增强归零、混响回到关闭（v1.1.4「恢复默认」）。
     *
     * 写回的就是**全新安装时那份值** —— 与 `Prefs` 的 `eqEnabled` / `eqPresetId` /
     * `eqBands` / `eqBassBoost` / `eqVirtualizer` / `eqLoudness` / `eqReverbId`
     * 默认值一一对应。
     *
     * 为什么不是应用 [EqPresets.FLAT] 预设：恢复默认的语义是「什么都没选过」，
     * 界面应显示「自定义 + 全平」，而不是停在「原声」预设上。直接把频段写 0
     * 并把 `eqPresetId` 清空，与实际状态一致。混响同理：回到「关闭」而不是某一档。
     */
    fun reset() {
        prefs?.apply {
            eqEnabled = false
            eqPresetId = ""
            eqBassBoost = 0
            eqVirtualizer = 0
            eqLoudness = 0
            eqReverbId = ""
            // 音效方案与进车快照一并回厂（v1.1.52）：快照留着会在下次退出车载时
            // 把旧值还回来，盖掉这次 reset 的效果
            soundSchemeId = SettingsDefaults.SOUND_SCHEME_ID
            carSchemeId = SettingsDefaults.CAR_SCHEME_ID
            soundStash = SettingsDefaults.SOUND_STASH
        }

        val s = _state.value
        // 播放器还没起来（未开始播放 / 设备不支持）：只落盘，界面保持「不可用」，
        // 但要把**为什么不可用**原样带过去，否则重置一次就把诊断信息抹成「未启动」
        if (!s.available || s.bandCount <= 0) {
            _state.value = EqState(
                available = false,
                unavailable = s.unavailable,
                unavailableDetail = s.unavailableDetail,
                userPresets = loadUserPresets(),
            )
            return
        }

        val flat = IntArray(s.bandCount)
        prefs?.eqBands = encodeBands(flat)
        syncSoftEqCurve()
        equalizer?.let { applyBands(it, flat) }
        runCatching { bass?.setStrength(0) }
        runCatching { virtualizer?.setStrength(0) }
        runCatching { loudness?.setTargetGain(0) }
        runCatching { reverb?.setPreset(ReverbPresets.OFF.platform) }
        _state.value = s.copy(
            enabled = false,
            presetId = "",
            bandLevels = flat,
            bassStrength = 0,
            virtualStrength = 0,
            loudnessGain = 0,
            reverbId = "",
            schemeId = "",
            userPresets = loadUserPresets(),
        )
        // 必须放在状态更新**之后**：apply 会读 `_state.value` 里的三处强度
        // 决定要不要启用对应 effect。
        apply(false)
        // A6：恢复默认即关闭硬 EQ，拆掉会话效果器
        if (!bypassed) releaseEffects()
    }

    private fun apply(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
        runCatching { bass?.enabled = enabled && _state.value.bassStrength > 0 }
        runCatching { virtualizer?.enabled = enabled && _state.value.virtualStrength > 0 }
        runCatching { loudness?.enabled = enabled && _state.value.loudnessGain > 0 }
        // 混响：off 档不使能效果器（`setPreset(PRESET_NONE)` 之后也没必要再开着这条链）
        runCatching {
            val preset = ReverbPresets.byId(_state.value.reverbId)
            reverb?.enabled = enabled && preset != null && preset != ReverbPresets.OFF
        }
    }

    /** E3: push current Prefs / state curve onto SoftEqPath (ready for SOFT_ACTIVE). */
    private fun syncSoftEqCurve() {
        val p = prefs ?: return
        val hardCenters = _state.value.bandCenterHz
        SoftEqPath.syncCurve(
            presetId = p.eqPresetId,
            bandsCsv = p.eqBands,
            hardCentersHz = hardCenters.takeIf { it.isNotEmpty() }?.toIntArray(),
        )
    }

    private fun applyBands(eq: Equalizer, levels: IntArray) {
        for (i in levels.indices) {
            runCatching { eq.setBandLevel(i.toShort(), levels[i].coerceIn(-1500, 1500).toShort()) }
        }
    }

    private fun decodeBands(stored: String, count: Int, min: Int, max: Int): IntArray {
        val parts = stored.split(',').filter { it.isNotBlank() }
        if (parts.size == count) {
            return parts.mapNotNull { it.trim().toIntOrNull() }.map { it.coerceIn(min, max) }.toIntArray()
        }
        // 默认全 0（平直）
        return IntArray(count) { 0 }
    }

    private fun encodeBands(levels: IntArray): String = levels.joinToString(",")
}
