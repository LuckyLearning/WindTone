package com.luckylearning.windtone.playback.softEq

import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.playback.AudioFx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 软 EQ 主路径门面（E1）：把 [SoftEqAudioProcessor] 挂进 Media3 音频链，可开关。
 *
 * - 默认 **关**（[enabled]=false）；PCM 链里始终挂着 processor，未启用时 [SoftEqAudioProcessor.isActive]
 *   为 false，直通，不改听感。
 * - [onHardEqUnavailable] 是系统 EQ DEVICE_REJECTED 时的挂钩点；**E1 不自动打开**
 *   （[SoftEqSpike.ENABLED] 仍为 false）。E2 再做门控自动启用。
 * - 曲线桥接 / Prefs / 文案 / 诊断全套属 E3–E5，本对象不涉及。
 *
 * 接线：PlaybackService → DefaultRenderersFactory.buildAudioSink →
 * DefaultAudioSink.setAudioProcessors（软 EQ 在声道平衡之前）。
 *
 * 文档：docs/功能-软EQ.md；Spike 结论见 docs/spike-软EQ.md。
 */
@UnstableApi
object SoftEqPath {

    data class State(
        /** 运行时总开关；与 SoftEqSpike.ENABLED（自动回退总闸）独立 */
        val enabled: Boolean = false,
        /** 最近一次硬 EQ 不可用回调原因（诊断用；E5 再进诊断包） */
        val lastHardUnavailable: AudioFx.Unavailable = AudioFx.Unavailable.NONE,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** ExoPlayer 音频链持有的单例；UI / AudioFx 只改开关与曲线 */
    val processor: SoftEqAudioProcessor = SoftEqAudioProcessor()

    val enabled: Boolean get() = _state.value.enabled

    fun setEnabled(on: Boolean) {
        processor.setEnabled(on)
        _state.value = _state.value.copy(enabled = on)
    }

    /**
     * 系统硬 EQ 不可用时的挂钩（E1 骨架）。
     *
     * 仅记录 [AudioFx.Unavailable]；当且仅当 [SoftEqSpike.ENABLED] 为 true 时才会
     * setEnabled(true)（当前恒 false，故 E1 听感不变）。E2 在此做 DEVICE_REJECTED 门控。
     */
    fun onHardEqUnavailable(reason: AudioFx.Unavailable) {
        _state.value = _state.value.copy(lastHardUnavailable = reason)
        if (reason != AudioFx.Unavailable.DEVICE_REJECTED) return
        if (SoftEqSpike.ENABLED) setEnabled(true)
    }

    /** 测试 / 冷启动复位：关软 EQ，清挂钩记录 */
    fun resetForTest() {
        processor.setEnabled(false)
        processor.setCurve(
            SoftEqEngine.DEFAULT_CENTERS_HZ,
            IntArray(SoftEqEngine.DEFAULT_CENTERS_HZ.size),
        )
        _state.value = State()
    }
}
