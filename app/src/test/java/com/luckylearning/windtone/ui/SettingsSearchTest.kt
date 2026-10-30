package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G8：设置页搜索纯函数过滤。 */
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
}
