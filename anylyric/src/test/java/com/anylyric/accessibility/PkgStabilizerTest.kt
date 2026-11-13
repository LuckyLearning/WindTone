package com.anylyric.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 前台包名滞回：短时闪烁不能改「隐藏名单」的判定结果。
 *
 * 时钟注入，不依赖真实 sleep —— 车机上那种「几十毫秒的包名抖动」在单测里靠 sleep
 * 根本复现不了，只能把时间做成参数。
 */
class PkgStabilizerTest {

    private var now = 100_000L
    private val stabilizer = PkgStabilizer(stableMs = 450L, clock = { now })

    @Test
    fun `candidate is not committed immediately`() {
        // 刚到的新包名：还不能拿去判定，否则车机上每次切换都会先藏一下再恢复
        assertNull(stabilizer.onRaw("com.example.video"))
        assertEquals(450L, PkgStabilizer.STABLE_MS)
    }

    @Test
    fun `candidate commits after the stable window`() {
        stabilizer.onRaw("com.example.video")
        now += 450L
        assertEquals("com.example.video", stabilizer.tick())
    }

    @Test
    fun `short flicker between two packages is discarded`() {
        stabilizer.onRaw("com.example.video")
        now += 200L
        // 系统浮层短暂抢了一下前台：不到 450ms 又换回来
        stabilizer.onRaw("com.byd.gesture")
        now += 100L
        stabilizer.onRaw("com.example.video")
        now += 200L
        // 候选一直在变，谁都没能稳定到 450ms
        assertNull(stabilizer.stablePkg())
        now += 300L
        assertEquals("com.example.video", stabilizer.tick())
    }

    @Test
    fun `blank package is treated as unknown`() {
        assertNull(stabilizer.onRaw(""))
        now += 1_000L
        assertNull(stabilizer.tick())
    }

    @Test
    fun `reset clears committed package`() {
        stabilizer.onRaw("com.example.video")
        now += 450L
        stabilizer.tick()
        stabilizer.reset()
        assertNull(stabilizer.stablePkg())
    }
}
