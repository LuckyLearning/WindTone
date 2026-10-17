package com.luckylearning.windtone.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 布局契约：**禁止 `fillMaxSize()` 紧接着 `.width(...)` / `.height(...)`**。
 *
 * 起因（v1.1.13 的真机事故，2026-10-17）：宽屏双栏里那条分隔线写成了
 * `Modifier.fillMaxSize().width(1.dp)`。`fillMaxSize()` 是
 * `fillMaxWidth() + fillMaxHeight()`，所以它在 `Row` 里把**整行宽度**占满了；
 * 而 `Row` 只把「剩下的宽度」分给 `weight(...)` 的兄弟节点 —— 剩余为 0，
 * 于是左列表与右播放页的宽度双双变成 0：整屏只剩背景色。
 *
 * 表现：车机（1920×1080 / density 1.5 ⇒ 1280dp 宽）和平板（≥840dp）**打开全黑**，
 * 手机上一切正常 —— 因为双栏分支只在 `widthDp ≥ 840` 时才走。黑得很彻底，
 * 连顶栏文字都没有，看起来像「渲染坏了」，实际是布局把宽度吃光了。
 *
 * 为什么用源码扫描而不是渲染测试：`MainScreen` 依赖四个构造器私有的 ViewModel，
 * 在单测里没法拼出真身；而这条规则的坏处是**结构性**的（写了就必然 0 宽），
 * 扫一眼源码就能可靠拦住，比造一堆假 VM 更划算。
 * （真实渲染结果在模拟器上按 1920×1080 / 240dpi 复现并验证过，见 docs/AI开发指南.md §13。）
 */
class LayoutContractTest {

    private fun sourceFile(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("找不到 $relative（当前目录 ${File(".").absolutePath}）")

    private fun sourceDir(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
            ?: error("找不到目录 $relative（当前目录 ${File(".").absolutePath}）")

    private fun uiSources(): List<File> =
        sourceDir("src/main/java/com/luckylearning/windtone/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun noFillMaxSizeFollowedByFixedSize() {
        // 允许中间有换行/空白：`fillMaxSize()\n    .width(`
        val pattern = Regex("""fillMaxSize\(\s*\)\s*\.\s*(width|height)\s*\(""")
        val offenders = uiSources().mapNotNull { file ->
            pattern.find(file.readText())?.let { m ->
                "${file.name} → ${m.value.replace(Regex("\\s+"), "")}"
            }
        }
        assertTrue(
            "fillMaxSize() 后面接 width/height 会把同级 weight 挤成 0 宽/0 高" +
                "（车机黑屏就是这么来的）。要单轴填满请用 fillMaxWidth / fillMaxHeight：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * v1.1.14：双栏整体停用。
     *
     * 车机（比亚迪宋 L DM-i）实测两条问题：左侧导航底部的「设置」被车机固定按钮栏
     * 压住点不到；用户也明确表示不要双栏。于是车机两栏（[CarHomeScreen]）与平板双栏
     * 一起下线，统一走单栏 + 抽屉导航。
     *
     * 这条测试防「顺手恢复」：MainScreen 里一旦又出现分隔线或车载两栏调用，
     * 必须同时恢复上一版那条 `fillMaxHeight().width(1.dp)` 的断言，否则黑屏会回来。
     */
    @Test
    fun noTwoPaneLayout() {
        val source = sourceFile("src/main/java/com/luckylearning/windtone/ui/MainScreen.kt").readText()
        assertFalse(
            "MainScreen 又出现 VerticalDivider —— 恢复双栏前先读 docs/版本历史.md 的 v1.1.13/v1.1.14 两条",
            source.contains("VerticalDivider("),
        )
        assertFalse(
            "MainScreen 不应再调用车载两栏布局 CarHomeScreen（v1.1.14 起停用）",
            source.contains("CarHomeScreen("),
        )
    }

    /**
     * 底部必须给车机 Dock 留死高度：DiLink 的按钮栏不在 insets 里上报（navigationBars = 0），
     * 只在 `Box` 上套 `navigationBarsPadding()` 没用，「设置」照样被压住。
     */
    @Test
    fun mainScreenReservesCarDockHeight() {
        val source = sourceFile("src/main/java/com/luckylearning/windtone/ui/MainScreen.kt").readText()
        assertTrue("MainScreen 缺少车机 Dock 保底高度常量", source.contains("CAR_DOCK_RESERVED_HEIGHT"))
        assertTrue(
            "根布局必须把内容抬到 bottomSafe 之上（padding(bottom = bottomSafe)）",
            Regex("""padding\(bottom = bottomSafe\)""").containsMatchIn(source),
        )
    }
}
