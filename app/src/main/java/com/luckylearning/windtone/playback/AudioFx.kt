package com.luckylearning.windtone.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.luckylearning.windtone.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val presetNames: List<String> = emptyList(),
        val presetIndex: Int = -1,
        val bassStrength: Int = 0,
        val virtualStrength: Int = 0,
        val bassMax: Int = 1000,
        /** 响度增强目标增益（mB，0 表示关闭） */
        val loudnessGain: Int = 0,
        val loudnessMax: Int = 1500,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var prefs: Prefs? = null
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    fun init(context: android.content.Context) {
        if (prefs == null) prefs = Prefs(context.applicationContext)
    }

    /** 绑定播放会话（服务端调用；sessionId 未就绪时传入 0 延后绑定） */
    fun attach(audioSessionId: Int) {
        val p = prefs ?: return
        if (audioSessionId != 0) sessionId = audioSessionId
        if (sessionId == 0) return
        releaseEffects()

        runCatching {
            val eq = Equalizer(0, sessionId)
            val bassMax = runCatching { BassBoost(0, sessionId) }.getOrNull()
            val virt = runCatching { Virtualizer(0, sessionId) }.getOrNull()
            val loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

            val bandCount = eq.numberOfBands.toInt()
            val min: Int = eq.bandLevelRange[0].toInt()
            val max: Int = eq.bandLevelRange[1].toInt()
            val centers = (0 until bandCount).map { i ->
                val hz = eq.getCenterFreq(i.toShort()) / 100 // -> Hz
                if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
            }
            val presets = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }

            val bands = decodeBands(p.eqBands, bandCount, min, max)
            applyBands(eq, bands)

            bassMax?.setStrength(p.eqBassBoost.coerceIn(0, 1000).toShort())
            virt?.setStrength(p.eqVirtualizer.coerceIn(0, 1000).toShort())
            runCatching { loud?.setTargetGain(p.eqLoudness.coerceIn(0, 1500)) }

            equalizer = eq
            bass = bassMax
            virtualizer = virt
            loudness = loud

            _state.value = EqState(
                available = true,
                enabled = p.eqEnabled,
                bandCount = bandCount,
                bandMinMb = min,
                bandMaxMb = max,
                bandLevels = bands,
                centerFreqs = centers,
                presetNames = presets,
                presetIndex = p.eqPreset,
                bassStrength = p.eqBassBoost,
                virtualStrength = p.eqVirtualizer,
                bassMax = 1000,
                loudnessGain = p.eqLoudness,
            )
            apply(p.eqEnabled)
        }.onFailure {
            _state.value = EqState(available = false)
        }
    }

    fun release() {
        releaseEffects()
        sessionId = 0
        _state.value = EqState(available = false)
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bass = null
        virtualizer = null
        loudness = null
    }

    // ------------------------------------------------------------- UI 入口

    fun setEnabled(enabled: Boolean) {
        prefs?.eqEnabled = enabled
        _state.value = _state.value.copy(enabled = enabled)
        apply(enabled)
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        val eq = equalizer ?: return
        val s = _state.value
        val clamped = levelMb.coerceIn(s.bandMinMb, s.bandMaxMb)
        val levels = s.bandLevels.copyOf().also { it[band] = clamped }
        applyBands(eq, levels)
        prefs?.eqPreset = -1
        prefs?.eqBands = encodeBands(levels)
        _state.value = s.copy(bandLevels = levels, presetIndex = -1)
    }

    fun setPreset(index: Int) {
        val eq = equalizer ?: return
        val s = _state.value
        runCatching { eq.usePreset(index.toShort()) }
        val levels = (0 until s.bandCount).map { eq.getBandLevel(it.toShort()).toInt() }.toIntArray()
        prefs?.eqPreset = index
        prefs?.eqBands = encodeBands(levels)
        _state.value = s.copy(presetIndex = index, bandLevels = levels)
    }

    fun setBassStrength(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        prefs?.eqBassBoost = v
        bass?.setStrength(v.toShort())
        _state.value = _state.value.copy(bassStrength = v)
    }

    fun setVirtualStrength(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        prefs?.eqVirtualizer = v
        virtualizer?.setStrength(v.toShort())
        _state.value = _state.value.copy(virtualStrength = v)
    }

    /** 响度增强：0 = 关闭，单位 mB（毫贝） */
    fun setLoudnessGain(gainMb: Int) {
        val v = gainMb.coerceIn(0, 1500)
        prefs?.eqLoudness = v
        runCatching { loudness?.setTargetGain(v) }
        _state.value = _state.value.copy(loudnessGain = v)
        apply(_state.value.enabled)
    }

    /**
     * 恢复出厂：关掉均衡器、频段全平、三处增强归零（v1.1.4「恢复默认」）。
     *
     * 写回的就是**全新安装时那份值** —— 与 `Prefs` 的 `eqEnabled` / `eqPreset` /
     * `eqBassBoost` / `eqVirtualizer` / `eqLoudness` 默认值一一对应。
     *
     * 为什么不是 `usePreset(0)`：平台预设的**名字与曲线各机型不同**，第 0 个
     * 未必是平直（有的 ROM 上直接就是「Rock」）。直接把频段写 0 才是真的全平，
     * 顺带把 `eqPreset` 打回 -1（界面显示「自定义」，与实际状态一致）。
     */
    fun reset() {
        prefs?.apply {
            eqEnabled = false
            eqPreset = -1
            eqBassBoost = 0
            eqVirtualizer = 0
            eqLoudness = 0
        }

        val s = _state.value
        // 播放器还没起来（未开始播放 / 设备不支持）：只落盘，界面保持「不可用」
        if (!s.available || s.bandCount <= 0) {
            _state.value = EqState(available = false)
            return
        }

        val flat = IntArray(s.bandCount)
        prefs?.eqBands = encodeBands(flat)
        equalizer?.let { applyBands(it, flat) }
        runCatching { bass?.setStrength(0) }
        runCatching { virtualizer?.setStrength(0) }
        runCatching { loudness?.setTargetGain(0) }
        _state.value = s.copy(
            enabled = false,
            presetIndex = -1,
            bandLevels = flat,
            bassStrength = 0,
            virtualStrength = 0,
            loudnessGain = 0,
        )
        // 必须放在状态更新**之后**：apply 会读 `_state.value` 里的三处强度
        // 决定要不要启用对应 effect。
        apply(false)
    }

    private fun apply(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
        runCatching { bass?.enabled = enabled && _state.value.bassStrength > 0 }
        runCatching { virtualizer?.enabled = enabled && _state.value.virtualStrength > 0 }
        runCatching { loudness?.enabled = enabled && _state.value.loudnessGain > 0 }
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

    private fun IntArray.toShortArray(): ShortArray = ShortArray(size) { this[it].toShort() }
}
