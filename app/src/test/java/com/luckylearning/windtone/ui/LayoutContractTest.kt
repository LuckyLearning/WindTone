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
     * 压住点不到；用户也明确表示不要双栏。于是车机两栏（`CarHomeScreen`，已删除）与平板双栏
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
            "MainScreen 不应再调用车载两栏布局（v1.1.14 起停用，CarHomeScreen 已删除）",
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

    /**
     * 车机首页（v3，2026-10-20）：左常驻导航 + 右内容区。
     *
     * 历史沿革（这条测试为什么改过两次）：
     *  · v1.1.14 因「底部按钮栏压住设置 / 像后台管理界面」否决双栏；
     *  · v1.1.19 改成单栏 + 顶部横向 Tab；
     *  · v3 重新采用左导航，但三处都处理过（导航收窄成卡、播放条只铺内容区、Dock 安全区照留）。
     * 所以现在锁定「左导航 + 车机组件接线」，并显式禁止旧的横向 Tab 回潮。
     */
    @Test
    fun carModeUsesLeftNavPlusContent() {
        val main = sourceFile("src/main/java/com/luckylearning/windtone/ui/MainScreen.kt").readText()
        listOf("CarNavRail(", "CarLibraryHeader(", "CarPlayerBar(").forEach { component ->
            assertTrue("MainScreen 的车载分支没有接 $component", main.contains(component))
        }
        assertFalse(
            "v3 已改用左导航，旧的横向 Tab 组件（CarViewTabs）不应再被引用",
            main.contains("CarViewTabs("),
        )
        assertTrue(
            "车机模式必须能立即改屏幕方向（requestedOrientation），否则开关等于没反应",
            main.contains("requestedOrientation"),
        )
        assertTrue(
            "车载分支必须由 player.carMode 驱动",
            Regex("""val carMode = player\.carMode""").containsMatchIn(main),
        )

        val car = sourceFile(
            "src/main/java/com/luckylearning/windtone/ui/CarLibraryScreen.kt",
        ).readText()
        assertTrue("车机左常驻导航必须存在（v3）", car.contains("internal fun CarNavRail("))
        assertFalse(
            "车机播放条不应再有歌词快捷入口（用户 2026-10-17 要求删掉）",
            car.contains("AppIcons.Lyrics"),
        )
        assertTrue(
            "车机触控目标必须 ≥76dp（行车中不瞄也能按中）",
            Regex("""CAR_TOUCH_TARGET: Dp = 76\.dp""").containsMatchIn(car),
        )
    }

    /**
     * v1.1.29 前台应用检测（无障碍服务）必须按规矩声明。
     *
     * 两个坑防住：
     *  1. 少了 `BIND_ACCESSIBILITY_SERVICE` 权限 —— 服务根本进不了系统辅助功能列表，
     *     用户想授权都找不到入口（表现为「开关开了但什么都没记」）；
     *  2. `canRetrieveWindowContent` 若被谁「顺手打开」—— 服务就开始读屏幕内容，
     *     那是信任底线，配置里写死 false，测试钉死。
     */
    @Test
    fun foregroundAppServiceIsDeclaredProperly() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "清单缺少 .overlay.ForegroundAppService 声明（v1.1.29 前台检测服务）",
            manifest.contains("android:name=\".overlay.ForegroundAppService\""),
        )
        val xml = sourceFile("src/main/res/xml/foreground_app_service.xml").readText()
        assertTrue("只应订阅窗口状态变化事件", xml.contains("typeWindowStateChanged"))
        assertTrue(
            "canRetrieveWindowContent 必须为 false —— 不读屏幕内容是这条通道的信任底线",
            xml.contains("canRetrieveWindowContent=\"false\""),
        )
    }

    /**
     * v1.1.29 回归：**悬浮窗服务必须订阅前台包名，不能只在判可见性时读一次 `.value`**。
     *
     * 真机反馈「名单勾了几个，但没起效，悬浮窗还在」。根因不是判定逻辑（`OverlayVisibility`
     * 单测全绿），而是**通道没接上**：检测端（无障碍服务 / UsageStats 轮询）只往
     * `ForegroundAppState.currentPkg` 写值，而状态接线（v1.1.37 重构后落在
     * `OverlayStateBinder` 的 combine 里）只有 content / editing / foreground /
     * preview / appearance 五路 ——
     * 「写包名」不产生任何一路的事件，于是切到名单应用后**没有任何东西触发重判**，
     * 只能等下一句歌词（暂停或歌词无变化时永不发生）碰巧重算。
     *
     * 这类缺陷的形态是「代码全对、就是没接上」，纯函数测试永远抓不到，
     * 所以在源码层钉一句：`currentPkg` 必须作为 combine 的输入出现。
     * ⚠️ 重构把接线搬去 [OverlayStateBinder] 时，这条断言的锚点要跟着搬家 ——
     * 否则测的是空文件，永远绿着骗人。
     */
    @Test
    fun overlayServiceSubscribesToForegroundPackage() {
        val src = sourceFile("src/main/java/com/luckylearning/windtone/overlay/OverlayStateBinder.kt").readText()
        assertTrue(
            "OverlayStateBinder 必须订阅 ForegroundAppState.currentPkg（否则按应用隐藏不生效）",
            src.contains("ForegroundAppState.currentPkg"),
        )
        assertTrue(
            "v1.2.43 起前台包名须 debounce 稳定后再进 combine（防 avc 等短闪狂闪）",
            src.contains("debounce(foregroundStableMs)") &&
                src.contains("combine(visuals, stableForegroundPkg)"),
        )
        assertFalse(
            "判可见性时不要再直接读 ForegroundAppState.currentPkg.value（那是快照，收不到后续变化）",
            src.contains("pkg = ForegroundAppState.currentPkg.value"),
        )
    }
}
