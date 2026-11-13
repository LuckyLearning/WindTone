package com.anylyric.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「把无障碍服务写回系统设置」里唯一值得单独测的一步。
 *
 * 这一步出事代价最大：`enabled_accessibility_services` 是整个系统的无障碍服务列表，
 * 拼错一个冒号或覆盖掉别人的条目，用户机器上的读屏软件就会挂。所以把纯逻辑抽出来，
 * 四种输入各钉一条。
 */
class AccessibilityWatchdogTest {

    private val svc = "com.anylyric/com.anylyric.accessibility.ForegroundAppService"

    @Test
    fun `empty list becomes just this service`() {
        assertEquals(svc, AccessibilityWatchdog.appendService(null, svc))
        assertEquals(svc, AccessibilityWatchdog.appendService("", svc))
    }

    @Test
    fun `existing services are preserved and this one is appended`() {
        val other = "com.example.screenreader/com.example.SrService"
        assertEquals("$other:$svc", AccessibilityWatchdog.appendService(other, svc))
    }

    @Test
    fun `already present returns null`() {
        // 返回 null = 不用写：已经勾着的时候服务没连是系统侧的事，写回也没用
        assertNull(AccessibilityWatchdog.appendService("$svc:com.x/y", svc))
        assertNull(AccessibilityWatchdog.appendService(svc, svc))
    }

    @Test
    fun `blank entries are dropped`() {
        // 真机上见过 `pkg/svc::other` 这种脏数据，不能把空串写回去
        assertEquals("com.a/b:$svc", AccessibilityWatchdog.appendService("com.a/b::", svc))
    }
}
