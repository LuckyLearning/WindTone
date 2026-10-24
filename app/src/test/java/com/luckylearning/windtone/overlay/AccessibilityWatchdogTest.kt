package com.luckylearning.windtone.overlay

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 无障碍「重启后掉线」的提醒决策（v1.1.85）。
 *
 * 为什么单独测：这条链路一旦误判，用户每次开机都会收到一条「辅助功能被关闭了」的
 * 通知 —— 而他从没开过这个功能，只会当成骚扰。所以「从没开过就不提醒」这条
 * 必须钉死，它比「掉了要提醒」更容易被后来的改动人忽略。
 */
@RunWith(RobolectricTestRunner::class)
class AccessibilityWatchdogTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

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
    fun `服务连上后记住曾经开启`() {
        // Robolectric 每个用例一个干净的 SharedPreferences，所以起点必为 false
        assertFalse(AccessibilityWatchdog.seenOn(context))
        AccessibilityWatchdog.markOn(context)
        assertTrue(AccessibilityWatchdog.seenOn(context))
    }
}
