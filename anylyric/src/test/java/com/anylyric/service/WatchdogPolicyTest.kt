package com.anylyric.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看门狗判定边界。
 *
 * 这类逻辑的失败很隐蔽：判太松会频繁重绑（打扰系统），判太紧则掉线后永远不恢复。
 * 边界全部钉在这里。
 */
class WatchdogPolicyTest {

    private val stale = WatchdogPolicy.STALE_MS

    /** 用一个远大于过期阈值的「现在」，避免把 lastAlive 算成负数（那会命中「从未心跳」分支）。 */
    private val now = 10 * stale

    @Test
    fun `stale heartbeat triggers rebind`() {
        assertTrue(WatchdogPolicy.shouldRebind(now, now - stale, granted = true, enabled = true))
    }

    @Test
    fun `fresh heartbeat does nothing`() {
        assertFalse(WatchdogPolicy.shouldRebind(now, now - stale + 1, granted = true, enabled = true))
        assertFalse(WatchdogPolicy.shouldRebind(now, now - 1_000L, granted = true, enabled = true))
    }

    @Test
    fun `never heartbeated tries once`() {
        assertTrue(WatchdogPolicy.shouldRebind(now, 0L, granted = true, enabled = true))
    }

    @Test
    fun `switch off means do nothing`() {
        assertFalse(WatchdogPolicy.shouldRebind(now, 0L, granted = true, enabled = false))
    }

    @Test
    fun `not granted means do nothing`() {
        assertFalse(WatchdogPolicy.shouldRebind(now, 0L, granted = false, enabled = true))
    }

    /** 用户改时间 / NTP 校正导致 now < lastAlive：不能判成过期。 */
    @Test
    fun `clock rollback is ignored`() {
        assertFalse(WatchdogPolicy.shouldRebind(1_000L, 2_000L, granted = true, enabled = true))
    }
}
