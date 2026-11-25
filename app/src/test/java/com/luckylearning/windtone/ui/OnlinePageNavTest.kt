package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 洛雪页不再用顶栏 Tab。分区由左侧菜单推进来的 [LxFrame] 决定，
 * 返回只在还有子页或上一个分区时出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnlinePageNavTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchSectionHasNoTopTabs() {
        compose.setContent {
            MusicPlayerTheme {
                OnlinePage(
                    place = LxFrame(LxSection.SEARCH),
                    canPop = false,
                    onBack = {},
                    onOpenDetail = {},
                    onSelectSection = {},
                    onPlay = { _, _ -> },
                    onOpenDrawer = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("搜索歌曲或歌手").assertIsDisplayed()
        compose.onNodeWithText("排行榜").assertDoesNotExist()
        compose.onNodeWithText("音源管理").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    @Test
    fun boardsSectionShowsBoardNamesAndBackPops() {
        var backed = false
        compose.setContent {
            MusicPlayerTheme {
                OnlinePage(
                    place = LxFrame(LxSection.BOARDS),
                    canPop = true,
                    onBack = { backed = true },
                    onOpenDetail = {},
                    onSelectSection = {},
                    onPlay = { _, _ -> },
                    onOpenDrawer = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("TOP500").assertIsDisplayed()
        compose.onNodeWithText("搜索歌曲或歌手").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertTrue(backed)
    }

    @Test
    fun sourceSectionIsAPageNotADialog() {
        compose.setContent {
            MusicPlayerTheme {
                OnlinePage(
                    place = LxFrame(LxSection.SOURCES),
                    canPop = true,
                    onBack = {},
                    onOpenDetail = {},
                    onSelectSection = {},
                    onPlay = { _, _ -> },
                    onOpenDrawer = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("从网址导入").assertIsDisplayed()
        compose.onNodeWithText("搜索歌曲或歌手").assertDoesNotExist()
    }
}
