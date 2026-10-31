package com.luckylearning.windtone.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G8 / E2：设置页搜索过滤（i18n 第 23 批后关键词走 `ss_kw_*`）。
 *
 * 关键词资源为中英同串，故 zh-rCN 下中文俗称与英文别名都能命中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN")
class SettingsSearchTest {

    private val catalog = SettingsSearchCatalog.entries()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppStrings.init(context)
    }

    private fun resolve(id: Int): String = SettingsSearch.resolveTitle(context, id)

    private fun filter(query: String): List<SettingsSearchEntry> =
        SettingsSearch.filter(catalog, query, ::resolve)

    @Test
    fun emptyQuery_returnsEmpty_uiShowsCategories() {
        assertEquals(0, filter("").size)
        assertEquals(0, filter("   ").size)
    }

    @Test
    fun matchesCategoryTitle() {
        assertTrue(filter("appear").any { it.page == SettingsPage.APPEARANCE } ||
            filter("外观").any { it.page == SettingsPage.APPEARANCE })
        assertTrue(filter("外观").any { it.page == SettingsPage.APPEARANCE })
    }

    @Test
    fun matchesKeywordsAndSection() {
        assertTrue(filter("silence").any { it.titleRes == R.string.skip_silence })
        assertTrue(filter("静音").any { it.titleRes == R.string.skip_silence })
        // section title from resources
        val behaviorHits = filter("behavior") + filter("播放行为")
        assertTrue(behaviorHits.any { it.sectionRes == R.string.playback_behavior })
    }

    @Test
    fun noMatch_returnsEmpty() {
        assertEquals(0, filter("zzzz-not-a-setting").size)
    }

    /** E2：中文关键词 → 车机 / 悬浮窗设置入口。 */
    @Test
    fun chineseKeywords_hitCarOverlayAndIntro() {
        assertTrue(
            "车载应命中外观→车载模式",
            filter("车载").any {
                it.page == SettingsPage.APPEARANCE && it.titleRes == R.string.car_mode
            },
        )
        assertTrue(
            "悬浮应命中悬浮歌词页",
            filter("悬浮").any { it.page == SettingsPage.OVERLAY },
        )
        assertTrue(
            "片头应命中播放→跳过片头",
            filter("片头").any {
                it.page == SettingsPage.PLAYBACK && it.titleRes == R.string.skip_intro_seconds_title
            },
        )
    }

    @Test
    fun queryIsTrimmedAndCaseInsensitive() {
        assertTrue(filter("  APPEAR  ").any { it.page == SettingsPage.APPEARANCE } ||
            filter("外观").any { it.page == SettingsPage.APPEARANCE })
        assertTrue(filter("CAR").any { it.titleRes == R.string.car_mode })
        assertTrue(filter(" Overlay ").any { it.page == SettingsPage.OVERLAY })
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
