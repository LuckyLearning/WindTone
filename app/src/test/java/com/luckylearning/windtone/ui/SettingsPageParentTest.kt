package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E2：设置页返回栈父链（悬浮窗 / 关于子页 / 实验室子页）。
 *
 * [SettingsMotionTest] 已锁 depth；这里钉**具体父页**——OVERLAY 若误指 ROOT，
 * 从歌词进悬浮窗再返回会直接跳出设置，车机上等于丢了上下文。
 */
class SettingsPageParentTest {

    @Test
    fun overlayReturnsToLyrics() {
        assertEquals(SettingsPage.LYRICS, SettingsPage.OVERLAY.parent())
    }

    @Test
    fun aboutChildrenReturnToAbout() {
        assertEquals(SettingsPage.ABOUT, SettingsPage.UPDATE.parent())
        assertEquals(SettingsPage.ABOUT, SettingsPage.DIAGNOSIS.parent())
    }

    @Test
    fun labChildrenReturnToLabThenOnlineHub() {
        assertEquals(SettingsPage.LAB, SettingsPage.ONLINE_HUB.parent())
        assertEquals(SettingsPage.LAB, SettingsPage.MENU_ORDER.parent())
        assertEquals(SettingsPage.ONLINE_HUB, SettingsPage.ONLINE.parent())
    }

    @Test
    fun topLevelCategoriesReturnToRoot() {
        listOf(
            SettingsPage.APPEARANCE,
            SettingsPage.PLAYBACK,
            SettingsPage.LYRICS,
            SettingsPage.LIBRARY,
            SettingsPage.ABOUT,
            SettingsPage.LAB,
        ).forEach { page ->
            assertEquals("$page 应回主页", SettingsPage.ROOT, page.parent())
        }
        assertEquals(SettingsPage.ROOT, SettingsPage.ROOT.parent())
    }
}
