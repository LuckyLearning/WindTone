package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * 排行榜在洛雪页里，页面祖先上挂着「右滑开抽屉」。
 * 带一点横向偏移的下滑必须仍然能把列表滚走，而不是被祖先吃掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RankingScrollTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mostlyVerticalDragStillScrolls() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            state = rememberLazyListState()
            MusicPlayerTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .drawerSwipe(enabled = true, onOpen = {}),
                ) {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize().testTag("ranks"),
                    ) {
                        items((0 until 40).toList()) { index ->
                            Text("榜单 $index", modifier = Modifier.height(56.dp))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("ranks").performTouchInput {
            val start = center
            down(start)
            // 横向 24px、纵向 500px：纵向更大，必须让给列表
            moveBy(androidx.compose.ui.geometry.Offset(24f, -500f))
            up()
        }
        compose.waitForIdle()
        assertTrue(
            "纵向为主的拖动之后列表应该离开顶部",
            state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0,
        )
    }
}
