package com.luckylearning.windtone.overlay

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.ui.AppStrings
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 排障报告的版式与结论（v1.1.82；i18n 第 23 批后正文走 `fp_*`）。
 *
 * 为什么要测这份文本：车机问题只能靠用户把报告贴回来，**少一行关键事实**就会
 * 多一轮往返。所以这里把「结论段必须回答哪三件事」「不可解析的候选必须打叉」
 * 「设置类应用为空必须显式说明」逐条钉住。
 *
 * 取数部分（[ForegroundProbe.collect]）依赖真实 PM/系统设置，只能在真机验；
 * 版式与推荐逻辑是纯函数，放在这里锁（Robolectric zh-rCN 对齐中文断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN")
class ForegroundProbeTest {

    @Before
    fun setUp() {
        AppStrings.init(ApplicationProvider.getApplicationContext())
    }

    private fun facts(
        accessibilityPageResolvable: Boolean = false,
        usageAccessPageResolvable: Boolean = true,
        accessibilityServiceEnabled: Boolean = false,
        usageStatsGranted: Boolean = false,
        settingsApps: List<ForegroundProbe.SettingsApp> = emptyList(),
        currentForeground: String? = null,
        liveForeground: String? = null,
        hiddenApps: List<String> = emptyList(),
        recentSwitches: List<Pair<String, Long>> = emptyList(),
        ignoringBatteryOptimizations: Boolean = false,
        powerSaveMode: Boolean = false,
        backgroundRestricted: Boolean? = false,
        runInBackgroundMode: String = "默认(3)",
        batteryCandidates: List<ForegroundProbe.Candidate> = emptyList(),
    ) = ForegroundProbe.Facts(
        collectedAt = 1_700_000_000_000L,
        manufacturer = "BYD",
        model = "DiLink 4.0",
        device = "byd",
        androidRelease = "10",
        sdkInt = 29,
        appPackage = "com.luckylearning.windtone",
        appVersion = "1.1.82",
        accessibilityPageResolvable = accessibilityPageResolvable,
        usageAccessPageResolvable = usageAccessPageResolvable,
        accessibilityServiceEnabled = accessibilityServiceEnabled,
        enabledAccessibilityServices = "",
        accessibilityMasterEnabled = false,
        serviceDeclared = true,
        usageStatsMode = "MODE_DEFAULT(3)",
        usageStatsGranted = usageStatsGranted,
        currentForeground = currentForeground,
        settingsApps = settingsApps,
        candidates = listOf(
            ForegroundProbe.Candidate("无障碍", "android.settings.ACCESSIBILITY_SETTINGS", accessibilityPageResolvable),
            ForegroundProbe.Candidate("使用情况", "android.settings.USAGE_ACCESS_SETTINGS", usageAccessPageResolvable),
        ),
        logcatReadable = false,
        liveForeground = liveForeground,
        hiddenApps = hiddenApps,
        recentSwitches = recentSwitches,
        ignoringBatteryOptimizations = ignoringBatteryOptimizations,
        powerSaveMode = powerSaveMode,
        backgroundRestricted = backgroundRestricted,
        runInBackgroundMode = runInBackgroundMode,
        batteryCandidates = batteryCandidates,
    )

    @Test
    fun `车机没有无障碍页时结论明确指向使用情况访问`() {
        val report = ForegroundProbe.buildReport(facts())
        assertTrue(report.contains("无障碍设置页：本机没有这个页面"))
        assertTrue(report.contains("使用情况访问：未授权"))
        assertTrue(report.contains("建议走哪条：去开使用情况访问"))
        // 已授权时直接给「就用它」的结论
        assertTrue(
            ForegroundProbe.buildReport(facts(usageStatsGranted = true))
                .contains("建议走哪条：使用情况访问"),
        )
    }

    @Test
    fun `无障碍已开启时优先推荐无障碍`() {
        val report = ForegroundProbe.buildReport(
            facts(accessibilityPageResolvable = true, accessibilityServiceEnabled = true),
        )
        assertTrue(report.contains("无障碍服务：已开启"))
        assertTrue(report.contains("建议走哪条：无障碍"))
    }

    @Test
    fun `两个页面都不存在时如实说明只能靠 adb`() {
        val report = ForegroundProbe.buildReport(
            facts(accessibilityPageResolvable = false, usageAccessPageResolvable = false),
        )
        assertTrue(report.contains("两个页面都被裁掉"))
        // 硬路子也要给全：命令里必须带本应用的服务组件名，且提示会覆盖原列表
        assertTrue(report.contains("adb shell settings put secure enabled_accessibility_services"))
        assertTrue(report.contains(ForegroundAppService::class.java.name))
        assertTrue(report.contains("覆盖"))
    }

    @Test
    fun `候选不可解析时打叉，可解析时标注可解析`() {
        val report = ForegroundProbe.buildReport(facts(usageAccessPageResolvable = true))
        assertTrue(report.contains("[  ×  ] 无障碍：android.settings.ACCESSIBILITY_SETTINGS"))
        assertTrue(report.contains("[可解析] 使用情况：android.settings.USAGE_ACCESS_SETTINGS"))
    }

    @Test
    fun `设置类应用为空时给出显式说明而不是留白`() {
        val report = ForegroundProbe.buildReport(facts())
        assertTrue(report.contains("从启动器里没找到设置类应用"))
    }

    @Test
    fun `设置类应用会列出 accessibility 相关 Activity`() {
        val report = ForegroundProbe.buildReport(
            facts(
                settingsApps = listOf(
                    ForegroundProbe.SettingsApp(
                        "com.android.settings",
                        "10",
                        listOf("com.android.settings.accessibility.AccessibilitySettings"),
                    ),
                ),
            ),
        )
        assertTrue(report.contains("com.android.settings v10"))
        assertTrue(report.contains("AccessibilitySettings"))
    }

    @Test
    fun `电池优化与前台包名如实呈现`() {
        val report = ForegroundProbe.buildReport(facts(currentForeground = "com.byd.launcher"))
        assertTrue(report.contains("电池优化白名单：未加入"))
        assertTrue(report.contains("当前前台包名：com.byd.launcher"))
    }

    @Test
    fun `名单命中时报告直接说命中`() {
        val report = ForegroundProbe.buildReport(
            facts(liveForeground = "com.byd.media", hiddenApps = listOf("com.byd.media")),
        )
        assertTrue(report.contains("名单是否命中：是"))
        assertTrue(report.contains("← 当前正是它"))
    }

    @Test
    fun `名单为空时说明这是预期不生效`() {
        val report = ForegroundProbe.buildReport(facts())
        assertTrue(report.contains("名单为空，功能不生效是预期"))
    }

    @Test
    fun `没有收到事件时明确说没收到`() {
        val report = ForegroundProbe.buildReport(facts(liveForeground = null))
        assertTrue(report.contains("事件看到的前台：（还没有收到任何事件）"))
        assertTrue(report.contains("（空：无障碍服务可能还没收到窗口事件"))
    }

    @Test
    fun `最近切换按时间列出包名`() {
        val report = ForegroundProbe.buildReport(
            facts(recentSwitches = listOf("com.byd.launcher" to 0L, "com.byd.media" to 60_000L)),
        )
        assertTrue(report.contains("com.byd.launcher"))
        assertTrue(report.contains("com.byd.media"))
    }

    @Test
    fun `后台限制段列出白名单状态与 AppOps 模式`() {
        val report = ForegroundProbe.buildReport(
            facts(
                runInBackgroundMode = "被忽略/拒绝(1)",
                backgroundRestricted = true,
                batteryCandidates = listOf(
                    ForegroundProbe.Candidate(
                        "电池优化",
                        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        false,
                    ),
                ),
            ),
        )
        assertTrue(report.contains("[后台限制]"))
        assertTrue(report.contains("电池优化白名单：未加入"))
        assertTrue(report.contains("AppOps runAnyInBackground：被忽略/拒绝(1)"))
        assertTrue(report.contains("系统标记「限制后台活动」：是"))
        assertTrue(report.contains("[  ×  ] android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test
    fun `系统版本查不到后台限制时如实说明`() {
        val report = ForegroundProbe.buildReport(facts(backgroundRestricted = null))
        assertTrue(report.contains("查不到（系统版本较低）"))
    }
}
