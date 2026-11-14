package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放页三页「视觉锚点」的接线契约（v1.2.56 闪烁修复）。
 *
 * ## 为什么这类接线值得单独守
 *
 * 三页相位在 `Modifier.offset{}` 的 lambda 里算，那是 **layout 阶段**；页号持久化
 * 走 StateFlow → collectAsState → **下一帧重组**。翻页动画结束时位移归零当帧生效，
 * 如果锚点读的是 `state.playerPane`，中间就会画出「位移已归零、锚点还是旧页」的
 * 一帧旧页回闪 —— 真机表现是「左右滑切页时屏幕闪一下」。
 *
 * 这类问题**照常编译、照常跑过所有手势单测**：判据、动画、状态链路全对，
 * 只是两个 snapshot 写入差了一帧。所以除了 [PlayerSwipeTest] 的相位恒等式，
 * 还要用源码扫描锁住生产接线的三个不可拆条件（写法错了就红，不依赖真机）。
 */
class PlayerPaneAnchorContractTest {

    private fun source(): String =
        listOf(
            File("src/main/java/com/luckylearning/windtone/ui/PlayerScreen.kt"),
            File("app/src/main/java/com/luckylearning/windtone/ui/PlayerScreen.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("找不到 PlayerScreen.kt（当前目录 ${File(".").absolutePath}）")

    /** 取局部函数体，避免把文件里其它同名调用算进来。 */
    private fun selectPaneBody(): String {
        val text = source()
        val start = text.indexOf("fun selectPane(target: PlayerPane) {")
        assertTrue("必须保留统一的改页入口 selectPane", start >= 0)
        return text.substring(start, text.indexOf('}', start) + 1)
    }

    @Test
    fun `三页相位读本地视觉锚点而不是组合期页号`() {
        val text = source()
        assertTrue(
            "offset{} 必须用 visualPaneState 当锚点（layout 阶段可读、同帧可写）",
            text.contains("anchorIndex = visualPaneState.intValue"),
        )
        assertFalse(
            "把锚点改回 currentPane（组合期值）会重新引入跨帧回闪",
            text.contains("(page.index - currentPane.index)"),
        )
        assertFalse(
            "锚点不能直接用 StateFlow 的 state.playerPane",
            text.contains("anchorIndex = state.playerPane"),
        )
    }

    @Test
    fun `手势判据读视觉锚点的最新值`() {
        val text = source()
        assertTrue(
            "currentPane lambda 必须读 visualPaneState.intValue（而不是捕获值参数）",
            text.contains("currentPane = { PlayerPane.of(visualPaneState.intValue) }"),
        )
        assertFalse(
            "currentPane lambda 不能读 state.playerPane",
            text.contains("currentPane = { PlayerPane.of(state.playerPane) }"),
        )
    }

    @Test
    fun `selectPane 先写视觉锚点再落盘`() {
        val body = selectPaneBody()
        val write = body.indexOf("visualPaneState.intValue = target.index")
        val persist = body.indexOf("onPlayerPane(target)")
        assertTrue("selectPane 必须同步写视觉锚点", write >= 0)
        assertTrue("selectPane 必须落盘", persist >= 0)
        assertTrue(
            "写锚点必须在落盘之前：落盘会触发下一帧重组，晚写就会闪一帧旧页",
            write < persist,
        )
    }

    @Test
    fun `所有改页入口都收敛到 selectPane`() {
        val text = source()
        val start = text.indexOf("onSettle = { result ->")
        assertTrue("onSettle 接线必须存在", start >= 0)
        val end = text.indexOf("PageSwipe.Stay -> Unit", start)
        assertTrue("onSettle 分支不完整", end > start)
        val settle = text.substring(start, end)
        assertFalse("翻页提交不能绕过视觉锚点直接改持久化页号", settle.contains("onPlayerPane("))
        assertTrue(settle.contains("selectPane(result.target)"))
        assertTrue(settle.contains("selectPane(PlayerPane.ART)"))

        // 全文件只允许 selectPane 自己调 onPlayerPane。顶栏、页码点、手势三个入口
        // 都必须收敛过去；以后新增外部改页入口时，这条会提醒先同步写视觉锚点。
        assertEquals(
            "存在绕过 selectPane 的直接改页调用（会重新引入跨帧回闪）",
            1,
            Regex("onPlayerPane\\(").findAll(text).count(),
        )
        assertTrue(
            "顶栏 / 页码点 / 手势共四处改页入口都要走 selectPane",
            Regex("selectPane\\(").findAll(text).count() >= 5,
        )
    }
}
