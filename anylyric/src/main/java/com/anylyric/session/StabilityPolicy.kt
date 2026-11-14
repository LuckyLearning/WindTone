package com.anylyric.session

/**
 * 「取词稳不稳」里两条可以写成纯函数的判断（0.2.23，参照 Cardash 的实现）。
 *
 * 为什么要抄这一手：Cardash 的 `MusicLyricsListenerService` 不只是等回调 ——
 * 它在 `onListenerConnected` 之后挂了一个 **900ms 自驱循环**，每轮重读一次已绑定会话的
 * metadata / playbackState。这带来一个 AnyLyric 之前没有的性质：
 * **即使播放器一次回调都不发，歌词也会在 1 秒内跟上**。
 *
 * AnyLyric 原本完全靠 `MediaController.Callback` 推：推送稀疏的播放器（车机上很常见）
 * 表现为「换歌不换词」「暂停了歌词还在滚」，而这类问题在日志里看不出任何异常 ——
 * 因为没有事件，就没有日志。
 *
 * 所以这里补一条**只在回调静默时**才触发的补查，而不是无脑轮询：
 *  · 播放中每 [POLL_PLAYING_MS] 看一眼；静默超过 [QUIET_MS] 才真的去读一次；
 *  · 非播放态放宽到 [POLL_IDLE_MS]：暂停时进度不动，读得太勤只是白耗电。
 *
 * 第二条判断是「同曲空窗保持」：Cardash 在解析结果为空、且仍是同一首歌时，
 * 沿用**上一轮**的行表（`listD.isEmpty() && 同曲 → 保留 n3K0.f972h`）。播放器在换歌
 * 间隙常把歌词字段清空一两拍，立刻清屏就会闪一下「正在获取歌词…」。
 */
object StabilityPolicy {

    /** 播放中：多久检查一次「回调是否静默」。 */
    const val POLL_PLAYING_MS = 1_000L

    /** 非播放态：暂停/停止时进度不动，检查可以稀疏。 */
    const val POLL_IDLE_MS = 5_000L

    /**
     * 回调静默多久算「这个播放器不推了」。
     *
     * 取 1.5 个轮询周期：正常播放器一秒内至少有一个 state 回调，卡在 1.5s 说明
     * 要么它真不推，要么推送链路断了 —— 两种都该自己读一次。
     */
    const val QUIET_MS = 1_500L

    /** 下一次检查的间隔。 */
    fun pollDelayMs(playing: Boolean): Long = if (playing) POLL_PLAYING_MS else POLL_IDLE_MS

    /**
     * 现在该不该主动补读一次。
     *
     * 非播放态把门槛也放宽一倍：暂停时进度不动，晚两秒发现「又播起来了」没人能感知到，
     * 而读一次就是两次跨进程调用。
     *
     * @param nowMs           当前 `elapsedRealtime`
     * @param lastCallbackAtMs 最近一次收到本会话回调的时刻（0 = 还没收到过）
     */
    fun shouldRequery(nowMs: Long, lastCallbackAtMs: Long, playing: Boolean): Boolean {
        if (lastCallbackAtMs <= 0L) return true
        val quiet = nowMs - lastCallbackAtMs
        // 时钟回绕 / 时间戳来自别的基准：当成静默，宁可多读一次
        if (quiet < 0) return true
        return quiet >= if (playing) QUIET_MS else QUIET_MS * 2
    }

    /**
     * 同一首歌内、这一轮解析不出行时，是否沿用上一轮的行表。
     *
     * @param trackChanged 本轮是否判定为换歌（会话内 identity 或展示标题变了）
     * @param hadLines     上一轮屏上是否有行
     */
    fun keepLines(trackChanged: Boolean, hadLines: Boolean): Boolean = !trackChanged && hadLines
}
