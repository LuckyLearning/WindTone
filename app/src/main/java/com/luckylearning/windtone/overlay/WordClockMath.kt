package com.luckylearning.windtone.overlay

/**
 * 逐字进度的本地外推（纯函数）。
 *
 * 播放域每 500ms 推一次「已读到的进度 + 本地时间戳」，服务侧 60ms 定时器在**本地**
 * 用单调时钟外推，避免每帧跨进程读 `MediaController.currentPosition`
 * （那正是 v1.2.40 把 tick 从 200ms 降到 500ms 要省掉的开销）。
 */
internal object WordClockMath {

    /**
     * @param clock 播放域推来的最近一次时钟
     * @param nowRealtime 当前 `SystemClock.elapsedRealtime()`
     * @return 外推后的播放进度（毫秒）；不可用返回 -1
     */
    fun extrapolate(clock: LyricOverlayBus.WordClock, nowRealtime: Long): Long {
        if (clock.progressMs < 0L) return -1L
        // 暂停时冻结在推来的那一刻，不随时钟漂移（否则暂停后字还在往前亮）
        if (!clock.playing) return clock.progressMs
        val elapsed = nowRealtime - clock.atRealtimeMs
        // 时钟回拨（重启 / 时间戳异常）或超过 2 秒没更新（播放域停了）→ 不外推，
        // 宁可用旧值也不要把字推到句尾。
        if (elapsed < 0L || elapsed > 2_000L) return clock.progressMs
        return clock.progressMs + elapsed
    }
}
