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
 * 0.2.24 起按 Cardash 的 `N3.b` 补两条，直接决定「当前行对不对得上」：
 *
 *  1. **`position < 0`（发布端说 `PLAYBACK_POSITION_UNKNOWN`）不能当 0**。旧实现
 *     `coerceAtLeast(0)` 把它清成 0，于是播放器每发一次不带进度的回调，歌词就从
 *     第一句重新爬 —— 真机上表现正是「当前行老是慢半拍、或者干脆停在开头」。
 *     现在这种更新只改状态与倍速，**锚点保持不动**，继续外推。
 *  2. **外推结果夹到时长**（Cardash `N3.b` 的 `min(position, duration)`）：结尾
 *     几十秒里播放器常把 position 报大或停更，不夹会让定位越过最后一行。
 */
class SessionClock(private val nowMs: () -> Long) {

    private var anchorPosition: Long = 0L
    private var anchorAt: Long = 0L
    private var speed: Float = 1f
    private var state: PlayState = PlayState.NONE
    private var durationMs: Long = 0L

    /**
     * @param positionMs 发布端给的进度；**小于 0 表示它没给**，此时保留旧锚点。
     * @param durationMs 歌曲总长；<= 0 表示未知，不夹取。
     */
    fun update(
        positionMs: Long,
        playbackSpeed: Float,
        playState: PlayState,
        durationMs: Long = 0L
    ) {
        if (durationMs > 0L) this.durationMs = durationMs
        speed = if (playbackSpeed <= 0f) 1f else playbackSpeed

        if (positionMs >= 0L) {
            anchorPosition = positionMs
            anchorAt = nowMs()
        } else if (playState != state) {
            // 状态变了但没给进度（暂停 / 缓冲最常见）：把「此刻推算到的位置」钉成新锚点，
            // 否则暂停期间继续按 speed 外推，恢复播放时已经偏出去几秒。
            val current = position()
            if (current >= 0L) {
                anchorPosition = current
                anchorAt = nowMs()
            }
        }
        state = playState
    }

    fun state(): PlayState = state

    /** 返回 -1 表示发布端给了 `PLAYBACK_POSITION_UNKNOWN`，调用方应退化为静态显示。 */
    fun position(): Long {
        if (state == PlayState.NONE || state == PlayState.STOPPED) return -1L
        val raw = if (state == PlayState.PLAYING) {
            val elapsed = (nowMs() - anchorAt).coerceAtLeast(0L)
            anchorPosition + (elapsed * speed).toLong()
        } else {
            anchorPosition
        }
        val floored = raw.coerceAtLeast(0L)
        return if (durationMs > 0L) floored.coerceAtMost(durationMs) else floored
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
