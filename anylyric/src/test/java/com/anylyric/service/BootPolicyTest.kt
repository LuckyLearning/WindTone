package com.anylyric.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BootPolicy.shouldRebind] 的边界。
 *
 * 这条链路的失败表现全是「开机后什么都没发生」，无法在开发机上复现开机，
 * 全靠这里把组合钉住。
 */
class BootPolicyTest {

    @Test
    fun `standard boot broadcast triggers rebind when enabled`() {
        assertTrue(BootPolicy.shouldRebind("android.intent.action.BOOT_COMPLETED", enabled = true))
    }

    @Test
    fun `quickboot broadcast triggers rebind when enabled`() {
        assertTrue(BootPolicy.shouldRebind(BootPolicy.ACTION_QUICKBOOT_POWERON, enabled = true))
    }

    @Test
    fun `switch off means do nothing`() {
        assertFalse(BootPolicy.shouldRebind("android.intent.action.BOOT_COMPLETED", enabled = false))
        assertFalse(BootPolicy.shouldRebind(BootPolicy.ACTION_QUICKBOOT_POWERON, enabled = false))
    }

    @Test
    fun `other broadcasts and null action are ignored`() {
        assertFalse(BootPolicy.shouldRebind(null, enabled = true))
        assertFalse(BootPolicy.shouldRebind("android.intent.action.MY_PACKAGE_REPLACED", enabled = true))
        assertFalse(BootPolicy.shouldRebind("", enabled = true))
    }
}
