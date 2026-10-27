package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SettingsPage.depth()` 与 `SettingsMotion` 的方向判据。
 *
 * 为什么单独测：滑行方向**完全**由 depth 差决定（`target.depth() > initial.depth()`），
 * 而 depth 是沿 `parent()` 一路数出来的 —— 父页写错、或新加一层忘了改 parent，
 * 表现是「下钻时页面从左边推进来」这种立刻能被用户看出来的反向动画，
 * 但编译、lint、其它测试全都不报。
 *
 * 这里同时锁住**结构约束**：设置页最多三层（主页 → 分类 → 子项），
 * 加第四层的话这条会红，提醒先想清楚是不是又该收进「实验室」。
 */
class SettingsMotionTest {

    /** 主页是最浅一层。 */
    @Test
    fun rootIsDepthZero() {
        assertEquals(0, SettingsPage.ROOT.depth())
    }

    /** 六个分类页都在第一层 —— 它们直接挂在主页下。 */
    @Test
    fun categoryPagesAreDepthOne() {
        val categories = listOf(
            SettingsPage.APPEARANCE,
            SettingsPage.PLAYBACK,
            SettingsPage.LYRICS,
            SettingsPage.LIBRARY,
            SettingsPage.DIAGNOSTICS,
            SettingsPage.LAB,
        )
        categories.forEach { page ->
            assertEquals("${page.name} 应该直接挂在主页下", 1, page.depth())
        }
    }

    /** 收进上层页面的入口行（悬浮歌词 / 更新 / 在线播放 / 菜单顺序）都在第二层及以下。 */
    @Test
    fun nestedPagesAreDeeperThanTheirParents() {
        listOf(
            SettingsPage.OVERLAY,
            SettingsPage.UPDATE,
            SettingsPage.ONLINE_HUB,
            SettingsPage.MENU_ORDER,
        ).forEach { page ->
            assertTrue(
                "${page.name} 的父页不该是主页（v1.1.80 起它已降级为上层页面里的入口行）",
                page.parent() != SettingsPage.ROOT,
            )
            assertEquals(
                "${page.name} 应比父页深一层",
                page.parent().depth() + 1,
                page.depth(),
            )
        }
        // 在线播放嵌在「实验室 → 在线播放」里，是唯一的三层页面
        assertEquals(3, SettingsPage.ONLINE.depth())
    }

    /**
     * 结构约束：最多三层。
     *
     * 加第四层会让「下钻/返回」的层级感彻底失效（用户已经数不清自己退到哪了），
     * 真要加，先确认是不是该并进上一层。
     */
    @Test
    fun settingsStaysWithinThreeLevels() {
        SettingsPage.entries.forEach { page ->
            assertTrue(
                "${page.name} 已经是第 ${page.depth()} 层，设置页上限三层",
                page.depth() <= 3,
            )
        }
    }

    /** 每加一个页面都必须能走到主页 —— 父链断掉（成环 / 指回自己）会让 depth 退化成 0。 */
    @Test
    fun everyPageReachesRoot() {
        SettingsPage.entries.forEach { page ->
            var cursor = page
            var hops = 0
            while (cursor != SettingsPage.ROOT && hops <= SettingsPage.entries.size) {
                cursor = cursor.parent()
                hops++
            }
            assertEquals("${page.name} 沿 parent() 走不到主页", SettingsPage.ROOT, cursor)
        }
    }

    /** 动效时长必须取自全站令牌，不许在别处写魔法数。 */
    @Test
    fun motionDurationsComeFromTokens() {
        assertEquals(com.luckylearning.windtone.ui.theme.AppVisual.Motion.BaseMs, SettingsMotion.SlideMs)
        assertEquals(com.luckylearning.windtone.ui.theme.AppVisual.Motion.FastMs, SettingsMotion.FadeMs)
        // 滑动要比淡入慢一点：两页错身时纯淡入淡出会显得"糊"，有位移才像"翻过去"。
        assertTrue(SettingsMotion.SlideMs >= SettingsMotion.FadeMs)
    }
}
