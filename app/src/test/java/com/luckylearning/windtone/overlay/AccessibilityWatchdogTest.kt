package com.luckylearning.windtone.overlay

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * 无障碍「重启后掉线」的提醒决策（v1.1.85）与写回合并 / tryAutoRestore（v1.2.36+）。
 *
 * 为什么单独测：这条链路一旦误判，用户每次开机都会收到一条「辅助功能被关闭了」的
 * 通知 —— 而他从没开过这个功能，只会当成骚扰。所以「从没开过就不提醒」这条
 * 必须钉死；同时「无 WRITE_SECURE_SETTINGS 绝不假装写回成功」也要钉死，
 * 否则普通车机会以为已经恢复、却不弹通知。
 */
@RunWith(RobolectricTestRunner::class)
class AccessibilityWatchdogTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun ours(): String = AccessibilityWatchdog.serviceComponent(context)

    private fun readEnabledServices(): String? =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )

    private fun writeEnabledServices(value: String?) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            value,
        )
    }

    @Test
    fun `从没开过就不提醒`() {
        assertFalse(AccessibilityWatchdog.shouldPromptRecovery(seenOn = false, currentlyEnabled = false))
    }

    @Test
    fun `曾经开过而现在关了才提醒`() {
        assertTrue(AccessibilityWatchdog.shouldPromptRecovery(seenOn = true, currentlyEnabled = false))
    }

    @Test
    fun `当前开着不提醒`() {
        assertFalse(AccessibilityWatchdog.shouldPromptRecovery(seenOn = true, currentlyEnabled = true))
        assertFalse(AccessibilityWatchdog.shouldPromptRecovery(seenOn = false, currentlyEnabled = true))
    }

    @Test
    fun `勾还开着但服务被系统杀掉要提醒`() {
        // DiLink 常态：设置里勾还在，服务被系统单独停掉 → 必须提醒（旧逻辑漏掉这一种）
        assertTrue(
            AccessibilityWatchdog.shouldPromptServiceDead(
                seenOn = true, enabled = true, connected = false,
            ),
        )
        // 服务正常连着 → 不打扰
        assertFalse(
            AccessibilityWatchdog.shouldPromptServiceDead(
                seenOn = true, enabled = true, connected = true,
            ),
        )
        // 勾被关掉 → 交给 shouldPromptRecovery 那条，不在这里重复
        assertFalse(
            AccessibilityWatchdog.shouldPromptServiceDead(
                seenOn = true, enabled = false, connected = false,
            ),
        )
        // 从没开过 → 不打扰
        assertFalse(
            AccessibilityWatchdog.shouldPromptServiceDead(
                seenOn = false, enabled = true, connected = false,
            ),
        )
    }

    @Test
    fun `服务连上后记住曾经开启`() {
        // Robolectric 每个用例一个干净的 SharedPreferences，所以起点必是 false
        assertFalse(AccessibilityWatchdog.seenOn(context))
        assertEquals(0L, AccessibilityWatchdog.lastConnectedAt(context))
        AccessibilityWatchdog.markOn(context)
        assertTrue(AccessibilityWatchdog.seenOn(context))
        assertTrue(AccessibilityWatchdog.lastConnectedAt(context) > 0L)
        assertEquals(ours(), AccessibilityWatchdog.rememberedComponent(context))
    }

    @Test
    fun `合并写回不覆盖其它无障碍服务`() {
        val ours = "com.luckylearning.windtone/com.luckylearning.windtone.overlay.ForegroundAppService"
        assertEquals(ours, AccessibilityWatchdog.mergeEnabledServices(null, ours))
        assertEquals(ours, AccessibilityWatchdog.mergeEnabledServices("", ours))
        assertEquals(
            "com.other/.Svc:$ours",
            AccessibilityWatchdog.mergeEnabledServices("com.other/.Svc", ours),
        )
        assertEquals(
            "com.other/.Svc:$ours",
            AccessibilityWatchdog.mergeEnabledServices("com.other/.Svc:$ours", ours),
        )
    }

    @Test
    fun `合并写回忽略大小写空白与空段`() {
        val ours = "com.luckylearning.windtone/com.luckylearning.windtone.overlay.ForegroundAppService"
        val upper = ours.uppercase()
        // 已存在（大小写不同）时不重复追加；保留列表里原有写法，只清空白/空段
        assertEquals(
            "com.other/.A:$upper",
            AccessibilityWatchdog.mergeEnabledServices(" com.other/.A :: $upper : ", ours),
        )
        assertEquals(
            ours,
            AccessibilityWatchdog.mergeEnabledServices("::$ours::", ours),
        )
        val merged = AccessibilityWatchdog.mergeEnabledServices("com.other/.A:$upper", ours)
        assertEquals(1, merged.split(':').count { it.equals(ours, ignoreCase = true) })
    }

    @Test
    fun `无 WRITE_SECURE_SETTINGS 时 tryAutoRestore 不假装成功`() {
        AccessibilityWatchdog.markOn(context)
        writeEnabledServices(null)
        // Robolectric 默认没有该权限；且当前未开启 → 必须返回 false，走通知路径
        assertFalse(AccessibilityWatchdog.hasWriteSecureSettings(context))
        assertFalse(AccessibilityWatchdog.tryAutoRestore(context))
        // 权限缺失时不得改写 Secure 列表（否则诊断会误判「已尝试写回」）
        assertTrue(readEnabledServices().isNullOrEmpty())
    }

    @Test
    fun `从没开过 tryAutoRestore 直接跳过`() {
        assertFalse(AccessibilityWatchdog.seenOn(context))
        assertFalse(AccessibilityWatchdog.tryAutoRestore(context))
        assertTrue(readEnabledServices().isNullOrEmpty())
    }

    @Test
    fun `已开启时 tryAutoRestore 刷新标记且不依赖写权限`() {
        AccessibilityWatchdog.markOn(context)
        writeEnabledServices(ours())
        assertFalse(AccessibilityWatchdog.hasWriteSecureSettings(context))
        assertTrue(AccessibilityWatchdog.tryAutoRestore(context))
        assertTrue(AccessibilityWatchdog.seenOn(context))
        assertEquals(ours(), readEnabledServices())
    }

    @Test
    fun `有 WRITE_SECURE_SETTINGS 时合并写回其它服务并成功`() {
        AccessibilityWatchdog.markOn(context)
        writeEnabledServices("com.other/.Svc")
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertTrue(AccessibilityWatchdog.hasWriteSecureSettings(context))

        assertTrue(AccessibilityWatchdog.tryAutoRestore(context))
        assertEquals(
            "com.other/.Svc:${ours()}",
            readEnabledServices(),
        )
        assertTrue(ForegroundAppDetector.accessibilityEnabled(context))
        assertTrue(AccessibilityWatchdog.seenOn(context))
    }

    @Test
    fun `checkAndNotify 从未开过不发提醒`() {
        writeEnabledServices(null)
        assertFalse(AccessibilityWatchdog.seenOn(context))
        assertFalse(AccessibilityWatchdog.checkAndNotify(context))
    }

    @Test
    fun `checkAndNotify 已开启时不发提醒`() {
        AccessibilityWatchdog.markOn(context)
        writeEnabledServices(ours())
        assertFalse(AccessibilityWatchdog.checkAndNotify(context))
    }

    @Test
    fun `checkAndNotify 掉线且无写权限时发提醒`() {
        AccessibilityWatchdog.markOn(context)
        writeEnabledServices(null)
        assertFalse(AccessibilityWatchdog.hasWriteSecureSettings(context))
        assertTrue(AccessibilityWatchdog.checkAndNotify(context))
        // 同一通知挂着时再次检查不应重复发
        assertFalse(AccessibilityWatchdog.checkAndNotify(context))
    }
}
