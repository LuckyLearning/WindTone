package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变调换算（[Pitch]）。
 *
 * 这里守的是两件事：
 *  ① **半音 → 倍率**的换算必须准（±12 半音 = 一个八度 = 0.5x / 2.0x）；
 *  ② 越界要**夹取**而不是崩 —— 值可能来自界面滑块边界或旧版 Prefs。
 *
 * 为什么值得单独测：算错的表现是「听着音高不对」，而 `setPlaybackParameters`
 * 不会为错误的倍率报任何错，本机也没有真机可以听。
 */
class PitchTest {

    private val eps = 0.0005f

    @Test
    fun `原调倍率为 1`() {
        assertEquals(1f, Pitch.factorOf(0), eps)
    }

    @Test
    fun `升 12 半音是八度，即 2 倍`() {
        assertEquals(2f, Pitch.factorOf(12), eps)
    }

    @Test
    fun `降 12 半音是半个八度，即 0_5 倍`() {
        assertEquals(0.5f, Pitch.factorOf(-12), eps)
    }

    @Test
    fun `降 5 半音是纯四度，倍率约 0_749`() {
        // 2^(-5/12) = 0.7492 —— 顺手钉住「不是线性插值」这件事
        assertEquals(0.7492f, Pitch.factorOf(-5), eps * 5)
    }

    @Test
    fun `升半音与降半音互为倒数`() {
        assertEquals(1f, Pitch.factorOf(3) * Pitch.factorOf(-3), eps)
    }

    @Test
    fun `越界一律夹取到端点而不是抛异常`() {
        assertEquals(Pitch.factorOf(Pitch.MAX_SEMITONES), Pitch.factorOf(99), eps)
        assertEquals(Pitch.factorOf(Pitch.MIN_SEMITONES), Pitch.factorOf(-99), eps)
    }

    @Test
    fun `倍率随半音单调递增`() {
        for (n in Pitch.MIN_SEMITONES until Pitch.MAX_SEMITONES) {
            assertTrue("第 $n 档应小于第 ${n + 1} 档", Pitch.factorOf(n) < Pitch.factorOf(n + 1))
        }
    }

    @Test
    fun `文案区分原调 升 降`() {
        assertEquals("原调", Pitch.labelOf(0))
        assertEquals("+2 半音", Pitch.labelOf(2))
        assertEquals("-3 半音", Pitch.labelOf(-3))
    }

    @Test
    fun `默认判定只看是否为 0`() {
        assertTrue(Pitch.isDefault(0))
        assertFalse(Pitch.isDefault(1))
        assertFalse(Pitch.isDefault(-1))
    }
}
