package com.luckylearning.windtone.playback

/**
 * H2：片头 / 片尾**固定秒**跳过（非「跳过静音」）。
 *
 * - 片头：开播后 seek 到 N 秒（N=0 关闭）。
 * - 片尾：进度距结束 ≤ M 秒时切下一首（M=0 关闭；切歌策略跟 Media3 现有
 *   `seekToNextMediaItem` / 循环·停播行为，本模块只给出「该不该切」）。
 * - 与 [PlayerHandle.setSkipSilence] 无关：那是 ExoPlayer 静音检测；这里是固定秒。
 */
object IntroOutroSkip {
    const val MAX_SECONDS = 120
    const val DEFAULT_INTRO_SEC = 0
    const val DEFAULT_OUTRO_SEC = 0

    fun clampSeconds(sec: Int): Int = sec.coerceIn(0, MAX_SECONDS)

    /**
     * 片头 seek 目标。返回 null = 不 seek（关闭、或整曲太短不够留下可播内容）。
     *
     * 规则：intro 开启时至少留 1s 可播；若 duration 未知（≤0）仍返回 introMs，
     * 由播放器自行夹取。
     */
    fun introSeekMs(introSec: Int, durationMs: Long): Long? {
        val intro = clampSeconds(introSec)
        if (intro <= 0) return null
        val introMs = intro * 1_000L
        if (durationMs <= 0L) return introMs
        // 太短：seek 过去就没得听了
        if (durationMs <= introMs + 1_000L) return null
        return introMs
    }

    /**
     * 当前进度是否已进入片尾窗口、应切下一首。
     * 整曲短于 outro 时不触发（否则一开播就切）。
     */
    fun shouldSkipOutro(positionMs: Long, durationMs: Long, outroSec: Int): Boolean {
        val outro = clampSeconds(outroSec)
        if (outro <= 0) return false
        if (durationMs <= 0L) return false
        val outroMs = outro * 1_000L
        if (durationMs <= outroMs) return false
        val pos = positionMs.coerceAtLeast(0L)
        return pos >= durationMs - outroMs
    }

    /**
     * 仅当进度仍在片头窗口内才自动 seek（用户已拖过片头则不拉回）。
     * [alreadyApplied] 为本曲是否已做过片头跳过。
     */
    fun shouldApplyIntro(
        alreadyApplied: Boolean,
        positionMs: Long,
        introSec: Int,
        durationMs: Long,
    ): Long? {
        if (alreadyApplied) return null
        val target = introSeekMs(introSec, durationMs) ?: return null
        val pos = positionMs.coerceAtLeast(0L)
        // 容差 400ms：READY 瞬间进度可能略 >0
        if (pos > target + 400L) return null
        return target
    }
}
