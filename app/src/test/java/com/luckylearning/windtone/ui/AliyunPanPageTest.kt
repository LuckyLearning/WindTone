package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 阿里云盘一级页（v1.1.75）的冒烟回归。
 *
 * 文案已全部走 stringResource；断言用当前测试 Locale 下的 getString，
 * 避免硬编码中文在默认 values（英文）下找不到节点。
 *
 * 坑与 [LibraryDrawerTest] 相同：必须给窗口尺寸、必须开 NATIVE 图形模式、
 * 一次测试只能 setContent 一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AliyunPanPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun s(id: Int): String = ctx.getString(id)

    private fun render(onClose: () -> Unit = {}, onOpenDrawer: () -> Unit = {}) {
        compose.setContent {
            MusicPlayerTheme {
                AliyunPanPage(
                    onPlay = { _, _ -> },
                    onClose = onClose,
                    onOpenDrawer = onOpenDrawer,
                )
            }
        }
        compose.waitForIdle()
    }

    /** 未登录：hero 引导 + 两个授权入口，一个都不能少 */
    @Test
    fun notLoggedInShowsHeroAndAuthEntries() {
        render()
        compose.onNodeWithText(s(R.string.drawer_aliyun)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.aliyun_connect_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.aliyun_scan_auth)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.aliyun_paste_token)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.aliyun_open_app)).assertIsDisplayed()
    }

    /** 「手动粘贴令牌」入口存在即可 — 弹层不在 Robolectric 里打开 */
    @Test
    fun pasteTokenEntryIsPresent() {
        render()
        compose.onNodeWithText(s(R.string.aliyun_paste_token)).assertIsDisplayed()
    }

    /** 顶栏返回键生效（一级页的退出动作） */
    @Test
    fun backButtonFiresOnClose() {
        var closed = false
        render(onClose = { closed = true })
        compose.onNodeWithContentDescription(s(R.string.aliyun_cd_back)).performClick()
        compose.waitForIdle()
        assertTrue("click back must call onClose", closed)
    }
}
