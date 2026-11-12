package com.anylyric.data

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * 通知使用权的两个独立事实。
 *
 * 「系统设置里勾着」与「本进程已被系统绑定」不是一回事。应用被系统回收后重新打开，
 * 权限仍然是勾着的，但 `NotificationListenerService` 要等 SystemServer 重新 bind，
 * 这段空窗期里 `onListenerConnected` 还没来。若把「已连接」当作「已授权」，
 * 界面就会显示未授予，把用户赶去系统设置 —— 而他在那里看到的是勾选状态，
 * 只能手动关掉再打开一次才会重绑。
 *
 * 两个事实分开后，空窗期可以自行 [Permissions.requestRebind]，不必甩锅给用户。
 */
enum class ListenerState {
    /** 系统设置里勾着，服务也已连上 —— 正常。 */
    GRANTED,

    /** 系统设置里勾着，但本进程还没被系统绑定 —— 空窗期，可自行请求重绑。 */
    GRANTED_NOT_BOUND,

    /** 系统设置里确实没有勾选 —— 需要用户去授权。 */
    NOT_GRANTED
}

object Permissions {

    /** 监听服务的全名。用字符串而不是类引用，方便纯 JVM 单测直接验判定逻辑。 */
    const val LISTENER_CLASS = "com.anylyric.service.MediaWatchService"

    private const val KEY_ENABLED_LISTENERS = "enabled_notification_listeners"

    /**
     * 系统设置里是否勾选了本应用的通知使用权。
     *
     * 返回 `null` 表示这一项读不到（个别定制 ROM 限制读取 secure 设置），
     * 调用方应退化为按未授予引导，而不是假设已授权。
     */
    fun isListenerGranted(context: Context): Boolean? {
        val flat = try {
            Settings.Secure.getString(context.contentResolver, KEY_ENABLED_LISTENERS)
        } catch (_: Throwable) {
            return null
        }
        return matches(flat, context.packageName, LISTENER_CLASS)
    }

    /**
     * 已授权但本进程未被绑定，请求系统重新绑定。
     *
     * 效果等同「在系统设置里关掉再打开一次」，但不需要用户操作。系统自身有频率
     * 限制，调用方仍应做节流（见 `SessionHub.tryRebind`）。
     *
     * 注：应用被 force-stop 后该请求无效，只能由用户重新打开一次；这是平台行为。
     */
    fun requestRebind(context: Context) {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context.packageName, LISTENER_CLASS)
            )
        } catch (_: Throwable) {
            // 个别定制 ROM 未实现该 API，失败即静默；此时仍可回落到设置页手工重开
        }
    }

    /**
     * 在系统保存的扁平组件串里查找本服务。
     *
     * 系统格式为 `pkg/cls:pkg/cls`，类名可能是相对形式 `.service.X`，必须先展开
     * 再比较 —— 直接做字符串包含会漏判，正是「明明勾着却报未授权」的成因之一。
     */
    internal fun matches(flat: String?, pkg: String, cls: String): Boolean {
        if (flat.isNullOrEmpty()) return false
        for (entry in flat.split(':')) {
            val sep = entry.indexOf('/')
            if (sep <= 0 || sep >= entry.length - 1) continue
            val entryPkg = entry.substring(0, sep)
            if (entryPkg != pkg) continue
            var entryCls = entry.substring(sep + 1)
            if (entryCls.startsWith(".")) entryCls = entryPkg + entryCls
            if (entryCls == cls) return true
        }
        return false
    }
}
