package com.anylyric.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「换一版歌词」的索引规则（0.2.26）。
 *
 * 三种结局都必须明确，错一种就是真机上「点了没反应」或「歌词闪一下又回去」。
 */
class VariantCycleTest {

    private val max = 3

    @Test
    fun `advances while there is a cached next version`() {
        assertEquals(1, VariantCycle.nextIndex(0, 2, max))
        assertEquals(2, VariantCycle.nextIndex(1, 3, max))
    }

    @Test
    fun `asks for more when under the cap`() {
        // 只有 1 版、上限 3：本地没得切，但还能再找 —— 返回 -1 让调用方联网。
        assertEquals(-1, VariantCycle.nextIndex(0, 1, max))
        // 有 2 版、上限 3：切到第 2 版之后还能再找第 3 版。
        assertEquals(-1, VariantCycle.nextIndex(1, 2, max))
    }

    @Test
    fun `wraps back to the first version at the cap`() {
        // 已到上限就不能再联网了，循环回第 0 版：用户再点一下回到最初那版是可预期的。
        assertEquals(0, VariantCycle.nextIndex(2, 3, max))
    }

    @Test
    fun `no lyrics means nothing to cycle`() {
        assertEquals(-1, VariantCycle.nextIndex(0, 0, max))
    }

    @Test
    fun `negative index resets to the first`() {
        assertEquals(0, VariantCycle.nextIndex(-1, 2, max))
    }
}
