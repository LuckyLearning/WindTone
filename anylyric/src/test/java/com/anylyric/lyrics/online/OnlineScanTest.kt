package com.anylyric.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「获取更多版本」的推进规则（0.2.27 追加）。
 *
 * 用户要的是「上限不限制，点一次每个源查 2 个，再点再查」。这里钉住三件事：
 * 每轮每源最多 2 版、想要的总数不被超过、游标过了一页就翻页。
 */
class OnlineScanTest {

    @Test
    fun `quota is two per source while there is room`() {
        assertEquals(2, OnlineScan.quota(want = 3, collected = 0))
        assertEquals(2, OnlineScan.quota(want = 200, collected = 7))
    }

    @Test
    fun `quota shrinks when only one slot is left`() {
        assertEquals(1, OnlineScan.quota(want = 3, collected = 2))
    }

    @Test
    fun `quota is zero when the target is reached`() {
        assertEquals(0, OnlineScan.quota(want = 3, collected = 3))
        assertEquals(0, OnlineScan.quota(want = 3, collected = 9))
    }

    @Test
    fun `page advances every page size`() {
        assertEquals(1, OnlineScan.pageFor(0))
        assertEquals(1, OnlineScan.pageFor(19))
        assertEquals(2, OnlineScan.pageFor(20))
        assertEquals(2, OnlineScan.pageFor(39))
        assertEquals(3, OnlineScan.pageFor(40))
    }

    @Test
    fun `page tolerates negative cursor`() {
        // 游标不可能为负，但真为负时也不能算成第 0 页（接口会 400）。
        assertEquals(1, OnlineScan.pageFor(-5))
    }

    @Test
    fun `in-page skip is the remainder only`() {
        // 关键：页码 / offset 已经整页跳过，页内只能跳余数。
        // 否则第 2 页会被整页丢光 —— 版本过 20 之后「再点更多永远没有新增」。
        assertEquals(0, OnlineScan.inPageSkip(0))
        assertEquals(3, OnlineScan.inPageSkip(3))
        assertEquals(0, OnlineScan.inPageSkip(20))
        assertEquals(2, OnlineScan.inPageSkip(42))
    }

    @Test
    fun `safety cap is far beyond what a user taps`() {
        // 「上限不限制」指产品语义；这个是防跑飞的档板，必须远大于
        // 「5 个源 × 每轮 2 版 × 十来次点击」这个上限。
        assertTrue(OnlineScan.SAFETY_MAX >= 100)
    }
}
