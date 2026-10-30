package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G8 / E2：设置页搜索纯函数过滤。
 *
 * 补关键路径：中文关键词（车载 / 悬浮 / 片头）、大小写与 trim、
 * 六大分区目录覆盖 —— 文档宣称有测、此前用例未钉死。
 */
class SettingsSearchTest {

    private val catalog = SettingsSearchCatalog.entries()

    private fun filterWith(titles: Map<Int, String>, query: String): List<SettingsSearchEntry> =
        SettingsSearch.filter(catalog, query) { id -> titles[id] ?: "res:$id" }

    @Test
    fun emptyQuery_returnsEmpty_uiShowsCategories() {
        val resolve: (Int) -> String = { id -> "res:$id" }
        assertEquals(0, SettingsSearch.filter(catalog, "", resolve).size)
        assertEquals(0, SettingsSearch.filter(catalog, "   ", resolve).size)
    }

    @Test
    fun matchesCategoryTitle() {
        val titles = mapOf(
            R.string.appearance to "Appearance",
            R.string.playback to "Playback",
            R.string.lyrics to "Lyrics",
        )
        assertTrue(filterWith(titles, "appear").any { it.page == SettingsPage.APPEARANCE })
    }

    @Test
    fun matchesKeywordsAndSection() {
        val titles = mapOf(
            R.string.skip_silence to "Skip silence",
            R.string.playback_behavior to "Playback behavior",
            R.string.gapless_playback to "Gapless",
        )
        assertTrue(filterWith(titles, "silence").any { it.titleRes == R.string.skip_silence })
        assertTrue(filterWith(titles, "behavior").any { it.sectionRes == R.string.playback_behavior })
    }

    @Test
    fun noMatch_returnsEmpty() {
        val titles = mapOf(R.string.appearance to "Appearance")
        assertEquals(0, filterWith(titles, "zzzz-not-a-setting").size)
    }

    /** E2：中文关键词 → 车机 / 悬浮窗设置入口（不依赖真实 string 资源）。 */
    @Test
    fun chineseKeywords_hitCarOverlayAndIntro() {
        val titles = emptyMap<Int, String>()
        assertTrue(
            "车载应命中外观→车载模式",
            filterWith(titles, "车载").any {
                it.page == SettingsPage.APPEARANCE && it.titleRes == R.string.car_mode
            },
        )
        assertTrue(
            "悬浮应命中悬浮歌词页",
            filterWith(titles, "悬浮").any { it.page == SettingsPage.OVERLAY },
        )
        assertTrue(
            "片头应命中播放→跳过片头",
            filterWith(titles, "片头").any {
                it.page == SettingsPage.PLAYBACK && it.titleRes == R.string.skip_intro_seconds_title
            },
        )
    }

    @Test
    fun queryIsTrimmedAndCaseInsensitive() {
        val titles = mapOf(R.string.appearance to "Appearance")
        assertTrue(filterWith(titles, "  APPEAR  ").any { it.page == SettingsPage.APPEARANCE })
        assertTrue(filterWith(emptyMap(), "CAR").any { it.titleRes == R.string.car_mode })
        assertTrue(filterWith(emptyMap(), " Overlay ").any { it.page == SettingsPage.OVERLAY })
    }

    @Test
    fun catalogCoversSixRootCategoriesAndOverlay() {
        val pages = catalog.map { it.page }.toSet()
        listOf(
            SettingsPage.APPEARANCE,
            SettingsPage.PLAYBACK,
            SettingsPage.LYRICS,
            SettingsPage.LIBRARY,
            SettingsPage.ABOUT,
            SettingsPage.LAB,
        ).forEach { page ->
            assertTrue("目录应覆盖 $page", pages.contains(page))
        }
        assertTrue("悬浮窗设置入口必须可搜", pages.contains(SettingsPage.OVERLAY))
        assertFalse("主页本身不是可搜条目", pages.contains(SettingsPage.ROOT))
    }
}
