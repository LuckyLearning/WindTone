package com.luckylearning.windtone.playback

import androidx.media3.exoplayer.ExoPlayer

/**
 * 服务端 [ExoPlayer] 的进程内句柄。
 *
 * 部分 ExoPlayer 专有开关（如跳过静音 `setSkipSilenceEnabled`）**不在 `Player` 接口上**，
 * 因此 `MediaController` 无法转发，走自定义 SessionCommand 又要改一通连接协商。
 * 本应用的播放服务与界面**同进程**，直接持引用更简单——
 * 与 [AudioFx] 采用同一套「进程内单例共享」思路。
 *
 * 句柄为空表示服务尚未启动或已销毁，此时调用会被安全忽略；
 * 真正的开关状态仍持久化在 Prefs，由 `PlaybackService.onCreate` 在启动时套用。
 *
 * `skipSilenceEnabled` / `setSkipSilenceEnabled` 是 Media3 的 `@UnstableApi`：
 * 构建脚本里的 `-opt-in=...UnstableApi` 只有编译器认，lint 仍报 `UnsafeOptInUsageError`，
 * 所以这里显式标注（理由同 `PlaybackService`）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerHandle {

    @Volatile
    var player: ExoPlayer? = null
        internal set

    val skipSilenceEnabled: Boolean
        get() = runCatching { player?.skipSilenceEnabled }.getOrNull() ?: false

    fun setSkipSilence(enabled: Boolean) {
        runCatching { player?.setSkipSilenceEnabled(enabled) }
    }

    /** 跳转到指定播放位置（毫秒） */
    fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms) }
    }
}
