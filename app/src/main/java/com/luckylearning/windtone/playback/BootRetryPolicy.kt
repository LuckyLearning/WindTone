package com.luckylearning.windtone.playback

/**
 * 开机后台起播的**重试时机策略**，从 [com.luckylearning.windtone.playback.PlaybackEngine.startPlaybackInBackground]
 * 抽出，纯函数可单测。
 *
 * 开机这一路有三个竞态，任何一个没赢都会静默失败：
 *  ① `MediaController` 还没连上（服务刚起，绑定是异步的）；
 *  ② 曲库还没从数据库读出来（冷启动时 IO 最慢）；
 *  ③ 音频设备还没就绪，`play()` 被 HAL 吞掉（车机最常见）。
 *
 * 一次不成就放弃的话，用户看到的就是「开关打开了，开机没反应」。所以按固定节奏重试，
 * 三次总时长仍在 20 秒内——不会让「开机没播」退化成「开机十几秒后才突然出声」。
 */
object BootRetryPolicy {

    /**
     * 各次尝试前的等待毫秒：第 1 发立即（0s）、随后 4s、10s。
     * 顺序不可打乱：第一次必须 0（能成当场成），后面两次兜底等 IO / 音频设备就绪。
     */
    val DELAYS = longArrayOf(0L, 4_000L, 10_000L)

    /** 总尝试次数（= 重试时机数组长度） */
    fun attempts(): Int = DELAYS.size

    /**
     * 第 [attempt] 次尝试前应等待的毫秒。越界时取最后一次（而不是抛异常），
     * 调用方用 [hasMore] / [isExhausted] 判断收尾即可。
     */
    fun waitMs(attempt: Int): Long = DELAYS.getOrElse(attempt) { DELAYS.last() }

    /** 第 [attempt] 次之后是否还有下一次重试 */
    fun hasMore(attempt: Int): Boolean = attempt + 1 < DELAYS.size

    /**
     * 第 [attempt] 次是否已是最后一次尝试（之后不再重试，用于失败兜底判定）。
     * [attempt] 从 0 计，共 [attempts] 次，所以最后一次下标 = [attempts]-1。
     */
    fun isExhausted(attempt: Int): Boolean = attempt >= DELAYS.size - 1
}
