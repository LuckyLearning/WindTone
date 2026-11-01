package com.luckylearning.windtone.overlay

/**
 * 悬浮窗「刷字 / 跳过」闸门（可单测）。
 *
 * [LyricOverlayService.renderLines] 用它比较 [OverlayRenderKey]：键未变则记跳过并
 * 返回 false（调用方不再组装 Spanned / setText）；键变了则记刷字并返回 true。
 *
 * 与 [LyricOverlayBus.publish] / binder paint 指纹的跳过计数是**互斥路径**——
 * 同一次「本可刷字」的机会只会在一层被记一次。
 */
internal class OverlayRenderDeduper {
    private var lastKey: String = ""

    fun clear() {
        lastKey = ""
    }

    /**
     * @return true = 需要刷字（已记 [OverlayMetrics.onRenderApply]）；
     *         false = 字/样式未变（已记 [OverlayMetrics.onRenderSkip]）
     */
    fun begin(key: String): Boolean {
        if (key == lastKey) {
            OverlayMetrics.onRenderSkip()
            return false
        }
        lastKey = key
        OverlayMetrics.onRenderApply()
        return true
    }
}
