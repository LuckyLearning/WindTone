package com.anylyric.overlay

/**
 * 单行滚动的纯计算：要不要滚、滚多快、某一刻滚到哪。
 *
 * 抽出来的唯一理由是**可以被 JVM 单测覆盖** —— 跑马灯一旦出现「滚一半跳回去」或
 * 「速度忽快忽慢」，在车机上肉眼很难判断是节奏错了还是掉帧，只能靠这里的边界值兜住。
 *
 * 坐标约定：返回值是「文字相对内容区左边缘向左偏移了多少像素」，恒 ≥ 0。
 */
object Marquee {

    /** 默认滚动速度（dp/秒）。车速下读一行 12 个字的歌词大约 3 秒走完，不快不慢。 */
    const val SPEED_DP_PER_SEC = 44f

    /** 一轮结束后到下一轮开始之间的空档，占内容宽的比例。 */
    private const val GAP_RATIO = 0.14f

    /** 空档下限（px）：窄窗时比例算出来太小，首尾两句会粘在一起。 */
    private const val GAP_MIN_PX = 28f

    /** 浮点误差容忍：差不到 1px 就当放得下，避免为了 0.4px 一直滚。 */
    private const val TOLERANCE_PX = 1f

    fun needsScroll(textWidth: Float, contentWidth: Float): Boolean =
        contentWidth > 0f && textWidth > contentWidth + TOLERANCE_PX

    fun gap(contentWidth: Float): Float =
        (contentWidth * GAP_RATIO).coerceAtLeast(GAP_MIN_PX)

    /** 一轮走过的距离：整句宽度 + 尾部空档。 */
    fun distance(textWidth: Float, contentWidth: Float): Float =
        textWidth.coerceAtLeast(0f) + gap(contentWidth)

    /**
     * 一轮耗时（毫秒）。
     *
     * 下限 1200ms：极短句本来就不该滚；但万一因为字号极大导致内容区极窄，
     * 也不该出现「每秒闪三遍」的观感。
     */
    fun durationMs(textWidth: Float, contentWidth: Float, pxPerSec: Float): Long {
        if (pxPerSec <= 0f) return 1L
        val ms = (distance(textWidth, contentWidth) / pxPerSec * 1000f).toLong()
        return ms.coerceAtLeast(1200L)
    }

    /** 某一刻的偏移量；[elapsedMs] 可以是任意大，内部按轮取余。 */
    fun offsetAt(
        elapsedMs: Long,
        textWidth: Float,
        contentWidth: Float,
        pxPerSec: Float
    ): Float {
        val duration = durationMs(textWidth, contentWidth, pxPerSec)
        val phase = (elapsedMs % duration).toFloat() / duration
        return distance(textWidth, contentWidth) * phase
    }
}
