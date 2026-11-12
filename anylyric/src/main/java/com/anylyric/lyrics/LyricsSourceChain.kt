package com.anylyric.lyrics

/**
 * 责任链：按 L0 → L1 → L2 顺序尝试，第一个产出非空行集的来源获胜。
 *
 * 无论成败都保留全部尝试记录（`attempts`），这是探测报告的主体内容：
 * 用户能直接看到「L0 没这个键 / L1 标题是稳定的 / L2 通知里没有歌词」。
 */
class LyricsSourceChain(private val sources: List<LyricsSource>) {

    data class Outcome(
        val hit: LyricResult.Hit?,
        val attempts: List<LyricResult>
    )

    fun resolve(ctx: TrackContext): Outcome {
        val attempts = ArrayList<LyricResult>(sources.size)
        for (source in sources) {
            val result = source.tryResolve(ctx)
            attempts += result
            if (result is LyricResult.Hit && result.lines.isNotEmpty()) {
                return Outcome(result, attempts)
            }
        }
        return Outcome(null, attempts)
    }
}
