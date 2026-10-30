package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 睡眠定时器。
 * UI 通过 [start]/[startEndOfSong]/[cancel] 控制；
 * 播放服务在 onCreate 时把「到点暂停」的动作挂到 [fireAction]。
 * 倒计时放在常驻单例协程里，App 切后台也能触发。
 */
object SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** 剩余毫秒；0 表示未开启 */
    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    /** 到点动作（由 PlaybackService 注册：暂停播放） */
    @Volatile
    var fireAction: (() -> Unit)? = null

    fun start(durationMs: Long) {
        job?.cancel()
        _remainingMs.value = durationMs.coerceAtLeast(0L)
        job = scope.launch {
            var remain = durationMs
            var last = System.currentTimeMillis()
            while (remain > 0) {
                delay(500L)
                val now = System.currentTimeMillis()
                remain -= (now - last)
                last = now
                _remainingMs.value = remain.coerceAtLeast(0L)
            }
            _remainingMs.value = 0L
            fireAction?.invoke()
        }
    }

    /** 「播完当前曲」模式 */
    fun startEndOfSong(positionMs: Long, durationMs: Long) {
        val remain = (durationMs - positionMs).coerceAtLeast(0L)
        start(remain)
    }

    /**
     * 「播完当前专辑」要等多久：当前曲剩余 + 队列里**后续同专辑**曲目的时长。
     *
     * 纯函数（只依赖 `Song` 数据类），单独拎出来是为了能直接进 JVM 单测 ——
     * 时长算错的表现是「到点没停」或「提前停」，本机没有真机，靠跑 App 发现不了。
     *
     * 走到第一首不同专辑的曲目就停：专辑内曲目在队列里天然连排（从专辑详情页
     * 「播放全部」起播时就是连续的），遇到别的专辑说明这张已经放完。扫全队列反而会把
     * 「后面偶然又出现同一张专辑」算进来，把定时拉长。
     *
     * 没有专辑信息（散装歌 / 曲库缺标签）时退化为「播完当前曲」。
     */
    fun albumRemainingMs(playlist: List<Song>, index: Int, positionMs: Long, durationMs: Long): Long {
        val current = (durationMs - positionMs).coerceAtLeast(0L)
        val album = playlist.getOrNull(index)?.album
        if (album.isNullOrBlank()) return current
        var total = current
        var i = index + 1
        while (i < playlist.size && playlist[i].album == album) {
            total += playlist[i].durationMs.coerceAtLeast(0L)
            i++
        }
        return total
    }

    /**
     * 「播完当前队列」要等多久：当前曲剩余 + 队列里**当前索引之后全部**曲目的时长。
     *
     * 纯函数，便于 JVM 单测。与 [albumRemainingMs] 的差别：不按专辑截断，一直累加到队尾。
     * 语义是「这一轮队列还剩多久」——列表循环时也只算到队尾一次，不会无限拖长。
     *
     * 索引越界 / 空队列：退化为「当前曲剩余」（与专辑档兜底一致）。
     */
    fun queueRemainingMs(playlist: List<Song>, index: Int, positionMs: Long, durationMs: Long): Long {
        val current = (durationMs - positionMs).coerceAtLeast(0L)
        if (index < 0 || index >= playlist.size) return current
        var total = current
        var i = index + 1
        while (i < playlist.size) {
            total += playlist[i].durationMs.coerceAtLeast(0L)
            i++
        }
        return total
    }


    /**
     * 自定义分钟合法区间：1～720（12 小时）。
     * UI 自定义输入与其它入口共用，避免 0 / 负数立刻触发、超大值误设成天级。
     */
    fun clampCustomMinutes(raw: Int): Int = raw.coerceIn(1, 720)

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0L
    }
}
