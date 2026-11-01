package com.luckylearning.windtone.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * 按 URI scheme 在「本地小缓冲 / 远程大缓冲」两套 [DefaultLoadControl] 间切换。
 *
 * ## 为什么是双实例而不是改时长
 *
 * Media3 [DefaultLoadControl] 建完后缓冲阈值不可变；重建 ExoPlayer 代价大。
 * 两套控制共享同一 [DefaultAllocator]，生命周期回调两边都走，避免切场景时
 * `loadingStates` 缺 key；决策方法只问当前场景那一套。
 *
 * ## 何时切换
 *
 * - [updateFromUri] / [updateFromMediaItem]：切歌监听里尽早切换；
 * - [shouldContinueLoading] / [shouldStartPlayback]：再按 Timeline 窗口核对一次，
 *   防止监听漏掉或 playlist 批量替换时场景滞后。
 */
@UnstableApi
class SceneLoadControl : LoadControl {

    private val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    private val remote: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            LoadControlScenePolicy.REMOTE_MIN_BUFFER_MS,
            LoadControlScenePolicy.REMOTE_MAX_BUFFER_MS,
            LoadControlScenePolicy.REMOTE_BUFFER_FOR_PLAYBACK_MS,
            LoadControlScenePolicy.REMOTE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val local: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            LoadControlScenePolicy.LOCAL_MIN_BUFFER_MS,
            LoadControlScenePolicy.LOCAL_MAX_BUFFER_MS,
            LoadControlScenePolicy.LOCAL_BUFFER_FOR_PLAYBACK_MS,
            LoadControlScenePolicy.LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    @Volatile
    private var useRemote: Boolean = true

    /** 诊断 / 单测：当前是否走远程大缓冲。默认 true（稳妥）。 */
    val isRemoteScene: Boolean
        get() = useRemote

    fun updateFromUri(uri: Uri?) {
        useRemote = LoadControlScenePolicy.useRemoteBuffers(uri)
    }

    fun updateFromMediaItem(mediaItem: MediaItem?) {
        updateFromUri(mediaItem?.localConfiguration?.uri)
    }

    private fun active(): DefaultLoadControl = if (useRemote) remote else local

    private fun syncSceneFromParameters(parameters: LoadControl.Parameters) {
        val timeline = parameters.timeline
        if (timeline.isEmpty) return
        val period = timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, Timeline.Period())
        val window = timeline.getWindow(period.windowIndex, Timeline.Window())
        updateFromMediaItem(window.mediaItem)
    }

    override fun onPrepared(playerId: PlayerId) {
        remote.onPrepared(playerId)
        local.onPrepared(playerId)
    }

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) {
        if (!timeline.isEmpty) {
            val period = timeline.getPeriodByUid(mediaPeriodId.periodUid, Timeline.Period())
            val window = timeline.getWindow(period.windowIndex, Timeline.Window())
            updateFromMediaItem(window.mediaItem)
        }
        remote.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
        local.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        remote.onStopped(playerId)
        local.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        remote.onReleased(playerId)
        local.onReleased(playerId)
    }

    override fun getAllocator(): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        active().getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        active().retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        syncSceneFromParameters(parameters)
        return active().shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        syncSceneFromParameters(parameters)
        return active().shouldStartPlayback(parameters)
    }
}
