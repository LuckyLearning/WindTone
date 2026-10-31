package com.luckylearning.windtone.overlay

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.ui.AppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台识别通道的**现场取证报告**（v1.1.82）。
 *
 * 起因：比亚迪 DiLink 把「无障碍」设置页整个裁掉了，用户点按钮只看到一句
 * 「打不开」；远程又看不到车机里到底有什么，只能靠猜。这个报告把三件事一次说清：
 *
 *  1. **系统里到底有没有这个页面** —— 对每个候选 Intent 逐个 `resolveActivity`，
 *     并枚举设置类应用里名字含 accessibility 的 Activity；
 *  2. **服务本身是否被系统接受** —— 我们的 AccessibilityService 是否声明在案、
 *     是否出现在 `ENABLED_ACCESSIBILITY_SERVICES` 里；
 *  3. **备胎通道能不能用** —— UsageStats 的 AppOps 模式、电池优化白名单、
 *     当前前台包名，以及「按应用隐藏」的实际判定输入。
 *
 * 纯函数 [buildReport] 只吃 [Facts]，不碰框架 —— 报告格式可以在 JVM 单测里钉死；
 * [collect] 负责取数。两者分开是为了「报告里少一行」这种退化能被测试拦住。
 */
internal object ForegroundProbe {

    private const val TAG = "WindToneProbe"

    /** 取到的全部事实（全部来自系统查询，不含推断） */
    data class Facts(
        val collectedAt: Long,
        val manufacturer: String,
        val model: String,
        val device: String,
        val androidRelease: String,
        val sdkInt: Int,
        val appPackage: String,
        val appVersion: String,
        /** 无障碍设置页：任一候选能解析即 true */
        val accessibilityPageResolvable: Boolean,
        /** 使用情况访问页：能否解析 */
        val usageAccessPageResolvable: Boolean,
        /** 我们的无障碍服务是否已在系统里启用 */
        val accessibilityServiceEnabled: Boolean,
        /** `ENABLED_ACCESSIBILITY_SERVICES` 原文（可能含其他应用，展示时截断） */
        val enabledAccessibilityServices: String,
        /** `ACCESSIBILITY_ENABLED` 总开关 */
        val accessibilityMasterEnabled: Boolean,
        /** 服务是否声明在案（PackageManager 能否查到本应用的 service） */
        val serviceDeclared: Boolean,
        /** 服务是否 exported（系统要跨进程绑定它；false 时开了也不会生效） */
        val serviceExported: Boolean = false,
        /** 看护记录：这台机器上是否曾见过无障碍处于开启（掉线提醒的前提） */
        val watchdogSeenOn: Boolean = false,
        /** 无障碍事件 / 轮询**实际**看到的前台包名（与 UsageStats 那行相互独立） */
        val liveForeground: String? = null,
        /** 当前「不显示悬浮窗的应用」名单 */
        val hiddenApps: List<String> = emptyList(),
        /** 最近的前台切换（旧的在前，最多 20 条）：判断事件到底有没有来 */
        val recentSwitches: List<Pair<String, Long>> = emptyList(),
        /** 系统当前认为在跑的无障碍服务（空 = 勾着但没绑上，事件不会来） */
        val activeServices: List<String> = emptyList(),
        /** 本服务上次被系统连上的时间；0 = 从未 */
        val serviceLastConnectedAt: Long = 0L,
        /** 省电模式（车机低电时也会限后台） */
        val powerSaveMode: Boolean = false,
        /** 系统是否显式标记「限制后台活动」；null = 该版本查不到 */
        val backgroundRestricted: Boolean? = null,
        /** AppOps android:runAnyInBackground 的模式（车厂常用它代替电池白名单） */
        val runInBackgroundMode: String = "—",
        /** 电池优化页的候选解析结果 */
        val batteryCandidates: List<Candidate> = emptyList(),
        /** UsageStats 的 AppOps 模式（数值 + 人话） */
        val usageStatsMode: String,
        /** UsageStats 是否视为已授权 */
        val usageStatsGranted: Boolean,
        /** 是否在电池优化白名单里（车机杀后台的首要嫌疑） */
        val ignoringBatteryOptimizations: Boolean,
        /** 当前前台包名（null = 查不到/未授权） */
        val currentForeground: String?,
        /** 设置类应用：包名 → 版本 + 名字里含 accessibility 的 Activity 列表 */
        val settingsApps: List<SettingsApp>,
        /** 候选 Intent 的逐条解析结果（顺序即尝试顺序） */
        val candidates: List<Candidate>,
        /** logcat 是否可读（可读就有第三条通道） */
        val logcatReadable: Boolean,
    )

    data class SettingsApp(
        val packageName: String,
        val versionName: String,
        val accessibilityActivities: List<String>,
    )

    data class Candidate(
        val label: String,
        val target: String,
        val resolvable: Boolean,
    )

    /** 取数（每项都 runCatching，任何一项失败只影响那一行，不整份失败） */
    fun collect(context: Context): Facts {
        val pm = context.packageManager
        val appVersion = runCatching {
            pm.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"

        val accessibilityCandidates = ForegroundAppDetector.accessibilitySettingsIntents(context)
        val usageCandidates = ForegroundAppDetector.usageAccessIntents()

        fun resolvable(intent: Intent): Boolean = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)

        val settingsApps = enumerateSettingsApps(context)
        val mode = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    "android:get_usage_stats",
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), context.packageName)
            }
            when (m) {
                AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED(0)"
                AppOpsManager.MODE_IGNORED -> "MODE_IGNORED(1)"
                AppOpsManager.MODE_ERRORED -> "MODE_ERRORED(2)"
                AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT(3)"
                else -> "MODE_$m"
            }
        }.getOrElse { AppStrings.s(R.string.fp_query_failed, it.javaClass.simpleName) }

        val ignoringBattery = ForegroundAppDetector.ignoringBatteryOptimizations(context)

        // ⚠️ v1.1.82 的报告在这一行给出过「否」的**假阴性**：当时用
        // queryIntentServices(MATCH_DEFAULT_ONLY) 查，而无障碍服务的 intent-filter
        // 没有 CATEGORY_DEFAULT，必然查不到。改为直接按组件名取 ServiceInfo。
        val serviceInfo = runCatching {
            pm.getServiceInfo(
                android.content.ComponentName(context, ForegroundAppService::class.java),
                PackageManager.GET_META_DATA,
            )
        }.getOrNull()
        val declared = serviceInfo != null
        val exported = serviceInfo?.exported ?: false

        return Facts(
            collectedAt = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            appPackage = context.packageName,
            appVersion = appVersion,
            accessibilityPageResolvable = accessibilityCandidates.any(::resolvable),
            usageAccessPageResolvable = usageCandidates.any(::resolvable),
            accessibilityServiceEnabled = ForegroundAppDetector.accessibilityEnabled(context),
            enabledAccessibilityServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty(),
            accessibilityMasterEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1,
            serviceDeclared = declared,
            serviceExported = exported,
            watchdogSeenOn = AccessibilityWatchdog.seenOn(context),
            liveForeground = ForegroundAppState.currentPkg.value,
            hiddenApps = Prefs(context).lyricOverlayHiddenApps.sorted(),
            recentSwitches = ForegroundAppState.recent().map { it.pkg to it.atMillis },
            activeServices = ForegroundAppDetector.activeAccessibilityServices(context),
            serviceLastConnectedAt = AccessibilityWatchdog.lastConnectedAt(context),
            powerSaveMode = ForegroundAppDetector.powerSaveMode(context),
            backgroundRestricted = ForegroundAppDetector.backgroundRestricted(context),
            runInBackgroundMode = ForegroundAppDetector.runInBackgroundMode(context),
            batteryCandidates = ForegroundAppDetector.batteryOptimizationIntents(context).map {
                Candidate(AppStrings.s(R.string.fp_label_battery), describe(it), resolvable(it))
            },
            usageStatsMode = mode,
            usageStatsGranted = ForegroundAppDetector.usageStatsGranted(context),
            ignoringBatteryOptimizations = ignoringBattery,
            currentForeground = ForegroundAppDetector.currentForegroundPackage(context),
            settingsApps = settingsApps,
            candidates = (accessibilityCandidates.map {
                Candidate(AppStrings.s(R.string.fp_label_a11y), describe(it), resolvable(it))
            } + usageCandidates.map {
                Candidate(AppStrings.s(R.string.fp_label_usage), describe(it), resolvable(it))
            } + Candidate(
                AppStrings.s(R.string.fp_label_settings_home),
                Settings.ACTION_SETTINGS,
                resolvable(ForegroundAppDetector.systemSettingsIntent()),
            )),
            logcatReadable = ForegroundAppDetector.logcatReadable(),
        )
    }

    private fun describe(intent: Intent): String =
        intent.component?.let { "${it.packageName}/${it.className}" }
            ?: intent.action.orEmpty()

    /**
     * 枚举「设置类」应用及其无障碍 Activity。
     *
     * 为什么从**启动器**反查：Android 11+ 不给 `getInstalledPackages` 全量列表，
     * 但用户能从桌面点开的「设置」一定有 launcher activity，可见性天然满足。
     * 包名或应用名里带 settings / 设置 / byd 的都收进来 —— DiLink 的设置应用
     * 在不同版本叫过 `com.android.settings`、`com.byd.settings` 等。
     */
    private fun enumerateSettingsApps(context: Context): List<SettingsApp> {
        val pm = context.packageManager
        val launchers = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }.getOrDefault(emptyList())

        return launchers.mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault("")
                pkg.contains("settings", ignoreCase = true) ||
                    pkg.contains("byd", ignoreCase = true) ||
                    label.contains("设置") ||
                    label.contains("Setting", ignoreCase = true)
            }
            .distinct()
            .take(6)
            .map { pkg ->
                val info = runCatching {
                    pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                }.getOrNull()
                val acts = info?.activities.orEmpty()
                    .mapNotNull { it.name }
                    .filter { it.contains("accessib", ignoreCase = true) }
                    .take(8)
                SettingsApp(pkg, info?.versionName ?: "—", acts)
            }
    }

    /**
     * 报告正文（纯函数，单测直接喂 [Facts]）。
     *
     * 版式刻意保持「一行一个事实、冒号右边是取值」，方便用户整段复制粘贴回来，
     * 也方便我们用肉眼比对两台车的差异。
     */
    fun buildReport(facts: Facts): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(facts.collectedAt))
        val sb = StringBuilder()
        fun s(id: Int, vararg args: Any) = AppStrings.s(id, *args)
        fun mark(ok: Boolean) = if (ok) s(R.string.fp_resolvable) else s(R.string.fp_not_resolvable)

        sb.appendLine(s(R.string.fp_report_title))
        sb.appendLine(s(R.string.fp_collected_at, time))
        sb.appendLine(s(R.string.fp_device, facts.manufacturer, facts.model, facts.device))
        sb.appendLine(s(R.string.fp_system, facts.androidRelease, facts.sdkInt))
        sb.appendLine(s(R.string.fp_app, facts.appPackage, facts.appVersion))
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_conclusion))
        sb.appendLine(
            s(
                R.string.fp_a11y_page,
                s(if (facts.accessibilityPageResolvable) R.string.fp_a11y_page_ok else R.string.fp_a11y_page_missing),
            ),
        )
        sb.appendLine(
            s(
                R.string.fp_a11y_service,
                s(if (facts.accessibilityServiceEnabled) R.string.fp_a11y_on else R.string.fp_a11y_off),
            ),
        )
        sb.appendLine(
            s(
                R.string.fp_usage,
                s(if (facts.usageStatsGranted) R.string.fp_usage_granted else R.string.fp_usage_denied),
            ),
        )
        sb.appendLine(s(R.string.fp_recommend, recommend(facts)))
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_a11y))
        sb.appendLine(s(R.string.fp_service_declared, yesNo(facts.serviceDeclared), yesNo(facts.serviceExported)))
        sb.appendLine(s(R.string.fp_a11y_master, yesNo(facts.accessibilityMasterEnabled)))
        sb.appendLine(s(R.string.fp_watchdog, yesNo(facts.watchdogSeenOn)))
        val connectedAt = if (facts.serviceLastConnectedAt > 0L) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(facts.serviceLastConnectedAt))
        } else {
            s(R.string.fp_never_connected)
        }
        sb.appendLine(s(R.string.fp_last_connected, connectedAt))
        val ours = "${facts.appPackage}/${ForegroundAppService::class.java.name}"
        val running = facts.activeServices.any { it.equals(ours, ignoreCase = true) }
        sb.appendLine(
            s(
                R.string.fp_service_running,
                yesNo(running),
                if (facts.activeServices.isEmpty()) s(R.string.fp_service_list_empty) else "",
            ),
        )
        sb.appendLine(
            s(
                R.string.fp_enabled_services,
                facts.enabledAccessibilityServices.take(400).ifBlank { s(R.string.fp_empty) },
            ),
        )
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_usage))
        sb.appendLine(s(R.string.fp_usage_appops, facts.usageStatsMode))
        sb.appendLine(s(R.string.fp_usage_judged, yesNo(facts.usageStatsGranted)))
        sb.appendLine(
            s(
                R.string.fp_fg_pkg,
                facts.currentForeground ?: s(R.string.fp_fg_unavailable),
            ),
        )
        sb.appendLine(s(R.string.fp_logcat, yesNo(facts.logcatReadable)))
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_bg))
        sb.appendLine(
            s(
                R.string.fp_battery_whitelist,
                s(if (facts.ignoringBatteryOptimizations) R.string.fp_battery_in else R.string.fp_battery_out),
            ),
        )
        sb.appendLine(s(R.string.fp_power_save, yesNo(facts.powerSaveMode)))
        sb.appendLine(
            s(
                R.string.fp_bg_restricted,
                when (facts.backgroundRestricted) {
                    true -> s(R.string.fp_bg_restricted_yes)
                    false -> s(R.string.fp_bg_restricted_no)
                    null -> s(R.string.fp_bg_restricted_unknown)
                },
            ),
        )
        sb.appendLine(s(R.string.fp_run_bg_appops, facts.runInBackgroundMode))
        sb.appendLine(s(R.string.fp_battery_candidates))
        if (facts.batteryCandidates.isEmpty()) {
            sb.appendLine(s(R.string.fp_no_candidates))
        } else {
            facts.batteryCandidates.forEach { c ->
                sb.appendLine(s(R.string.fp_candidate_line, mark(c.resolvable), c.target))
            }
        }
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_hidden))
        if (facts.hiddenApps.isEmpty()) {
            sb.appendLine(s(R.string.fp_hidden_empty))
        } else {
            facts.hiddenApps.forEach { pkg ->
                val hit = pkg == facts.liveForeground
                sb.appendLine(
                    s(
                        R.string.fp_hidden_item,
                        pkg,
                        if (hit) s(R.string.fp_hidden_hit) else "",
                    ),
                )
            }
        }
        sb.appendLine(
            s(
                R.string.fp_live_fg,
                facts.liveForeground ?: s(R.string.fp_no_events),
            ),
        )
        sb.appendLine(
            s(
                R.string.fp_list_hit,
                yesNo(facts.liveForeground != null && facts.liveForeground in facts.hiddenApps),
            ),
        )
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_switches))
        if (facts.recentSwitches.isEmpty()) {
            sb.appendLine(s(R.string.fp_switches_empty))
        } else {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            facts.recentSwitches.takeLast(20).forEach { (pkg, at) ->
                sb.appendLine("${fmt.format(Date(at))}  $pkg")
            }
        }
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_settings_apps))
        if (facts.settingsApps.isEmpty()) {
            sb.appendLine(s(R.string.fp_settings_apps_empty))
        } else {
            facts.settingsApps.forEach { app ->
                sb.appendLine(s(R.string.fp_settings_app_item, app.packageName, app.versionName))
                if (app.accessibilityActivities.isEmpty()) {
                    sb.appendLine(s(R.string.fp_no_a11y_activity))
                } else {
                    app.accessibilityActivities.forEach { sb.appendLine("    $it") }
                }
            }
        }
        sb.appendLine()

        sb.appendLine(s(R.string.fp_sec_candidates))
        facts.candidates.forEach { c ->
            sb.appendLine(s(R.string.fp_candidate_labeled, mark(c.resolvable), c.label, c.target))
        }
        sb.appendLine()

        // 车机上「无障碍页被裁」时的唯一硬路子（社区实践：MacroDroid / 按键助手都是这么开的）。
        // 必须提醒会覆盖原列表 —— 直接把原值接在前面，否则用户会把别人的服务顶掉。
        sb.appendLine(s(R.string.fp_sec_adb))
        sb.appendLine(s(R.string.fp_adb_note1))
        sb.appendLine(s(R.string.fp_adb_note2))
        sb.appendLine("adb shell settings put secure enabled_accessibility_services " +
            "${facts.enabledAccessibilityServices.ifBlank { "" }.let { if (it.isBlank()) "" else "$it:" }}" +
            "${facts.appPackage}/${ForegroundAppService::class.java.name}")
        sb.appendLine("adb shell settings put secure accessibility_enabled 1")
        sb.appendLine()
        sb.appendLine(s(R.string.fp_report_end))
        return sb.toString()
    }

    private fun recommend(facts: Facts): String = when {
        facts.accessibilityServiceEnabled -> AppStrings.s(R.string.fp_rec_a11y)
        facts.accessibilityPageResolvable -> AppStrings.s(R.string.fp_rec_a11y_first)
        facts.usageStatsGranted -> AppStrings.s(R.string.fp_rec_usage)
        facts.usageAccessPageResolvable -> AppStrings.s(R.string.fp_rec_open_usage)
        else -> AppStrings.s(R.string.fp_rec_adb_only)
    }

    private fun yesNo(v: Boolean) =
        AppStrings.s(if (v) R.string.fp_yes else R.string.fp_no)


    /**
     * 把报告落盘到应用私有目录（卸载即清），返回路径。
     *
     * 车机上「复制粘贴」不一定方便，落盘后可用文件管理器 / USB 取走，
     * 也便于连续多次采集后对比。
     */
    fun saveToFile(context: Context, report: String): String? = runCatching {
        val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = java.io.File(dir, "fg_probe_$stamp.txt")
        file.writeText(report)
        file.absolutePath
    }.getOrElse {
        Log.w(TAG, AppStrings.s(R.string.fp_save_failed), it)
        null
    }
}
