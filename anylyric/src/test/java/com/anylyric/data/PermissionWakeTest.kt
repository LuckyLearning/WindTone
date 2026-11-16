package com.anylyric.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionWakeTest {

    private val boot = "android.intent.action.BOOT_COMPLETED"
    private val quick = "android.intent.action.QUICKBOOT_POWERON"
    private val present = PermissionWake.ACTION_USER_PRESENT

    @Test
    fun `boot broadcasts wake when the switch is on`() {
        assertTrue(PermissionWake.shouldWake(boot, true, 0L))
        assertTrue(PermissionWake.shouldWake(quick, true, 0L))
    }

    @Test
    fun `switch off never wakes`() {
        // 关了就必须一次都不弹，包括开机广播本身 —— 否则「关掉」是假的。
        assertFalse(PermissionWake.shouldWake(boot, false, 0L))
        assertFalse(PermissionWake.shouldWake(present, false, 0L))
    }

    @Test
    fun `user present only counts shortly after boot`() {
        // USER_PRESENT 每次解锁都发。只在开机窗口内响应，否则会变成「每解锁一次弹一次」。
        assertTrue(PermissionWake.shouldWake(present, true, 60_000L))
        assertTrue(
            PermissionWake.shouldWake(present, true, PermissionWake.USER_PRESENT_WINDOW_MS)
        )
        assertFalse(
            PermissionWake.shouldWake(present, true, PermissionWake.USER_PRESENT_WINDOW_MS + 1)
        )
        // 开机很久之后的解锁：不该弹。
        assertFalse(PermissionWake.shouldWake(present, true, 3 * 60 * 60 * 1000L))
    }

    @Test
    fun `boot completed is not limited by the window`() {
        // BOOT_COMPLETED 本身只发一次，系统延迟送达（比如开机后两小时）也该认。
        assertTrue(PermissionWake.shouldWake(boot, true, 2 * 60 * 60 * 1000L))
    }

    @Test
    fun `unrelated broadcasts are ignored`() {
        assertFalse(PermissionWake.shouldWake("android.intent.action.SCREEN_ON", true, 0L))
        assertFalse(PermissionWake.shouldWake(null, true, 0L))
    }
}
