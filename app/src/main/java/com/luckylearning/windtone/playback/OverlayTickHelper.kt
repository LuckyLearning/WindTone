package com.luckylearning.windtone.playback

import com.luckylearning.windtone.lyrics.OverlayContentResolver
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.state.PlayerUiState

/**
 * 进度轮询 tick 里「歌词投屏 + 悬浮窗自愈」这一段的逻辑，从 [com.luckylearning.windtone.playback.PlaybackEngine]
 * 抽出，便于单测节流与转发条件。引擎的 ticker 只负责读控制器、写进度、调度，
 * 这一段交给本对象——它本身不持有任何引擎状态，所有输入都是参数。
 *
 * 三段职责：
 *  - [shouldPersistPosition]：进度落库的 5 秒节流判定（纯函数）
 *  - [shouldReviveOverlay]：悬浮窗服务被系统杀掉后的 30 秒自愈节流判定（纯函数）
 *  - [publishLyric]：把当前歌词行送到桌面悬浮窗（去重在 [LyricOverlayBridge] 内部按内容比对）
 */
object OverlayTickHelper {

    /**
     * 是否把当前播放进度落库：每 [intervalMs] 一次，且 [posMs] > 0
     * （0 表示还没真正播起来，落库没意义）。纯函数，便于单测 5 秒节流。
     */
    fun shouldPersistPosition(posMs: Long, lastSavedAt: Long, now: Long, intervalMs: Long): Boolean {
        return posMs > 0L && (now - lastSavedAt) >= intervalMs
    }

    /**
     * 悬浮窗服务被系统杀掉后，这一 tick 是否应该尝试拉起它。
     *
     * 约束（任一不满足就不动作）：
     *  - [overlayEnabled]：悬浮窗开关要开；
     *  - [serviceAlive]：服务确实挂了（还活着就别多事）；
     *  - 距上次尝试已超过 [intervalMs]：防「每切一首歌重启一次前台服务」的死循环。
     *
     * 纯函数，便于单测 30 秒节流与开关 / 存活判定。
     */
    fun shouldReviveOverlay(
        overlayEnabled: Boolean,
        serviceAlive: Boolean,
        lastReviveAt: Long,
        now: Long,
        intervalMs: Long,
    ): Boolean {
        if (!overlayEnabled) return false
        if (serviceAlive) return false
        if (now - lastReviveAt < intervalMs) return false
        return true
    }

    /**
     * 把当前歌词行送到桌面悬浮窗。
     *
     * 「该显示哪一句」交给 [OverlayContentResolver]——前奏回落歌名、间奏回溯上一句、
     * 上下文行、双语副行都由它一次算清；这里只负责把播放位置和曲目信息喂进去。
     * 去重放在 [LyricOverlayBridge] 内部按内容比对完成，所以每 tick 都推，
     * 内容不变时不会真的去动 View。无歌词时 resolver 会回落成歌名，连歌名都没有
     * 时才算「没东西可显示」，窗口保持隐藏。
     */
    fun publishLyric(positionMs: Long, playing: Boolean, state: PlayerUiState) {
        val resolved = OverlayContentResolver.resolve(
            lyrics = state.lyrics,
            positionMs = positionMs,
            offsetMs = state.lyricOffsetMs,
            title = state.current?.title.orEmpty(),
        )
        LyricOverlayBridge.publish(
            resolved = resolved,
            playing = playing,
            title = state.current?.title.orEmpty(),
            // 歌手未知时留空，让悬浮窗只显示歌名一行 —— 谁也不想看一行「未知歌手」
            artist = state.current?.artist
                ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                .orEmpty(),
        )
    }
}
