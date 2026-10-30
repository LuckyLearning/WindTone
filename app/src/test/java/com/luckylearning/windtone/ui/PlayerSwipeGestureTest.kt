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
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 播放页中部手势接线回归（2026-10-30 Spike）。
 *
 * 手机：左右 → 歌词/封面；上下 → 切歌（本测试封面直滑路径）。
 * 歌词滚动 vs 切歌：边缘策略见 [VerticalSkipConnection]，此处用无滚动子级验直滑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerSwipeGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private class Rec {
        var pane: SwipePane? = null
        var skip: SwipeSwitch? = null
    }

    /** 父级：横切面板 + 纵切歌；子级可带纵向滚动（验横滑不被吃掉）。 */
    private fun renderPane(scrollChild: Boolean): Rec {
        val rec = Rec()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pane")
                    .playerSwipeToPane(offset = offset, scope = scope) { rec.pane = it }
                    .playerVerticalSkipDirect { rec.skip = it }
            ) {
                if (scrollChild) {
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
        return rec
    }

    @Test
    fun `左滑切换到封面`() {
        val rec = renderPane(scrollChild = true)
        compose.onNodeWithTag("pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SwipePane.SHOW_COVER, rec.pane)
        assertNull(rec.skip)
    }

    @Test
    fun `右滑切换到歌词`() {
        val rec = renderPane(scrollChild = true)
        compose.onNodeWithTag("pane").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(SwipePane.SHOW_LYRICS, rec.pane)
        assertNull(rec.skip)
    }

    @Test
    fun `无滚动子级时上滑切下一首`() {
        val rec = renderPane(scrollChild = false)
        compose.onNodeWithTag("pane").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(SwipeSwitch.NEXT, rec.skip)
        assertNull(rec.pane)
    }

    @Test
    fun `无滚动子级时下滑切上一首`() {
        val rec = renderPane(scrollChild = false)
        compose.onNodeWithTag("pane").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(SwipeSwitch.PREVIOUS, rec.skip)
        assertNull(rec.pane)
    }
}
