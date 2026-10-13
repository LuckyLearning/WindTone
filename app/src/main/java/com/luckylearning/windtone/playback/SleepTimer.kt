package com.luckylearning.windtone.playback

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

    val running: Boolean get() = _remainingMs.value > 0

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

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0L
    }
}
