package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 播放页「滑动切歌」的**接线**回归（2026-10-28）。
 *
 * 为什么要单独测接线：[PlayerSwipe] 的判据（阈值 / 阻力 / 回弹）早就有 8 例单测覆盖，
 * 但那些测的是 `resolve()` / `resisted()` 这两个纯函数 —— 纯函数全对、手势没装上，
 * 表现就是「滑了完全没反应」。v1.2.10 正是这样：`playerSwipeToSwitch` 里
 * `pointerInput` 套了两层，里层的 Modifier 构造出来就丢了，没有任何节点承载手势，
 * 于是判据一次都没被执行过，而单测、lint、编译、打包全都不会报。
 *
 * 场景刻意贴近真机：父级挂手势、子级是 `verticalScroll` 的歌词列 ——
 * 这样才能验证「纵向滚动容器吃掉事件」不会把横向手势一起吃掉。
 *
 * ⚠️ Robolectric 三个坑见 [LibraryDrawerTest] 类注释：给窗口尺寸、开 NATIVE 图形模式、
 * 一个测试只调一次 `setContent`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerSwipeGestureTest {

    @get:Rule
    val compose = createComposeRule()

    /** 渲染「父级手势 + 子级纵向滚动」这个与播放页一致的层级。 */
    private fun renderPane(onSwipe: (SwipeSwitch) -> Unit) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pane")
                    .playerSwipeToSwitch(offset = offset, scope = scope, onSwipe = onSwipe)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    repeat(40) { Text("第 $it 行歌词") }
                }
            }
        }
    }

    @Test
    fun `左滑触发下一首`() {
        var got: SwipeSwitch? = null
        renderPane { got = it }

        compose.onNodeWithTag("pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(SwipeSwitch.NEXT, got)
    }

    @Test
    fun `右滑触发上一首`() {
        var got: SwipeSwitch? = null
        renderPane { got = it }

        compose.onNodeWithTag("pane").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(SwipeSwitch.PREVIOUS, got)
    }
}
