package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.SourceMode
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 车机左侧常驻导航（v1.1.84）。
 *
 * 回归背景：车机模式用的是 [CarNavRail] 而不是抽屉，而「阿里云盘」原本只在抽屉的
 * 「在线音乐」组里 —— 也就是说**车机上没有云盘入口**，用户只能看到一句「找不到」。
 * 这条用例钉三件事：云盘入口在、点它有回调、云盘页打开时曲库视图不再高亮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarNavRailTest {

    @get:Rule
    val compose = createComposeRule()

    /** 菜单文案统一走资源（v1.3.0 起车机与手机共用同一批字符串），测试也照资源取 */
    private val aliyunLabel: String
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.drawer_aliyun)

    private fun viewLabel(view: LibraryView): String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(carViewLabelRes(view))

    private fun render(
        current: LibraryView = LibraryView.SONGS,
        aliyunActive: Boolean = false,
        onOpenAliyun: () -> Unit = {},
        onSelect: (LibraryView) -> Unit = {},
        sourceMode: SourceMode = SourceMode.LOCAL,
        onlineActive: Boolean = false,
        lxSection: LxSection = LxSection.SEARCH,
        onSelectLx: (LxSection) -> Unit = {},
    ) {
        compose.setContent {
            MusicPlayerTheme {
                CarNavRail(
                    current = current,
                    onSelect = onSelect,
                    onOpenSettings = {},
                    aliyunActive = aliyunActive,
                    onOpenAliyun = onOpenAliyun,
                    sourceMode = sourceMode,
                    onlineActive = onlineActive,
                    lxSection = lxSection,
                    onSelectLx = onSelectLx,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `本地模式左导航没有云盘和洛雪`() {
        render()
        compose.onNodeWithText(viewLabel(LibraryView.SONGS)).assertIsDisplayed()
        compose.onNodeWithText(aliyunLabel).assertDoesNotExist()
        compose.onNodeWithText("搜索").assertDoesNotExist()
    }

    @Test
    fun `云盘模式左导航只有云盘且可点击`() {
        var opened = false
        render(sourceMode = SourceMode.CLOUD, aliyunActive = true, onOpenAliyun = { opened = true })

        compose.onNodeWithText(aliyunLabel).assertIsDisplayed()
        compose.onNodeWithText(viewLabel(LibraryView.ALBUM)).assertDoesNotExist()
        compose.onNodeWithText(viewLabel(LibraryView.SONGS)).assertDoesNotExist()
        compose.onNodeWithText(aliyunLabel).performClick()
        compose.waitForIdle()
        assertTrue("点云盘应打开阿里云盘页", opened)
    }

    @Test
    fun `洛雪模式左导航是搜索歌单排行榜和音源管理`() {
        var section: LxSection? = null
        render(
            sourceMode = SourceMode.LX,
            onlineActive = true,
            lxSection = LxSection.SEARCH,
            onSelectLx = { section = it },
        )
        listOf("搜索", "歌单", "排行榜", "音源管理").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText(aliyunLabel).assertDoesNotExist()
        compose.onNodeWithText(viewLabel(LibraryView.SONGS)).assertDoesNotExist()
        compose.onNodeWithText("排行榜").performClick()
        compose.waitForIdle()
        assertTrue(section == LxSection.BOARDS)
    }
}
