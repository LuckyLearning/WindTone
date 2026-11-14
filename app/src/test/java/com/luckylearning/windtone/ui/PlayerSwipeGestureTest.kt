package com.luckylearning.windtone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.luckylearning.windtone.data.PlayerPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 播放页横滑接线回归（v1.2.55 三页）。
 *
 * 覆盖四件事：中间页左右各翻一页、首尾页再往外滑切成切歌、纵向滑动什么都不做。
 * 判据本身（阈值 / 甩速 / 冲突）在 [PlayerSwipeTest] 里逐条对质，这里只验「接线」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerSwipeGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private class Rec {
        var last: PageSwipe? = null
    }

    private fun render(start: PlayerPane): Rec {
        val rec = Rec()
        compose.setContent {
            var pane by remember { mutableStateOf(start) }
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
            )
        }
        return rec
    }

    @Test
    fun `图片页左滑翻到歌词页`() {
        val rec = render(PlayerPane.ART)
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(PageSwipe.To(PlayerPane.LYRICS), rec.last)
    }

    @Test
    fun `图片页右滑翻到信息页`() {
        val rec = render(PlayerPane.ART)
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(PageSwipe.To(PlayerPane.INFO), rec.last)
    }

    @Test
    fun `歌词页左滑切下一首`() {
        val rec = render(PlayerPane.LYRICS)
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(PageSwipe.Skip(SwipeSwitch.NEXT), rec.last)
    }

    @Test
    fun `信息页右滑切上一首`() {
        val rec = render(PlayerPane.INFO)
        compose.onNodeWithTag("pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(PageSwipe.Skip(SwipeSwitch.PREVIOUS), rec.last)
    }

    @Test
    fun `上下滑动不再切歌也不再翻页`() {
        val rec = render(PlayerPane.ART)
        compose.onNodeWithTag("pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("pager").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertNull(rec.last)
    }

    /**
     * 连续两次同向滑动必须按**翻页后**的页号判定（v1.2.55 首发回归）。
     *
     * 生产代码传的是 `{ PlayerPane.of(state.playerPane) }`，`state` 是普通值参数；
     * `pointerInput(Unit)` 只执行一次，捕获到第一次组合的 lambda 就再也不更新 ——
     * 于是停在歌词页后无论怎么滑都按旧页算，左滑永远「切下一首」，三页翻不过去。
     * 这里刻意用「值快照」的写法复刻那个调用方，走 [rememberUpdatedState] 的兜底。
     */
    @Test
    fun `翻页后下一次滑动按新页号判定`() {
        val rec = Rec()
        compose.setContent {
            var pane by remember { mutableStateOf(PlayerPane.ART) }
            // ⚠️ 关键：普通局部值（不是 State 读），模拟生产代码里的 state.playerPane
            val snapshot = pane
            val scope = rememberCoroutineScope()
            val offset = remember { Animatable(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("pager")
                    .playerSwipePages(
                        offset = offset,
                        scope = scope,
                        currentPane = { snapshot },
                        onSettle = { result ->
                            rec.last = result
                            if (result is PageSwipe.To) pane = result.target
                        },
                    )
            )
        }
        // 第一次：图片页 → 歌词页
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(PageSwipe.To(PlayerPane.LYRICS), rec.last)

        // 第二次：已在歌词页，再左滑就该切下一首（若页号是旧的，这里仍是 To）
        rec.last = null
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(PageSwipe.Skip(SwipeSwitch.NEXT), rec.last)
    }
}
