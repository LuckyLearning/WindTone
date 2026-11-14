package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.luckylearning.windtone.data.PlayerPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 车机唱片区手势回归（v1.2.55）。
 *
 * 唱片区自身只挂双击（播放 / 暂停），横向拖动必须**上浮**给翻页容器 ——
 * 这一条在 v1.2.10 踩过坑（双层 `pointerInput` 把手势吃掉），所以单独留一个测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarVinylSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    private class Rec {
        var last: PageSwipe? = null
        var toggleCount = 0
    }

    private fun render(): Rec {
        val rec = Rec()
        compose.setContent {
            var pane by remember { mutableStateOf(PlayerPane.ART) }
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pager")
                    .playerSwipePages(
                        offset = offset,
                        scope = scope,
                        currentPane = { pane },
                        onSettle = { result ->
                            rec.last = result
                            if (result is PageSwipe.To) pane = result.target
                        },
                    )
            ) {
                CarVinylPane(
                    song = null,
                    isPlaying = false,
                    onToggle = { rec.toggleCount++ },
                    // 真实调用点会传 fillMaxHeight/weight；测试里必须给满尺寸，
                    // 否则 BoxWithConstraints 量到 0，双击落不到唱片上。
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return rec
    }

    @Test
    fun `唱片区左滑上浮给翻页容器`() {
        val rec = render()
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertNotNull("横向拖动应被翻页容器接住", rec.last)
        assertEquals(0, rec.toggleCount)
    }

    @Test
    fun `双击唱片切换播放暂停`() {
        val rec = render()
        compose.onNodeWithTag("pager").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(1, rec.toggleCount)
    }
}
