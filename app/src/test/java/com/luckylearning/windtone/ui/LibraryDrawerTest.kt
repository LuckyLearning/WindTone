package com.luckylearning.windtone.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 侧边抽屉（v1.1.3 新增导航组件）的冒烟回归。
 *
 * 为什么值得单独测：底部五视图导航被移除之后，**抽屉成了唯一的视图切换入口** ——
 * 它要是渲染不出来或者点击不生效，整个「歌曲 / 专辑 / 歌手 / 文件夹 / 歌单」
 * 就都进不去了。而这类问题在编译期完全看不出来（能编译、能构建、打开应用才发现）。
 *
 * 跑在 Robolectric + compose rule 上：本机没有设备也没有模拟器，
 * 这是能自动化跑到的最接近「点一下看看」的验证方式。
 *
 * ⚠️ 三个 Robolectric 上的坑（都踩过，别删）：
 *  1. **必须给窗口尺寸**（下面 `qualifiers`）。不给的话节点会存在于语义树里但
 *     **size 为 0** —— `assertIsDisplayed()` 报「The component is not displayed!」。
 *  2. **一个测试只能调一次 `setContent`**（第二次直接抛
 *     `Cannot call setContent twice per test!`）。要换状态就用 `mutableStateOf`
 *     驱动重组，而不是重新渲染一遍。
 *  3. **必须开 NATIVE 图形模式**（类上的 `@GraphicsMode`）。默认的 LEGACY 后端下，
 *     **只要祖先里有 `Modifier.shadow` 产生的图层，整棵子树就收不到触摸**：点击会
 *     穿透到下层（这里是遮罩 → 抽屉一打开就被关掉），`performClick()` 静默走错目标。
 *     实证：把面板换成「无阴影的 Surface」点击立刻正常，加回 `shadowElevation = 10.dp`
 *     就失效；切到 NATIVE 后两种情况都正常 → 是图形后端的命中测试算错了，不是产品代码
 *     （`Surface(shadowElevation=…)` 是 Material3 对话框 / 底部弹层的通用写法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryDrawerTest {

    @get:Rule
    val compose = createComposeRule()

    /** 记录回调命中情况；`open` 恒为 true，所以点完抽屉不会真的收起，可以连续点多项 */
    private class Recorder {
        var selected: LibraryView? = null
        var closed = false
        var scanScope = false
        var checkUpdate = false
        var settings = false
    }

    private fun renderOpen(currentView: LibraryView = LibraryView.SONGS): Recorder {
        val rec = Recorder()
        compose.setContent {
            MusicPlayerTheme {
                LibraryDrawer(
                    open = true,
                    currentView = currentView,
                    onSelectView = { rec.selected = it },
                    onOpenScanScope = { rec.scanScope = true },
                    onCheckUpdate = { rec.checkUpdate = true },
                    onOpenSettings = { rec.settings = true },
                    onClose = { rec.closed = true },
                )
            }
        }
        compose.waitForIdle()
        return rec
    }

    /** 关着的时候抽屉不该留在组合里（AnimatedVisibility 收起后整体移除、不吃触摸） */
    @Test
    fun closedDrawerRendersNothing() {
        compose.setContent {
            MusicPlayerTheme {
                LibraryDrawer(
                    open = false,
                    currentView = LibraryView.SONGS,
                    onSelectView = {},
                    onOpenScanScope = {},
                    onCheckUpdate = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("听风").assertDoesNotExist()
        compose.onNodeWithText("歌曲").assertDoesNotExist()
    }

    /** 品牌块 + 两组共 8 项，一个都不能少 */
    @Test
    fun showsBrandAndBothGroups() {
        renderOpen()
        compose.onNodeWithText("听风").assertIsDisplayed()
        compose.onNodeWithText("WindTone · 本地音乐").assertIsDisplayed()
        compose.onNodeWithText("音乐库").assertIsDisplayed()
        compose.onNodeWithText("更多").assertIsDisplayed()
        // 第一组 = 原底部导航的五个浏览视图
        listOf("歌曲", "专辑", "歌手", "文件夹", "歌单").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // 第二组 = 与顶栏 ⋮ 菜单同目的地的三个快捷入口
        listOf("扫描范围", "检查更新", "设置").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    /** 点视图 = 切视图 + 收起抽屉（否则遮罩还在，用户看不到自己刚选的列表） */
    @Test
    fun tappingViewSwitchesAndClosesDrawer() {
        val rec = renderOpen(currentView = LibraryView.SONGS)
        compose.onNodeWithText("专辑").performClick()
        compose.waitForIdle()
        assertEquals(LibraryView.ALBUM, rec.selected)
        assertTrue("点视图后抽屉必须收起", rec.closed)
    }

    /** 五个视图逐个点一遍，回调拿到的都是被点的那一个（顺序/映射别错位） */
    @Test
    fun everyViewItemReportsItsOwnView() {
        val rec = renderOpen()
        LibraryView.entries.forEach { view ->
            rec.selected = null
            rec.closed = false
            compose.onNodeWithText(view.label).performClick()
            compose.waitForIdle()
            assertEquals(view, rec.selected)
            assertTrue(rec.closed)
        }
    }

    /** 「更多」三项各自直达，且都不误伤「切换视图」 */
    @Test
    fun tappingMoreItemsFiresTheirCallbacksAndCloses() {
        val rec = renderOpen()

        compose.onNodeWithText("扫描范围").performClick()
        compose.waitForIdle()
        assertTrue(rec.scanScope)
        assertTrue(rec.closed)
        assertNull("「更多」项不该被当成视图切换", rec.selected)

        compose.onNodeWithText("检查更新").performClick()
        compose.waitForIdle()
        assertTrue(rec.checkUpdate)

        compose.onNodeWithText("设置").performClick()
        compose.waitForIdle()
        assertTrue(rec.settings)
    }

    /** 五个视图依次被标成「当前」时，选中项都要正常渲染（高亮只是视觉，但布局不能崩） */
    @Test
    fun everyViewRendersWhenItIsTheCurrentOne() {
        val current = mutableStateOf(LibraryView.SONGS)
        compose.setContent {
            MusicPlayerTheme {
                LibraryDrawer(
                    open = true,
                    currentView = current.value,
                    onSelectView = {},
                    onOpenScanScope = {},
                    onCheckUpdate = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }
        LibraryView.entries.forEach { view ->
            current.value = view
            compose.waitForIdle()
            compose.onNodeWithText(view.label).assertIsDisplayed()
        }
    }
}
