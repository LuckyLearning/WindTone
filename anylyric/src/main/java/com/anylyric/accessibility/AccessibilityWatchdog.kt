package com.anylyric.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
import com.anylyric.data.Prefs

/**
 * 无障碍服务的「自动拉回」（0.2.14）。
 *
 * 背景：HONOR/MagicOS 在**应用更新后会把无障碍服务关掉**，用户升级一次功能就静默失效，
 * 而且系统不会给任何提示。通知使用权有 `requestRebind` 可以自己重新要一次，
 * 无障碍**没有**对应 API —— Android 的安全模型就是「只有用户能开」。
 *
 * 唯一的自动通道是 `WRITE_SECURE_SETTINGS`（signature 级，普通安装拿不到，
 * 但 adb 或系统签名可以授予）。有它时直接把服务名写回
 * `enabled_accessibility_services`，效果等同用户手动打开。
 *
 * 没有它时**不假装能做到**：只把状态如实报出来，由界面给一次点击入口。
 * 这条边界写在这里，避免以后有人为了「自动」去做引导授权之类的骚操作。
 *
 * 与 [ForegroundApps.serviceConnected] 的区别：
 *  · 本对象答「系统里勾没勾」（持久事实，读 Secure）；
 *  · `serviceConnected` 答「此刻真的连着没有」（进程内事实）。
 * 两者会分叉：勾着但服务被杀 = granted && !connected。
 */
object AccessibilityWatchdog {

    private const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"

    /** 读 Secure 是 Binder 调用，而报告每次刷新都会问一次：1 秒内复用上次结果。 */
    private const val GRANTED_CACHE_MS = 1_000L

    private var grantedCache = false
    private var grantedCheckedAt = 0L

    /**
     * 系统设置里是否勾选了本应用的无障碍服务。
     *
     * 带 1 秒缓存：状态卡与探测报告都会高频问它。用户从系统设置回来时调
     * [invalidate] 立刻失效，不会看到过期值。
     */
    fun isGranted(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (grantedCheckedAt != 0L && now - grantedCheckedAt < GRANTED_CACHE_MS) {
            return grantedCache
        }
        grantedCache = readGranted(context)
        grantedCheckedAt = now
        return grantedCache
    }

    /** 强制下次 [isGranted] 重新读取（用户从系统设置返回、或刚写完 Secure 时）。 */
    fun invalidate() {
        grantedCheckedAt = 0L
    }

    private fun readGranted(context: Context): Boolean = try {
        val className = ForegroundAppService::class.java.name
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        enabled?.split(':')?.any { it.contains(context.packageName) && it.contains(className) } == true
    } catch (_: Throwable) {
        false
    }

    /**
     * 是否有能力写 Secure 设置。
     *
     * 只有 adb `pm grant` 过、或系统签名应用才为 true —— 普通安装永远是 false。
     */
    fun canWrite(context: Context): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * 尝试把服务写回系统设置。
     *
     * 三重前置，任何一条不满足都立刻返回 false（不做无谓的写入尝试）：
     *  1. 已经勾着 → 不用恢复（服务没连是系统侧的事，写回也没用）；
     *  2. 用户**从没开过** → 不替人做主（`accessibilityRequested` 由「打开按应用隐藏」
     *     与「服务成功连接」两处置位）；
     *  3. 没有写权限 → 平台不允许，交给界面引导。
     *
     * @return 真的写入了才返回 true
     */
    fun tryAutoRestore(context: Context): Boolean {
        if (isGranted(context)) return false
        if (!Prefs(context).accessibilityRequested) return false
        if (!canWrite(context)) return false

        val expected = ComponentName(context, ForegroundAppService::class.java).flattenToString()
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            // 只追加本服务，绝不动用户已有的其它无障碍服务（写坏了会把人家的读屏软件搞挂）
            val next = appendService(current, expected) ?: return false
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                next
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 把 [expected] 追加到 `enabled_accessibility_services` 的冒号分隔列表末尾。
     *
     * 抽成纯函数是因为这一步最容易出事故：写错一个冒号，用户机器上所有无障碍服务都会失效。
     * 单测覆盖了空列表、已有其它服务、已包含、脏数据四种情况。
     *
     * @return 新值；**已经包含**时返回 null，调用方据此跳过写入（避免无意义的 Secure 写）
     */
    internal fun appendService(current: String?, expected: String): String? {
        val parts = current.orEmpty().split(':').filter { it.isNotBlank() }
        if (expected in parts) return null
        return (parts + expected).joinToString(":")
    }
}
