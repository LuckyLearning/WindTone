package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.luckylearning.windtone.data.online.OnlineSonglists
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 歌单骨架停住的回归。
 *
 * 1.3.4 起加载协程在 `loading = true`（网格已被骨架换掉）时调用 `scrollToItem`。
 * `LazyGridState.scroll` 会先等网格的第一次布局，而那次布局要等 `loading` 变 false。
 * 协程停在滚动上，`finally` 不跑，骨架就不再撤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonglistLoadTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playlistDataReplacesTheSkeleton() {
        val gate = CompletableDeferred<Unit>()
        val state = SonglistUiState()
        compose.setContent {
            val grid = rememberLazyGridState()
            MusicPlayerTheme {
                SonglistPane(
                    state = state,
                    gridState = grid,
                    onOpen = {},
                    gateway = SonglistGateway(
                        tags = {
                            gate.await()
                            listOf(
                                OnlineSonglists.TagGroup(
                                    "热门",
                                    listOf(OnlineSonglists.Tag("9", "流行")),
                                ),
                            )
                        },
                        list = { _, _, _ ->
                            OnlineSonglists.SheetPage(
                                listOf(OnlineSonglists.Sheet("kw", "1", "夏日歌单", author = "听风")),
                                1,
                                false,
                            )
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("夏日歌单").assertDoesNotExist()
        gate.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { !state.loading && state.sheets.isNotEmpty() }
        compose.onNodeWithText("夏日歌单").assertIsDisplayed()
        assertTrue(state.loadedKey == "kw|9")
    }

    @Test
    fun timedOutListShowsRetryInsteadOfStayingOnTheSkeleton() {
        val state = SonglistUiState()
        compose.setContent {
            val grid = rememberLazyGridState()
            MusicPlayerTheme {
                SonglistPane(
                    state = state,
                    gridState = grid,
                    onOpen = {},
                    gateway = SonglistGateway(
                        tags = {
                            listOf(
                                OnlineSonglists.TagGroup(
                                    "热门",
                                    listOf(OnlineSonglists.Tag("9", "流行")),
                                ),
                            )
                        },
                        // 比页面的 12 秒上限更久。测试时钟是虚拟的，不真等 12 秒。
                        list = { _, _, _ ->
                            delay(60_000)
                            OnlineSonglists.SheetPage(emptyList(), 1, false)
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) { state.failed && !state.loading }
        assertTrue(state.sheets.isEmpty())
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun scrollingToTheEndLoadsTheNextPageWithoutAButtonOrDuplicates() {
        val requested = mutableListOf<Int>()
        val state = SonglistUiState()
        compose.setContent {
            val grid = rememberLazyGridState()
            MusicPlayerTheme {
                SonglistPane(
                    state = state,
                    gridState = grid,
                    onOpen = {},
                    gateway = SonglistGateway(
                        tags = {
                            listOf(
                                OnlineSonglists.TagGroup(
                                    "热门",
                                    listOf(OnlineSonglists.Tag("9", "流行")),
                                ),
                            )
                        },
                        list = { _, _, page ->
                            synchronized(requested) { requested.add(page) }
                            when (page) {
                                1 -> OnlineSonglists.SheetPage(
                                    (1..18).map { sheet(it) },
                                    page,
                                    true,
                                )
                                2 -> OnlineSonglists.SheetPage(
                                    // 1 是重复项，不应再出现一行
                                    listOf(sheet(1), sheet(19), sheet(20), sheet(21)),
                                    page,
                                    false,
                                )
                                else -> OnlineSonglists.SheetPage(emptyList(), page, false)
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { state.sheets.size == 18 && !state.loading }
        assertTrue(compose.onAllNodesWithText("加载更多").fetchSemanticsNodes().isEmpty())
        repeat(10) {
            if (state.sheets.size >= 21) return@repeat
            compose.onNodeWithTag("sheet-grid").performTouchInput {
                down(Offset(center.x, center.y))
                moveBy(Offset(0f, -700f))
                up()
            }
            compose.waitForIdle()
        }
        compose.waitUntil(timeoutMillis = 5_000) { state.sheets.size == 21 && !state.hasMore && !state.loadingMore }
        assertTrue(compose.onAllNodesWithText("加载更多").fetchSemanticsNodes().isEmpty())
        assertTrue(state.sheets.map { it.id } == (1..21).map { it.toString() })
        synchronized(requested) {
            assertTrue("应请求过第 2 页：$requested", requested.contains(2))
            assertTrue("没有下一页时不应再请求：$requested", requested.none { it >= 3 })
        }
        val callsAfterEnd = synchronized(requested) { requested.size }
        repeat(3) {
            compose.onNodeWithTag("sheet-grid").performTouchInput {
                down(Offset(center.x, center.y))
                moveBy(Offset(0f, -400f))
                up()
            }
            compose.waitForIdle()
        }
        synchronized(requested) {
            assertTrue(
                "到底之后再滑不应再发请求 before=$callsAfterEnd after=$requested",
                requested.size == callsAfterEnd,
            )
        }
    }

    /**
     * 分类接口超时不能记成「已加载」。
     *
     * 以前 `withTimeoutOrNull(...).orEmpty()` 之后无条件写 `tagsLoadedFor`，
     * 返回本页时 effect 直接短路，这家平台的分类就再也取不到，只剩「全部」默认歌单。
     */
    @Test
    fun timedOutTagsDoNotLockThePlatformIntoAnEmptyCategoryList() {
        val state = SonglistUiState()
        compose.setContent {
            val grid = rememberLazyGridState()
            MusicPlayerTheme {
                SonglistPane(
                    state = state,
                    gridState = grid,
                    onOpen = {},
                    gateway = SonglistGateway(
                        tags = {
                            // 超过页面的 10 秒上限。测试时钟是虚拟的，不真等。
                            delay(60_000)
                            emptyList()
                        },
                        list = { _, _, page -> OnlineSonglists.SheetPage(emptyList(), page, false) },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) { state.failed && !state.loading }
        assertNull("分类超时不能记成已加载，否则不再重取", state.tagsLoadedFor)
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    /**
     * 数据层把网络失败收成带 `error` 的页（而不是「真没有歌单」），
     * 界面必须据此给重试，不能只显示一句「这个分类暂时没有歌单」。
     */
    @Test
    fun failedPageThatReturnsImmediatelyStillOffersRetry() {
        val state = SonglistUiState()
        val recovered = AtomicBoolean(false)
        compose.setContent {
            val grid = rememberLazyGridState()
            MusicPlayerTheme {
                SonglistPane(
                    state = state,
                    gridState = grid,
                    onOpen = {},
                    gateway = SonglistGateway(
                        tags = {
                            listOf(
                                OnlineSonglists.TagGroup(
                                    "热门",
                                    listOf(OnlineSonglists.Tag("9", "流行")),
                                ),
                            )
                        },
                        list = { _, _, page ->
                            if (!recovered.get()) {
                                // 数据层把网络失败收成带 error 的页，不是「真没有歌单」
                                OnlineSonglists.SheetPage(emptyList(), page, false, error = "network")
                            } else {
                                OnlineSonglists.SheetPage(
                                    listOf(OnlineSonglists.Sheet("kw", "1", "恢复的歌单")),
                                    page,
                                    false,
                                )
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { state.failed && !state.loading }
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.runOnIdle { recovered.set(true) }
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { state.sheets.isNotEmpty() && !state.loading }
        compose.onNodeWithText("恢复的歌单").assertIsDisplayed()
    }

    private fun sheet(id: Int) = OnlineSonglists.Sheet(
        source = "kw",
        id = id.toString(),
        name = "歌单 $id",
        cover = "https://img1.kuwo.cn/$id.jpg",
    )
}
