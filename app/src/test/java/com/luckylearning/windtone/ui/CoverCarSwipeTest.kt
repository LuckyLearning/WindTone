package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 * 封面态 / 车机唱片区「横向滑动切歌交给父级」的接线回归（2026-10-28）。
 *
 * 背景：这两块以前各有一套横向判据 —— 手机封面用固定 90px 阈值 + `consume()` 全部拖动，
 * 车机唱片区干脆没有横滑。统一之后横向一律上浮给 `PlayerScreen` 中部那层
 * [playerSwipeToSwitch]，两处都不再自己判方向。**能不能上浮**取决于子级的手势是否消费
 * 了移动事件：封面若还用 `detectDragGestures` 并 `consume()`，父级就永远收不到。
 * 这条链路纯靠代码审查很容易看着"对"，所以这里真滑一次钉住。
 *
 * ⚠️ Robolectric 三个坑见 [LibraryDrawerTest] 类注释：给窗口尺寸、开 NATIVE 图形模式、
 * 一个测试只调一次 `setContent`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverCarSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * 复刻 `PlayerScreen` 的真实层级：父级挂统一手势，子级是封面（或车机唱片）。
     *
     * @return 记录「父级收到的方向」与「音量增量累计」的容器
     */
    private fun renderCover(): Recorder {
        val rec = Recorder()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pane")
                    .playerSwipeToSwitch(offset = offset, scope = scope) { rec.swipe = it }
            ) {
                CoverPane(
                    song = null,
                    isPlaying = false,
                    onToggle = { rec.toggleCount++ },
                    onVolumeDelta = { rec.volume += it },
                )
            }
        }
        return rec
    }

    private class Recorder {
        var swipe: SwipeSwitch? = null
        var volume = 0f
        var toggleCount = 0
    }

    @Test
    fun `封面态左滑上浮到父级并判为下一首`() {
        val rec = renderCover()

        compose.onNodeWithTag("pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(SwipeSwitch.NEXT, rec.swipe)
        // 横向滑动不该顺手改音量
        assertEquals(0f, rec.volume, 0.001f)
    }

    @Test
    fun `封面态右滑上浮到父级并判为上一首`() {
        val rec = renderCover()

        compose.onNodeWithTag("pane").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(SwipeSwitch.PREVIOUS, rec.swipe)
    }

    @Test
    fun `封面态纵滑只调音量不切歌`() {
        val rec = renderCover()

        compose.onNodeWithTag("pane").performTouchInput { swipeUp() }
        compose.waitForIdle()

        // 向上滑 = 增大音量，增量为正
        assertTrue("向上滑应给出正音量增量，实际 ${rec.volume}", rec.volume > 0f)
        // 纵向手势不能顺带切歌（方向互斥）
        assertNull(rec.swipe)
    }

    @Test
    fun `车机唱片区左滑上浮到父级并判为下一首`() {
        val rec = Recorder()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pane")
                    .playerSwipeToSwitch(offset = offset, scope = scope) { rec.swipe = it }
            ) {
                // 车机右侧唱片：只挂双击，不该吃掉横向拖动
                CarVinylPane(
                    song = null,
                    isPlaying = false,
                    onToggle = { rec.toggleCount++ },
                )
            }
        }

        compose.onNodeWithTag("pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(SwipeSwitch.NEXT, rec.swipe)
        // 拖动不该被当成双击
        assertEquals(0, rec.toggleCount)
    }
}
