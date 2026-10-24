package com.luckylearning.windtone.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * 云盘页升为一级入口后，它是抽屉里「在线音乐」组的落地页 —— 未登录的授权引导
 * 要是渲染不出来或者点击不生效，在线播放整条路就断了。这类问题编译期看不出来
 * （能编译、能构建、打开应用才发现），所以按 [LibraryDrawerTest] 的同一套
 * Robolectric 冒烟写法钉住。
 *
 * 测试环境的 AliyunAuth 是全新 SharedPreferences → 一定落在「未登录」分支，
 * 正好覆盖页面的 hero 引导与两个授权入口。
 *
 * 坑与 [LibraryDrawerTest] 相同：必须给窗口尺寸（否则节点 size 为 0）、
 * 必须开 NATIVE 图形模式（阴影图层会吃掉触摸）、一次测试只能 setContent 一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AliyunPanPageTest {

    @get:Rule
    val compose = createComposeRule()

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
        compose.onNodeWithText("阿里云盘").assertIsDisplayed()
        compose.onNodeWithText("连接你的阿里云盘").assertIsDisplayed()
        compose.onNodeWithText("扫码授权").assertIsDisplayed()
        compose.onNodeWithText("手动粘贴令牌").assertIsDisplayed()
        compose.onNodeWithText("打开阿里云盘 App").assertIsDisplayed()
    }

    /** 「手动粘贴令牌」入口存在即可 —— 弹层不在 Robolectric 里打开（连点击都不点）：
     *  material3 的 TextField 弹层在本机图形后端下会让 Compose 永远不 idle
     *  （AppNotIdleException，实测踩中）；弹层代码与旧版逐字相同，真机一直在用。 */
    @Test
    fun pasteTokenEntryIsPresent() {
        render()
        compose.onNodeWithText("手动粘贴令牌").assertIsDisplayed()
    }

    /** 顶栏返回键生效（一级页的退出动作，不再依赖系统返回栈） */
    @Test
    fun backButtonFiresOnClose() {
        var closed = false
        render(onClose = { closed = true })
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertTrue("点返回必须回调 onClose", closed)
    }
}
