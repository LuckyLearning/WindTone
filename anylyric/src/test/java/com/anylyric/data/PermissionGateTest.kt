package com.anylyric.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从设置页回来要不要弹路径向导」的边界。
 *
 * 真机上两种误判都很贵：
 *  · 太早判定 —— 设置页还没起来就弹窗，用户正在翻，凭空多一个对话框；
 *  · 已授权还弹 —— 用户刚开好权限回来，看到「去开权限」会觉得应用根本不认。
 */
class PermissionGateTest {

    @Test
    fun tooEarlyDoesNotGuide() {
        // 500ms：有些 ROM 起设置页前会先让我们 resume 一次，此刻判等于白弹。
        assertFalse(PermissionGate.shouldGuide(500L, granted = false))
    }

    @Test
    fun grantedNeverGuides() {
        assertFalse(PermissionGate.shouldGuide(PermissionGate.RETURN_GRACE_MS, granted = true))
        assertFalse(PermissionGate.shouldGuide(60_000L, granted = true))
    }

    @Test
    fun returnedWithoutGrantGuides() {
        assertTrue(PermissionGate.shouldGuide(1500L, granted = false))
        assertTrue(PermissionGate.shouldGuide(60_000L, granted = false))
    }

    /** 宽限期是「够久就算往返过」，等于边界值就该判。 */
    @Test
    fun graceBoundaryIsInclusive() {
        assertTrue(
            PermissionGate.shouldGuide(PermissionGate.RETURN_GRACE_MS, granted = false)
        )
        assertFalse(
            PermissionGate.shouldGuide(PermissionGate.RETURN_GRACE_MS - 1, granted = false)
        )
    }
}
