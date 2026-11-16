package com.anylyric.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 开机后把缺失的权限页拉起来（0.2.29）。
 *
 * 为什么需要它：车机重启后，「通知使用权」常常要等系统重新绑定，而「无障碍」
 * 在不少 ROM（HONOR/MagicOS、部分 DiLink）更新应用或重启后会直接被关掉 ——
 * 用户看到的现象是「开机后歌词没了」，而真正的原因是一个勾被清掉了。
 * 让他自己去设置里翻三个不同的页面去确认，是很高的门槛。
 *
 * 同车的「攻城狮助手」走的是同一条路：BOOT_COMPLETED + USER_PRESENT 拉起自己的页面。
 * 我们也监听 USER_PRESENT，但**只在开机后一段时间内**响应 —— 那个广播每次解锁都会发，
 * 不加窗口就变成「每解锁一次弹一次」。
 *
 * ⚠️ 后台启动 Activity 在 targetSdk >= 29 上需要 `SYSTEM_ALERT_WINDOW`。
 * 所以「没有悬浮窗权限」时不尝试拉起：那一步必被系统拦掉，只会刷一条异常日志。
 * 而此时用户本来就要打开应用才能用，自然会看到引导。
 */
object PermissionWake {

    /** 三类会让歌词彻底不工作的权限。 */
    enum class Kind { OVERLAY, LISTENER, ACCESSIBILITY }

    const val A11Y_CLASS = "com.anylyric.accessibility.ForegroundAppService"

    /** 开机后多久之内还认 USER_PRESENT（超过就不响应，避免每次解锁都弹）。 */
    const val USER_PRESENT_WINDOW_MS = 10 * 60 * 1000L

    /**
     * 决定这条广播要不要触发「权限确认页」。
     *
     * 抽成纯函数是为了单测：这条链路的失败表现全是「开机后什么都没发生」，
     * 而它又依赖两个容易写错的量（广播 action、开机时长窗口）。
     */
    fun shouldWake(action: String?, enabled: Boolean, uptimeMs: Long): Boolean {
        if (!enabled) return false
        return when (action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> true
            ACTION_USER_PRESENT -> uptimeMs in 0..USER_PRESENT_WINDOW_MS
            else -> false
        }
    }

    const val ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT"

    /**
     * 当前缺哪些权限。
     *
     * 无障碍**只在用户开过它时才检查**（[Prefs.accessibilityRequested]）：没开过的人
     * 是想用纯悬浮窗，此时提示「无障碍没开」纯属打扰。
     */
    fun missing(context: Context, prefs: Prefs): List<Kind> {
        val out = ArrayList<Kind>(3)
        if (!Settings.canDrawOverlays(context)) out += Kind.OVERLAY
        if (Permissions.isListenerGranted(context) != true) out += Kind.LISTENER
        if (prefs.accessibilityRequested && !isAccessibilityEnabled(context)) {
            out += Kind.ACCESSIBILITY
        }
        return out
    }

    /** 本应用的无障碍服务是否在系统里勾着。 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false
        return Permissions.matches(flat, context.packageName, A11Y_CLASS)
    }

    /** 某一项的跳转意图。逐个 page 打开，因为系统没有「一次性授权」的入口。 */
    fun intentFor(context: Context, kind: Kind): Intent = when (kind) {
        Kind.OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

        Kind.LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Kind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /**
     * 开机广播入口：有缺失才拉起，没有就完全不打扰。
     */
    fun wakeIfNeeded(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.bootPermissionWake) return
        // 没有悬浮窗权限时后台 startActivity 必被拦（targetSdk >= 29 的限制），
        // 试也白试 —— 而且这条路径下用户开机后反正会主动打开应用。
        if (!Settings.canDrawOverlays(context)) return
        if (missing(context, prefs).isEmpty()) return
        runCatching {
            context.startActivity(
                Intent(context, com.anylyric.ui.PermissionWakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure {
            // 车机 ROM 拒绝后台拉起时不要崩：日志本身就是排查线索。
            Log.w("PermissionWake", "startActivity failed: ${it.message}")
        }
    }
}
