package com.luckylearning.windtone.overlay

import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 打开「无障碍 / 使用情况访问」设置页的候选顺序（v1.1.81）。
 *
 * 背景：比亚迪 DiLink 上 `ACTION_ACCESSIBILITY_SETTINGS` 没有对应 Activity，
 * 旧实现直接 startActivity → ActivityNotFoundException → 只弹一个 toast，
 * 用户没有任何出口。现在改成候选列表逐个试，最后兜底到系统设置首页。
 *
 * 这里锁的是**列表结构**（首选标准 action、末尾必须有兜底），
 * 真机上到底哪一项能打开只能靠 P0 清单验 —— 但「没有兜底」这种退化必须在这里拦住。
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundAppDetectorTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `无障碍候选首选标准 action，且不把设置首页当成成功`() {
        val intents = ForegroundAppDetector.accessibilitySettingsIntents(context)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intents.first().action)
        assertTrue("候选里必须有显式组件（OEM 改名时靠它）", intents.any { it.component != null })
        assertTrue(
            "ACTION_SETTINGS 不能混进候选：打开设置首页会被误判成「无障碍页已打开」，" +
                "用户落在设置首页却看不到路径向导",
            intents.none { it.action == Settings.ACTION_SETTINGS },
        )
        // 兜底是独立入口，由路径向导的按钮显式触发
        assertEquals(
            Settings.ACTION_SETTINGS,
            ForegroundAppDetector.systemSettingsIntent().action,
        )
    }

    @Test
    fun `使用情况访问候选不混入设置首页`() {
        val intents = ForegroundAppDetector.usageAccessIntents()
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intents.first().action)
        assertTrue(intents.none { it.action == Settings.ACTION_SETTINGS })
    }

    @Test
    fun `每个候选都带 NEW_TASK`() {
        // 从 Activity 之外（诊断页里的普通 context 也是 Activity，但这里按契约锁死）
        // 启动时必须带 NEW_TASK，否则某些 ROM 会直接抛异常
        (ForegroundAppDetector.accessibilitySettingsIntents(context) +
            ForegroundAppDetector.usageAccessIntents()).forEach { intent ->
            assertTrue(
                "缺少 FLAG_ACTIVITY_NEW_TASK: ${intent.action ?: intent.component}",
                intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
            )
        }
    }
}
