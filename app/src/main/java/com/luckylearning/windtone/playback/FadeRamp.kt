package com.luckylearning.windtone.playback

/**
 * 切歌淡入淡出的**纯数学**（v1.1.52）。
 *
 * 抽成纯函数的原因与 `EqPresets` 一致：这条曲线要在 JVM 单测里钉死形状
 * （线性、夹取、边界），而真正调用它的 [TrackVolume] 绑着 ExoPlayer，
 * 设备代码测不了。任何「淡出曲线怎么算」的改动都先改这里、先红这里的测试。
 *
 * 因子语义：`1f` = 不衰减（正常音量），`0f` = 静音。**只做线性**——
 * 指数曲线听感更顺滑，但 40ms 步进的线性斜坡在 0.5~6 秒区间内已听不出台阶，
 * 换来的可测试性与可预期性更值钱。
 */
object FadeRamp {

    /**
     * 淡出因子：距曲尾 [remainingMs] 毫秒、单向时长 [fadeMs]。
     *
     * `remaining >= fadeMs` 时为 1（还没进入淡出区间）；`remaining <= 0` 为 0。
     */
    fun fadeOutFactor(remainingMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0) return 1f
        if (remainingMs >= fadeMs) return 1f
        if (remainingMs <= 0) return 0f
        return (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    /** 淡入因子：进入新曲后经过 [elapsedMs] 毫秒。`elapsed >= fadeMs` 后恒为 1。 */
    fun fadeInFactor(elapsedMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0) return 1f
        if (elapsedMs >= fadeMs) return 1f
        if (elapsedMs <= 0) return 0f
        return (elapsedMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    }
}
