package com.anylyric.session

import com.anylyric.lyrics.LyricLine

/** 播放状态。刻意不用 `android.media.session.PlaybackState` 常量，保证可 JVM 单测。 */
enum class PlayState { PLAYING, PAUSED, STOPPED, BUFFERING, NONE }

/**
 * 进度外推 + 行定位。
 *
 * `PlaybackState` 的 position 只在状态变化时更新，播放中必须自己外推：
 * ```
 * pos = anchorPosition + (now - anchorAt) * speed
 * ```
 * 一期不靠它做逐行推进（L1/L2 由元数据事件驱动），只用于暂停判定、
 * 以及 L0 拿到带时间轴的整段歌词时定位当前行。
 */
class SessionClock(private val nowMs: () -> Long) {

    private var anchorPosition: Long = 0L
    private var anchorAt: Long = 0L
    private var speed: Float = 1f
    private var state: PlayState = PlayState.NONE

    fun update(positionMs: Long, playbackSpeed: Float, playState: PlayState) {
        anchorPosition = positionMs.coerceAtLeast(0L)
        anchorAt = nowMs()
        speed = if (playbackSpeed <= 0f) 1f else playbackSpeed
        state = playState
    }

    fun state(): PlayState = state

    /** 返回 -1 表示发布端给了 `PLAYBACK_POSITION_UNKNOWN`，调用方应退化为静态显示。 */
    fun position(): Long {
        if (state == PlayState.NONE || state == PlayState.STOPPED) return -1L
        if (state != PlayState.PLAYING) return anchorPosition
        val elapsed = (nowMs() - anchorAt).coerceAtLeast(0L)
        return anchorPosition + (elapsed * speed).toLong()
    }

    /**
     * 二分查找当前行索引：最后一个 `timeMs <= posMs` 的行。
     * 返回 -1 表示无法定位（空列表、无时间轴、pos 非法）。
     */
    fun locate(lines: List<LyricLine>, posMs: Long): Int {
        if (lines.isEmpty() || posMs < 0L) return -1
        if (lines.any { it.timeMs == null }) return -1

        var low = 0
        var high = lines.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val t = lines[mid].timeMs ?: return -1
            if (t <= posMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }
}
