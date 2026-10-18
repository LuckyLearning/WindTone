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
}
