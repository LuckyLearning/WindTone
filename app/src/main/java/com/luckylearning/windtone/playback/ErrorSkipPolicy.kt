package com.luckylearning.windtone.playback

/**
 * 播放失败之后「跳下一首」还是「停下」的判断（v1.1.45）。
 *
 * 抽成纯函数是被真机逼出来的：原来的条件写的是 `consecutiveErrors < 队列长度`，看起来
 * 很合理 —— 挨个试完就停。但 `onMediaItemTransition` 会把计数清零，而**失败跳曲本身
 * 也会触发一次过渡**，于是计数永远停在 1，队列里每一首都试过、循环往复，用户看到的是
 * 「一直在自动切歌、一首也放不了」，真正的原因被几十条提示冲掉。
 *
 * 现在的规则：
 *  - 队列里还有下一首、且连续失败**没到** [MAX_CONSECUTIVE] 首 → 跳（单曲文件坏了不该卡住整张专辑）；
 *  - 到了上限 → 停下并说清原因（整批不可用时继续跳只是噪音）。
 *
 * A4：停止话术带「下一步」，与 [PlaybackErrorMessages] 的「原因 + 可操作路径」一致。
 */
object ErrorSkipPolicy {

    /**
     * 连续失败多少首就停下。
     *
     * 3 首足够区分「某首歌的文件坏了」和「整批地址都不可用」，也不至于让用户以为卡死。
     */
    const val MAX_CONSECUTIVE = 3

    fun shouldSkip(queueSize: Int, consecutiveErrors: Int): Boolean =
        queueSize > 1 && consecutiveErrors < MAX_CONSECUTIVE

    /**
     * 到达上限（或单曲失败）时给用户的一句话。
     *
     * [reason] 通常已是 [PlaybackErrorMessages.describe] 的结果（含「下一步」）；
     * 这里再补一层「已暂停 / 怎么处理队列」的上下文，避免只剩技术原因。
     */
    fun stopMessage(consecutiveErrors: Int, reason: String): String =
        if (consecutiveErrors >= MAX_CONSECUTIVE) {
            "连续 $consecutiveErrors 首播放失败，已暂停（$reason）"
        } else {
            "播放失败（$reason）"
        }
}
