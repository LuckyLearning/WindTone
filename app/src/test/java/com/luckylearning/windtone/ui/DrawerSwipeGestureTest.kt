package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 抽屉手势的状态机回归。
 *
 * [DrawerSwipeTest] 只测了纯函数 `decide`；这里补「一次手势里的方向会变」这条：
 * 先明显右滑（判定 OPEN 并 consume），再转成纵向（判定 YIELD），松手时不能开抽屉。
 * 以前用 `opening` 记住「曾经打开过」，这种手势会被误判成开抽屉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class DrawerSwipeGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened = 0

    private fun content() {
        compose.setContent {
            val opener = remember {
                DrawerOpener().also { it.onOpen = { opened++ } }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawerSwipe(enabled = true, opener = opener),
            )
        }
    }

    @Test
    fun rightSwipeThenVerticalReleaseDoesNotOpen() {
        content()
        compose.onRoot().performTouchInput {
            down(center)
            // 超过 72dp（xhdpi 下 144px），判定 OPEN
            moveBy(Offset(220f, 0f))
            // 手指转向纵向，判定 YIELD；松手不应开抽屉
            moveBy(Offset(0f, 500f))
            up()
        }
        compose.waitForIdle()
        assertEquals("转成纵向后松手不能开抽屉", 0, opened)
    }

    @Test
    fun cleanRightSwipeOpens() {
        content()
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(300f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals("明显右滑应当开抽屉", 1, opened)
    }
}
