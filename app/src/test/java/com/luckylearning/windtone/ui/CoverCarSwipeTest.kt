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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面态 / 车机唱片区手势接线（2026-10-30 Spike）。
 *
 * 手机封面：左右上浮父级切面板；上下在 CoverPane 内切歌。
 * 车机：左右仍切歌（playerSwipeToSwitch）；唱片区不吃横向。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverCarSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    private class Recorder {
        var pane: SwipePane? = null
        var skip: SwipeSwitch? = null
        var toggleCount = 0
    }

    private fun renderCover(): Recorder {
        val rec = Recorder()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pane")
                    .playerSwipeToPane(offset = offset, scope = scope) { rec.pane = it }
            ) {
                CoverPane(
                    song = null,
                    isPlaying = false,
                    onToggle = { rec.toggleCount++ },
                    onSkip = { rec.skip = it },
                )
            }
        }
        return rec
    }

    @Test
    fun `封面态左滑上浮到父级并判为切封面`() {
        val rec = renderCover()
        compose.onNodeWithTag("pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SwipePane.SHOW_COVER, rec.pane)
        assertNull(rec.skip)
    }

    @Test
    fun `封面态右滑上浮到父级并判为切歌词`() {
        val rec = renderCover()
        compose.onNodeWithTag("pane").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(SwipePane.SHOW_LYRICS, rec.pane)
    }

    @Test
    fun `封面态上滑切下一首不切面板`() {
        val rec = renderCover()
        compose.onNodeWithTag("pane").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(SwipeSwitch.NEXT, rec.skip)
        assertNull(rec.pane)
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
                    .playerSwipeToSwitch(offset = offset, scope = scope) { rec.skip = it }
            ) {
                CarVinylPane(
                    song = null,
                    isPlaying = false,
                    onToggle = { rec.toggleCount++ },
                )
            }
        }
        compose.onNodeWithTag("pane").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SwipeSwitch.NEXT, rec.skip)
        assertEquals(0, rec.toggleCount)
    }
}
