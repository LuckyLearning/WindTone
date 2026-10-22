package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「无底板」时的可读性参数（v1.1.25）。
 *
 * 锁的是一条来自真机的抱怨：无底板时浅色歌词压在泊车影像 / 浅色地图上，
 * 上下句被压到 42% 之后整片糊掉。这里断言「无底板一定比有底板更不透明」，
 * 避免以后有人为了让画面更「通透」又把它调回去。
 */
class OverlayLegibilityTest {

    @Test
    fun `无底板时上下句比有底板更不透明`() {
        val noBackdrop = OverlayLegibility.contextAlpha(noBackdrop = true)
        val withBackdrop = OverlayLegibility.contextAlpha(noBackdrop = false)
        assertTrue(
            "无底板($noBackdrop) 必须比有底板($withBackdrop) 更不透明",
            noBackdrop > withBackdrop,
        )
        // 但也不能到 100% 不透明：当前行要明显比上下句亮，层级不能塌
        assertTrue("上下句不该和当前行一样亮：$noBackdrop", noBackdrop < 0xFF)
    }

    @Test
    fun `无底板时译文比上下句略亮`() {
        val context = OverlayLegibility.contextAlpha(noBackdrop = true)
        val translation = OverlayLegibility.translationAlpha(noBackdrop = true)
        assertTrue("译文($translation) 应比上下句($context) 亮", translation > context)
        assertTrue("译文不该全不透明：$translation", translation < 0xFF)
    }

    @Test
    fun `有底板时保持与播放页一致的不透明度`() {
        assertEquals(0x6B, OverlayLegibility.contextAlpha(noBackdrop = false))
        assertEquals(0xA0, OverlayLegibility.translationAlpha(noBackdrop = false))
    }

    // ---- v1.1.52：压暗百分比开放为设置项（20–80%，默认 42 与播放页对齐）----

    @Test
    fun `默认压暗档与旧版逐位一致`() {
        val dim = OverlayLegibility.DEFAULT_DIM_PERCENT
        assertEquals(0x6B, OverlayLegibility.contextAlpha(noBackdrop = false, dimPercent = dim))
        assertEquals(0xA0, OverlayLegibility.translationAlpha(noBackdrop = false, dimPercent = dim))
        assertEquals(0x9B, OverlayLegibility.contextAlpha(noBackdrop = true, dimPercent = dim))
        assertEquals(0xBD, OverlayLegibility.translationAlpha(noBackdrop = true, dimPercent = dim))
    }

    @Test
    fun `压暗百分比即上下句保留的亮度`() {
        // 42 = 保留 42% 亮度（与播放页 0.42 对齐），所以值越小上下句越暗
        val darker = OverlayLegibility.contextAlpha(noBackdrop = false, dimPercent = 20)
        val brighter = OverlayLegibility.contextAlpha(noBackdrop = false, dimPercent = 80)
        assertTrue("两成($darker) 必须比八成($brighter) 更暗", darker < brighter)
    }

    @Test
    fun `无底板时压暗再深也有保底`() {
        // 用户把压暗拉满也不至于在浅色画面上把上下句压没 —— 这是 v1.1.25 踩过的坑
        val alpha = OverlayLegibility.contextAlpha(noBackdrop = true, dimPercent = 80)
        assertTrue("无底板上下句不能低于保底 0x9B：$alpha", alpha >= OverlayLegibility.CONTEXT_ALPHA_NO_BACKDROP)
    }
}
