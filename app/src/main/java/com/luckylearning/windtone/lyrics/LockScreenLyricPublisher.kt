package com.luckylearning.windtone.lyrics

import androidx.media3.common.Player
import com.luckylearning.windtone.state.PlayerUiState

/**
 * 把当前歌词行写进 MediaSession 当前项的 [androidx.media3.common.MediaMetadata.description]
 * （Legacy `METADATA_KEY_DISPLAY_DESCRIPTION` / SYNOPSIS），供通知栏与锁屏展示。
 *
 * ## 为什么用 replaceMediaItem
 * Media3 会话元数据来自 Player 队列项；没有「只改 description」的公开 API。
 * 同 index 上 `replaceMediaItem` 只换 metadata、不换 URI 时，播放位置保持，
 * 但会触发 `onMediaItemTransition(REASON_PLAYLIST_CHANGED)`——因此引擎侧必须
 * 认 [isUpdating]，跳过清 A-B / 片头跳过 / 重载歌词等切歌逻辑。
 *
 * ## 去重
 * 同一文案不重复写；切歌时 [reset] 清掉缓存，下一 tick 再发。
 */
object LockScreenLyricPublisher {

    @Volatile
    private var lastPublished: String? = null

    /**
     * 正在因歌词刷新而 replaceMediaItem。引擎的 `onMediaItemTransition` 见到此标志应直接 return。
     */
    @Volatile
    var isUpdating: Boolean = false
        private set

    /** 切到另一首时调用，避免旧歌词粘在新曲上、也避免误判「文案未变」跳过首帧。 */
    fun reset() {
        lastPublished = null
    }

    /**
     * 按当前播放位置刷新 SYNOPSIS。应在进度 ticker 里调用（与悬浮歌词同频即可）。
     * 无歌词时清空 description，通知栏回落默认艺人行。
     */
    fun publish(player: Player, state: PlayerUiState, positionMs: Long) {
        val display = LockScreenLyrics.current(
            lyrics = state.lyrics,
            positionMs = positionMs,
            offsetMs = state.lyricOffsetMs,
            title = state.current?.title.orEmpty(),
            artist = state.current?.artist.orEmpty(),
        )
        // 有歌词：写入当前行；无歌词：用空串表示「清掉 description」
        val want = if (display.fromLyrics) display.text else ""
        if (want == lastPublished) return

        val index = player.currentMediaItemIndex
        if (index < 0) return
        val item = player.getMediaItemAt(index)
        val old = item.mediaMetadata
        val oldDesc = old.description?.toString().orEmpty()
        if (oldDesc == want) {
            lastPublished = want
            return
        }
        val newMeta = old.buildUpon()
            .setDescription(want.ifEmpty { null })
            .build()
        isUpdating = true
        try {
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(newMeta).build())
            lastPublished = want
        } finally {
            isUpdating = false
        }
    }
}
