package com.luckylearning.windtone.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 前台应用检测的两个通道的「可用性探测 + 轮询」（v1.1.29，设计见 docs/设计-悬浮窗按应用隐藏.md）。
 *
 * 主通道是 [ForegroundAppService]（无障碍服务，事件即时、常驻）；本对象是备胎与仪表盘：
 *  · [usageStatsGranted] / [accessibilityEnabled] —— 诊断页显示两个通道各自的状态；
 *  · [currentForegroundPackage] —— 诊断页「当前前台」行、轮询共用的一次性查询；
 *  · [pollLoop] —— UsageStats 轮询（1.5s 一次），由 `LyricOverlayService` 在
 *    「有名单要判或记录开着」时启停；
 *  · [logcatReadable] —— 实测 logcat 可读性（诊断项）：万一迪丽放松了读取限制，
 *    就自动加成一个第三来源；读不了也只如实报告，不影响任何功能。
 *
 * ⚠️ **API 兼容**：`OPSTR_GET_USAGE_STATS` 常量是 API 29 才有的，`checkOpNoThrow(String,…)`
 * 也是。minSdk 24，所以常量用字面量，且 28 及以下走「查一秒内事件非空」的降级探测。
 */
internal object ForegroundAppDetector {

    private const val TAG = "WindToneFgDetect"

    /** `AppOpsManager.OPSTR_GET_USAGE_STATS` 的字面量（API 29 才有该常量） */
    private const val OPSTR_GET_USAGE_STATS = "android:get_usage_stats"

    private const val POLL_INTERVAL_MS = 1_500L

    /** `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`（API 31）的字面量 */
    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** 诊断页要显示的两个通道状态 */
    data class Status(
        val usageStats: Boolean,
        val accessibility: Boolean,
        val logcat: Boolean,
    )

    /** 探测两个通道 + logcat 可读性 */
    fun status(context: Context): Status = Status(
        usageStats = usageStatsGranted(context),
        accessibility = accessibilityEnabled(context),
        logcat = logcatReadable(),
    )

    /**
     * UsageStats 权限是否已授予。
     *
     * 授权页：`ACTION_USAGE_ACCESS_SETTINGS`（迪丽上有没有这一页未知，
     * 所以这只是备胎通道；诊断页会给出跳转与说明）。
     */
    fun usageStatsGranted(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } else {
            // 28 及以下：没有 String 版 checkOp。降级为「查得到事件即视为已授权」——
            // 未授权时 queryEvents 返回空，这个判据在实践中与权限状态一致。
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@runCatching false
            val now = System.currentTimeMillis()
            usm.queryEvents(now - 60_000, now).let { events ->
                var any = false
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    any = true
                }
                any
            }
        }
    }.getOrDefault(false)

    /**
     * 无障碍服务是否已在系统设置里开启。
     *
     * 判据是 `ENABLED_ACCESSIBILITY_SERVIES` 里有没有本服务的组件名
     * （`包名/com.luckylearning.windtone.overlay.ForegroundAppService`）。
     * 用户在设置里开 / 关都会反映在这里，所以诊断页的状态行是实时的。
     */
    fun accessibilityEnabled(context: Context): Boolean = runCatching {
        val expected = "${context.packageName}/${ForegroundAppService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    /**
     * 跳系统「无障碍设置」页（v1.1.81：多候选 + 兜底，返回是否真的打开了）。
     *
     * 车机（比亚迪 DiLink 等）大量没有 `ACTION_ACCESSIBILITY_SETTINGS` 对应的 Activity，
     * 直接 startActivity 会抛 ActivityNotFoundException —— 旧实现只弹一个 toast，
     * 用户面前就是一个**没有出口**的提示。
     *
     * 现在按可能性从高到低逐个试：标准 action → API 31+ 的本服务详情页 →
     * 各家设置应用的显式组件 → 系统设置首页。全都失败才回 false，
     * 由调用方给出「手动路径 + 使用情况权限」的替代方案。
     */
    fun openAccessibilitySettings(context: Context): Boolean =
        startFirstResolvable(context, accessibilitySettingsIntents(context))

    /** 候选 Intent（顺序即优先级；测试可直接断言顺序与兜底项） */
    fun accessibilitySettingsIntents(context: Context): List<Intent> = buildList {
        add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // API 31+：直达本服务的开关页，比「列表页里翻到听风」少一步。
        // 用字面量而不是 Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS：
        // 常量在部分 SDK 的 android.jar 里解析不到（本工程 compileSdk 35 上实测 Unresolved），
        // 而这串 action 是平台稳定值。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                Intent(ACTION_ACCESSIBILITY_DETAILS)
                    .putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        android.content.ComponentName(context, ForegroundAppService::class.java),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        // 各家设置应用的显式入口：resolve 不到会在 startFirstResolvable 里被跳过。
        // ⚠️ 这里**不放** ACTION_SETTINGS 兜底：兜底必须由调用方显式选择
        // （先给路径向导，用户点「打开系统设置」再去），否则「成功打开设置首页」
        // 会被当成「无障碍页打开了」，用户落在设置首页、没有任何指引。
        ACCESSIBILITY_ACTIVITIES.forEach { (pkg, cls) ->
            add(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 只打开「系统设置首页」（路径向导里的按钮用） */
    fun openSystemSettings(context: Context): Boolean =
        startFirstResolvable(context, listOf(systemSettingsIntent()))

    /** 系统设置首页的 Intent（单独暴露，便于测试断言「兜底是显式选择」） */
    fun systemSettingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 跳系统「使用情况权限」页（多候选 + 兜底）。
     *
     * 这是无障碍不可用时的**等效通道**（轮询 1.5 秒，够判「切到哪个应用」），
     * 所以它必须比无障碍更容易打开。
     */
    fun openUsageAccessSettings(context: Context): Boolean =
        startFirstResolvable(context, usageAccessIntents())

    fun usageAccessIntents(): List<Intent> = buildList {
        add(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        USAGE_ACCESS_ACTIVITIES.forEach { (pkg, cls) ->
            add(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 本机是否**存在**无障碍设置页（车机常被裁掉；仅用于如实展示状态，不影响跳转） */
    fun canOpenAccessibilityPage(context: Context): Boolean =
        accessibilitySettingsIntents(context).any { resolvable(context, it) }

    /** 本机是否存在「使用情况访问」页 */
    fun canOpenUsageAccessPage(context: Context): Boolean =
        usageAccessIntents().any { resolvable(context, it) }

    private fun resolvable(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, 0) != null
    }.getOrDefault(false)

    /**
     * 逐个尝试，返回是否有一个成功启动。
     *
     * 先 `resolveActivity` 过滤（不抛异常的判断），再 startActivity；
     * startActivity 仍可能因权限 / 导出限制失败，所以还要包一层 runCatching 继续试下一个。
     */
    private fun startFirstResolvable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val resolvable = runCatching {
                context.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
            if (!resolvable) continue
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) return true
        }
        return false
    }

    /** 常见 ROM 的无障碍设置页组件（找不到就跳过，不报错） */
    private val ACCESSIBILITY_ACTIVITIES = listOf(
        "com.android.settings" to "com.android.settings.Settings\$AccessibilitySettingsActivity",
        "com.android.settings" to "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings" to "com.android.settings.Settings\$AccessibilitySettings",
        // 比亚迪 DiLink（部分版本设置应用独立包名；不存在时 resolve 会跳过）
        "com.byd.settings" to "com.byd.settings.Settings\$AccessibilitySettingsActivity",
        "com.byd.settings" to "com.byd.settings.accessibility.AccessibilitySettings",
    )

    private val USAGE_ACCESS_ACTIVITIES = listOf(
        "com.android.settings" to "com.android.settings.Settings\$UsageAccessSettingsActivity",
        "com.android.settings" to "com.android.settings.usage.UsageAccessSettings",
        "com.byd.settings" to "com.byd.settings.Settings\$UsageAccessSettingsActivity",
    )

    /**
     * 当前前台应用的包名（UsageStats，一次性查询最近 10 秒的最后一个 ACTIVITY_RESUMED）。
     *
     * 拿不到（未授权 / 系统刚起还没有事件）返回 null —— 调用方按「不知道」处理，
     * **不猜、不隐藏**。
     */
    fun currentForegroundPackage(context: Context): String? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@runCatching null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        var pkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = e.packageName
            }
        }
        pkg
    }.getOrNull()

    /**
     * 轮询循环：每 1.5s 查一次前台应用，发现切换就回调。
     *
     * 只在「有名单要判 / 记录开着」且 UsageStats 已授权时由
     * `LyricOverlayService` 启动；协程一取消就退，不再查一个字节。
     */
    suspend fun pollLoop(context: Context, onSwitch: (String) -> Unit) {
        while (currentCoroutineContext().isActive) {
            val pkg = currentForegroundPackage(context)
            if (pkg != null && ForegroundAppState.report(pkg)) {
                onSwitch(pkg)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * logcat 可读性实测（诊断项，不作为正式通道）。
     *
     * Android 4.1 起应用默认只能读自己的日志；读全量要 `READ_LOGS`（adb 授予）。
     * 这里尝试起一个 50ms 的 logcat：读得到非空输出就报告「可读」——
     * 多数定制 ROM 是读不到的，如实报告即可，不影响任何功能。
     */
    fun logcatReadable(): Boolean = runCatching {
        val process = ProcessBuilder("logcat", "-t", "1").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.isNotBlank()
    }.getOrElse { e ->
        Log.d(TAG, "logcat 不可读（预期如此）", e)
        false
    }
}
