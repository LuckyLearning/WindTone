package com.luckylearning.windtone.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 左右声道平衡（#9）执行者：Prefs → [StereoBalanceAudioProcessor]。
 *
 * 与 [TrackVolume] 同属「不依赖 AudioEffect」的听感调节：车机
 * [AudioFx.Unavailable.DEVICE_REJECTED] 时仍可用。挂到
 * [PlaybackService] 的 [androidx.media3.exoplayer.DefaultRenderersFactory.buildAudioSink]。
 *
 * B3：balance==0 时 processor.isActive=false，Media3 跳过（不挂混音）。
 */
/** 自身消费 Media3 UnstableApi；勿标 @UnstableApi，避免 UI/Engine 触发 lint UnsafeOptInUsageError。 */
@androidx.annotation.OptIn(UnstableApi::class)
object StereoBalanceFx {

    data class State(val balance: Int = SettingsDefaults.STEREO_BALANCE)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: Prefs? = null

    /** 单例 processor：ExoPlayer 音频链持有引用；居中时 inactive */
    val processor: StereoBalanceAudioProcessor = StereoBalanceAudioProcessor().also {
        it.setBalance(SettingsDefaults.STEREO_BALANCE)
    }

    fun init(context: Context) {
        if (prefs != null) return
        prefs = Prefs(context.applicationContext)
        val b = StereoBalance.coerce(prefs!!.stereoBalance)
        processor.setBalance(b)
        _state.value = State(balance = b)
    }

    fun setBalance(balance: Int) {
        val b = StereoBalance.coerce(balance)
        prefs?.stereoBalance = b
        processor.setBalance(b)
        _state.value = State(balance = b)
    }

    fun reset() {
        setBalance(StereoBalance.DEFAULT)
    }
}
