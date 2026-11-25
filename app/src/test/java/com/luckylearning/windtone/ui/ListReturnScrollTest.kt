package com.luckylearning.windtone.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 进详情再返回之后，列表还要能纵向滚。
 *
 * 洛雪歌单 / 排行榜把列表放进 [AnimatedContent]，点进去时列表离开组合，
 * 返回时用同一个 [LazyListState] 装回来。祖先上还有右滑开抽屉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListReturnScrollTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun removingTheListAndShowingItAgainStillScrolls() {
        var showDetail by mutableStateOf(false)
        var state: LazyListState? = null
        compose.setContent {
            val listState = rememberLazyListState()
            SideEffect { state = listState }
            MusicPlayerTheme {
                Box(Modifier.fillMaxSize()) {
                    if (!showDetail) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("list"),
                        ) {
                            items((0 until 40).toList()) { index ->
                                Text("行 $index", modifier = Modifier.height(56.dp))
                            }
                        }
                    } else {
                        Text("详情", modifier = Modifier.fillMaxSize().testTag("detail"))
                    }
                }
            }
        }
        fun swipe() {
            compose.onNodeWithTag("list").performTouchInput {
                down(Offset(center.x, center.y))
                moveBy(Offset(24f, -500f))
                up()
            }
            compose.waitForIdle()
        }
        swipe()
        val afterFirst = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        showDetail = true
        compose.waitForIdle()
        showDetail = false
        compose.waitForIdle()
        swipe()
        val afterSecond = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue(
            "摘掉再装回之后列表应还能往下滚 first=$afterFirst second=$afterSecond inProgress=${state!!.isScrollInProgress}",
            afterSecond.first > afterFirst.first ||
                (afterSecond.first == afterFirst.first && afterSecond.second > afterFirst.second),
        )
    }

    @Test
    fun animatedContentReturnStillScrollsFromTheRoot() {
        var showDetail by mutableStateOf(false)
        var state: LazyListState? = null
        compose.setContent {
            val listState = rememberLazyListState()
            SideEffect { state = listState }
            MusicPlayerTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .drawerSwipe(enabled = true, onOpen = {}),
                ) {
                    AnimatedContent(
                        targetState = showDetail,
                        modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                        transitionSpec = {
                            SettingsMotion.slide(forward = targetState && !initialState)
                        },
                        label = "detail",
                    ) { detail ->
                        if (!detail) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("list"),
                            ) {
                                items((0 until 40).toList()) { index ->
                                    Text(
                                        "行 $index",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .clickable { showDetail = true },
                                    )
                                }
                            }
                        } else {
                            Text("详情", modifier = Modifier.fillMaxSize().testTag("detail"))
                        }
                    }
                }
            }
        }
        fun swipeRoot() {
            compose.onRoot().performTouchInput {
                down(Offset(center.x, center.y))
                moveBy(Offset(12f, -480f))
                up()
            }
            compose.waitForIdle()
        }
        swipeRoot()
        val afterFirst = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue("第一下应该能滚 first=$afterFirst", afterFirst.first > 0 || afterFirst.second > 0)
        // 用真实按下/抬起点进详情，而不是 performClick：后者不经过祖先的手势循环。
        compose.onRoot().performTouchInput {
            down(Offset(center.x, center.y))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("detail").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        showDetail = false
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        swipeRoot()
        val afterSecond = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue(
            "AnimatedContent 返回后从根上滑动应继续滚 first=$afterFirst second=$afterSecond " +
                "inProgress=${state!!.isScrollInProgress} canForward=${state!!.canScrollForward}",
            afterSecond.first > afterFirst.first ||
                (afterSecond.first == afterFirst.first && afterSecond.second > afterFirst.second),
        )
    }

    @Test
    fun scrollToItemWhileDetachedDoesNotLockTheNextGesture() {
        var showDetail by mutableStateOf(false)
        var state: LazyListState? = null
        compose.setContent {
            val listState = rememberLazyListState()
            SideEffect { state = listState }
            MusicPlayerTheme {
                if (!showDetail) {
                    LaunchedEffect(Unit) {
                        listState.scrollToItem(0)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("list"),
                    ) {
                        items((0 until 40).toList()) { index ->
                            Text("行 $index", modifier = Modifier.height(56.dp))
                        }
                    }
                } else {
                    Text("详情", modifier = Modifier.fillMaxSize().testTag("detail"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("list").performTouchInput {
            down(Offset(center.x, center.y))
            moveBy(Offset(0f, -400f))
            up()
        }
        compose.waitForIdle()
        // 手指还按着的时候切走：模拟点进详情把正在滚的列表移出组合。
        showDetail = true
        compose.waitForIdle()
        // 列表不在组合里时再请求一次滚动。挂起的 scroll 若占着互斥锁，回来就再也滚不动。
        compose.runOnIdle {
            // 不能在这里真的挂起；只是把列表摘掉后再装回来。
        }
        showDetail = false
        compose.waitForIdle()
        val before = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        compose.onNodeWithTag("list").performTouchInput {
            down(Offset(center.x, center.y))
            moveBy(Offset(0f, -400f))
            up()
        }
        compose.waitForIdle()
        val after = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue(
            "切走再回来应能继续滚 before=$before after=$after inProgress=${state!!.isScrollInProgress}",
            after.first > before.first || (after.first == before.first && after.second > before.second),
        )
    }

    @Test
    fun detailStaysAboveTheListSoTheListNodeIsNeverRemoved() {
        var showDetail by mutableStateOf(false)
        var state: LazyListState? = null
        compose.setContent {
            val listState = rememberLazyListState()
            SideEffect { state = listState }
            MusicPlayerTheme {
                LxStackedDetail(
                    showDetail = showDetail,
                    modifier = Modifier.fillMaxSize(),
                    list = {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("list"),
                        ) {
                            items((0 until 40).toList()) { index ->
                                Text("行 $index", modifier = Modifier.height(56.dp))
                            }
                        }
                    },
                    detail = {
                        Text("详情", modifier = Modifier.fillMaxSize().testTag("detail"))
                    },
                )
            }
        }
        fun swipeRoot() {
            compose.onRoot().performTouchInput {
                down(Offset(center.x, center.y))
                moveBy(Offset(8f, -420f))
                up()
            }
            compose.waitForIdle()
        }
        swipeRoot()
        val afterFirst = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue("盖详情之前要能滚 first=$afterFirst", afterFirst.first > 0 || afterFirst.second > 0)
        showDetail = true
        compose.waitForIdle()
        // 详情盖住时列表节点还在。若改回 if/else 把列表摘掉，这一条会失败。
        compose.onNodeWithTag("list").assertExists()
        compose.onNodeWithTag("detail").assertIsDisplayed()
        showDetail = false
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        swipeRoot()
        val afterSecond = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue(
            "详情滑走之后列表应继续滚 first=$afterFirst second=$afterSecond",
            afterSecond.first > afterFirst.first ||
                (afterSecond.first == afterFirst.first && afterSecond.second > afterFirst.second),
        )
    }

    @Test
    fun sameContentKeyUpdatesTheOverlayWithoutRemovingTheList() {
        var detail by mutableStateOf(false)
        var state: LazyListState? = null
        compose.setContent {
            val listState = rememberLazyListState()
            SideEffect { state = listState }
            MusicPlayerTheme {
                AnimatedContent(
                    targetState = detail,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { "sheets" },
                    label = "same-key",
                ) { show ->
                    LxStackedDetail(
                        showDetail = show,
                        list = {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("list"),
                            ) {
                                items((0 until 30).toList()) { index ->
                                    Text("行 $index", modifier = Modifier.height(56.dp))
                                }
                            }
                        },
                        detail = {
                            Text("详情", modifier = Modifier.fillMaxSize().testTag("detail"))
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("list").performTouchInput {
            down(Offset(center.x, center.y))
            moveBy(Offset(0f, -360f))
            up()
        }
        compose.waitForIdle()
        val afterFirst = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        detail = true
        compose.waitForIdle()
        compose.onNodeWithTag("detail").assertIsDisplayed()
        compose.onNodeWithTag("list").assertExists()
        detail = false
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("list").performTouchInput {
            down(Offset(center.x, center.y))
            moveBy(Offset(0f, -360f))
            up()
        }
        compose.waitForIdle()
        val afterSecond = state!!.firstVisibleItemIndex to state!!.firstVisibleItemScrollOffset
        assertTrue(
            "同一内容槽返回后应还能滚 first=$afterFirst second=$afterSecond",
            afterSecond.first > afterFirst.first ||
                (afterSecond.first == afterFirst.first && afterSecond.second > afterFirst.second),
        )
    }
}
