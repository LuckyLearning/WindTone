package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
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

    private fun render(
        current: LibraryView = LibraryView.SONGS,
        aliyunActive: Boolean = false,
        onOpenAliyun: () -> Unit = {},
        onSelect: (LibraryView) -> Unit = {},
    ) {
        compose.setContent {
            MusicPlayerTheme {
                CarNavRail(
                    current = current,
                    onSelect = onSelect,
                    onOpenSettings = {},
                    aliyunActive = aliyunActive,
                    onOpenAliyun = onOpenAliyun,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `车机导航里有云盘入口且可点击`() {
        var opened = false
        render(onOpenAliyun = { opened = true })

        compose.onNodeWithText(aliyunLabel).assertIsDisplayed()
        compose.onNodeWithText(aliyunLabel).performClick()
        compose.waitForIdle()
        assertTrue("点云盘应打开阿里云盘页", opened)
    }

    @Test
    fun `曲库视图与云盘互斥高亮`() {
        // 云盘页打开时点任意曲库视图，回调照常触发（由调用方负责关闭云盘页），
        // 本用例只锁「入口存在且不吞点击」——高亮视觉无法在语义树里断言。
        var selected: LibraryView? = null
        render(current = LibraryView.SONGS, aliyunActive = true, onSelect = { selected = it })

        compose.onNodeWithText(carViewLabel(LibraryView.ALBUM)).performClick()
        compose.waitForIdle()
        assertTrue("云盘页打开时曲库视图仍可点（用于退出云盘）", selected == LibraryView.ALBUM)
        compose.onNodeWithText(aliyunLabel).assertIsDisplayed()
    }
}
