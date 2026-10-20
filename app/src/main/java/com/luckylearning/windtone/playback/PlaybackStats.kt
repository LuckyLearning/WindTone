package com.luckylearning.windtone.playback

import android.os.SystemClock
import com.luckylearning.windtone.data.HOUR_MS
import com.luckylearning.windtone.data.SongIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放统计：播放次数、累计收听时长、切歌去重。
 *
 * 从 `PlaybackEngine` 抽出的原因：这三件事只依赖 [PlaybackStore] / [SongIndex] /
 * 一个协程作用域，**完全不认识 MediaController 与界面**，却和队列、连接、歌词、
 * 悬浮窗混在同一个 1800 行的类里。抽出来后：
 *  - 去重规则（15 秒内同一首只计一次）可以独立单测；
 *  - 引擎只保留「什么时候调用」，不再关心「怎么落库、怎么累加」。
 *
 * 线程约定：对外方法从主线程调用；落库副作用投递到 [scope]，IO 再切 IO 线程。
 */
class PlaybackStats(
    private val store: PlaybackStore,
    private val index: SongIndex,
    private val scope: CoroutineScope,
) {

    /** 当前正在累计收听的曲目（stableKey），null = 没有在累计 */
    private var listenForKey: String? = null
    private var listenAccumMs = 0L
    private var lastTickAt = 0L

    // 播放计数去重
    private var lastCountedMediaId: String? = null
    private var lastCountedAt = 0L

    /**
     * 是否把这次曲目切换计为一次播放。
     *
     * `replaceMediaItem`（回填封面）同样会触发 onMediaItemTransition，
     * 若不加判别，每首歌会被多计若干次播放。同一首歌在 15 秒内的重复切换视为非用户行为。
     */
    fun shouldCountPlay(mediaId: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (mediaId == lastCountedMediaId && now - lastCountedAt < 15_000L) return false
        lastCountedMediaId = mediaId
        lastCountedAt = now
        return true
    }

    /**
     * 记录一次播放。
     * 必须先 ensure（INSERT OR IGNORE）再 bump——历史实现只有 UPDATE，
     * 没收藏过的歌永远写不进统计表（现由 [PlaybackStore.recordPlay] 保证）。
     */
    fun recordPlay(mediaId: String) {
        val songId = index.idOf(mediaId) ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { store.recordPlay(songId, now) }
            }
            index.bump(songId) { it.copy(playCount = it.playCount + 1, lastPlayedAt = now) }
        }
    }

    /** 把累计的收听时长写回数据库（切歌 / 暂停 / 落盘时调用） */
    fun flushListen() {
        val key = listenForKey ?: return
        val ms = listenAccumMs
        listenAccumMs = 0L
        if (ms < 1000L) return
        val songId = index.idOf(key) ?: return
        // 收听报告的小时桶按**本次结算时刻**归属（一次累计最多 30 秒，
        // 恰好跨整点的情况误差可以忽略）
        val hour = System.currentTimeMillis() / HOUR_MS
        scope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { store.addListenTime(songId, ms, hour, now) }
            }
            index.bump(songId) { it.copy(playedMs = it.playedMs + ms) }
        }
    }

    /** 丢弃尚未落库的累计收听时长（「清空统计」前必须调用，否则它会被写回去） */
    fun resetListenAccumulator() {
        listenAccumMs = 0L
    }

    /**
     * 累计收听时长：仅播放中累加，切歌 / 每 30 秒落库一次。
     *
     * `delta` 上限 2 秒是为了兜住「屏幕熄灭后 ticker 被系统挂起 10 秒」的情况——
     * 否则一次恢复会把整段挂起时间都算成收听。
     */
    fun accumulateListening(mediaId: String?, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastTickAt > 0L) (now - lastTickAt).coerceIn(0L, 2_000L) else 0L
        lastTickAt = now

        if (mediaId == null) {
            flushListen()
            listenForKey = null
            return
        }
        if (listenForKey != null && listenForKey != mediaId) flushListen()
        if (listenForKey != mediaId) {
            listenForKey = mediaId
            listenAccumMs = 0L
        }
        if (playing) {
            listenAccumMs += delta
            if (listenAccumMs >= 30_000L) flushListen()
        }
    }
}
