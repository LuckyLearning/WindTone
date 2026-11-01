package com.luckylearning.windtone.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.playback.softEq.SoftEqPath

/**
 * PlaybackService → DefaultAudioSink 的处理器清单（资源极致 A6/B2/B3）。
 *
 * 两枚单例始终**注册**在 sink 上（避免开/关时重建 AudioSink / ExoPlayer 爆音），
 * 关闭或恒等时靠各自 [AudioProcessor.isActive] == false 让 Media3 跳过——
 * 等价「从处理链卸下」，不改 SoftEq 双二阶滤波核。
 *
 * - SoftEq：总开关关 / 曲线近似平直 → inactive（B2）
 * - StereoBalance：balance==0 → inactive（B3）
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackAudioProcessors {

    fun forAudioSink(): Array<AudioProcessor> = arrayOf(
        SoftEqPath.processor,
        StereoBalanceFx.processor,
    )
}
