package com.luckylearning.windtone.data

import android.content.Context
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 开机自启的「落盘证据」（v1.1.27）。
 *
 * 为什么需要它：开机自启这条链路只有真车能验 —— 模拟器发不出 `BOOT_COMPLETED`
 * （系统保护广播），用户也没法一边开着 adb 一边重启。而这条链路上的失败表现
 * 全部长成同一个样子：**开机后什么都没发生**。至少四种成因：
 *
 *  1. 广播根本没到（ROM 的自启动白名单拦了 / 接收器声明不对）；
 *  2. 到了但开关是关的（决策 = 什么都不做）；
 *  3. 决策对、服务也起来了，但曲库/队列还没就绪，起播被跳过；
 *  4. 起播成功，但用户以为没播（车机音量在 0 / 音频焦点被导航抢走）。
 *
 * 光看「没声音」分不出是哪种。所以广播一到就把「谁、什么时候、决定了什么」写进
 * 一个独立的小文件，起播成功或失败再补一行结果；设置页把它原样念给用户听。
 * 记录只写十几字节，与播放偏好分开存（`boot_status`），清用户数据不会顺手抹掉它。
 */
object BootStatus {

    private const val FILE = "boot_status"
    private const val KEY_AT = "at"
    private const val KEY_ACTION = "action"
    private const val KEY_OK = "ok"
    private const val KEY_REASON = "reason"

    /** 决策：后台起播（只起服务、不起界面） */
    const val ACTION_AUTOPLAY = "autoplay"

    /** 决策：打开主界面 */
    const val ACTION_OPEN_UI = "open_ui"

    /** 决策：什么都不做 */
    const val ACTION_NONE = "none"

    /**
     * 一次开机自启的记录。
     *
     * [ok] 为 `null` 表示「广播到了、结果还没写」——正常路径里它只存在几百毫秒，
     * 但如果开机后进程被系统杀掉，这个 null 会一直留着，正好说明「起播被中断」。
     */
    data class Snapshot(
        val atMillis: Long = 0L,
        val action: String = ACTION_NONE,
        val ok: Boolean? = null,
        val reason: String = "",
    ) {
        /** 是否真的收到过开机广播 */
        val received: Boolean get() = atMillis > 0L
    }

    /** 广播到了：先记下时间与决策，[Snapshot.ok] 留给起播结果去填 */
    fun markReceived(context: Context, action: String, reason: String = "") {
        runCatching {
            sp(context).edit()
                .putLong(KEY_AT, System.currentTimeMillis())
                .putString(KEY_ACTION, action)
                .remove(KEY_OK)
                .putString(KEY_REASON, reason)
                .apply()
        }
    }

    /** 起播有结论了 */
    fun markResult(context: Context, ok: Boolean, reason: String = "") {
        runCatching {
            sp(context).edit()
                .putBoolean(KEY_OK, ok)
                .putString(KEY_REASON, reason)
                .apply()
        }
    }

    fun snapshot(context: Context): Snapshot = runCatching {
        val p = sp(context)
        Snapshot(
            atMillis = p.getLong(KEY_AT, 0L),
            action = p.getString(KEY_ACTION, ACTION_NONE) ?: ACTION_NONE,
            ok = if (p.contains(KEY_OK)) p.getBoolean(KEY_OK, false) else null,
            reason = p.getString(KEY_REASON, "") ?: "",
        )
    }.getOrDefault(Snapshot())

    fun clear(context: Context) {
        runCatching { sp(context).edit().clear().apply() }
    }

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * 把 [BootStatus.Snapshot] 翻成人话（纯函数，能上单测）。
 *
 * 放在数据层而不是 Compose 里：这几句话是排障结论，措辞本身就是功能的一部分 ——
 * 「什么都没做」和「起播失败」必须一眼能分清，不能靠读代码才能解释。
 */
object BootStatusText {

    fun actionLabel(action: String): String = when (action) {
        BootStatus.ACTION_AUTOPLAY ->
            AppStrings.s(R.string.bst_action_autoplay).ifEmpty { "后台起播（不打开界面）" }
        BootStatus.ACTION_OPEN_UI ->
            AppStrings.s(R.string.bst_action_open_ui).ifEmpty { "打开主界面" }
        else ->
            AppStrings.s(R.string.bst_action_none).ifEmpty { "什么都不做" }
    }

    /**
     * 结果那一行。
     *
     * [Snapshot.ok] 为 null 且已经过了很久 → 「起了个头就没下文」，即进程在起播途中被回收；
     * 这种情况在车机上不算罕见（开机阶段内存最紧张），值得单独说一句。
     *
     * 冷路径（设置页排障）：每次展示调一次 [AppStrings.s]，不缓存大对象。
     */
    fun resultLabel(s: BootStatus.Snapshot, now: Long = System.currentTimeMillis()): String {
        if (!s.received) {
            return AppStrings.s(R.string.bst_never_received).ifEmpty { "还没有收到过开机广播" }
        }
        return when (s.ok) {
            true -> if (s.reason.isNotBlank()) {
                AppStrings.s(R.string.bst_ok_reason, s.reason)
                    .ifEmpty { "已起播（${s.reason}）" }
            } else {
                AppStrings.s(R.string.bst_ok).ifEmpty { "已起播" }
            }
            false -> {
                val reason = s.reason.ifBlank {
                    AppStrings.s(R.string.bst_fail_unknown).ifEmpty { "原因未知" }
                }
                AppStrings.s(R.string.bst_fail, reason).ifEmpty { "未起播：$reason" }
            }
            null -> if (now - s.atMillis > STUCK_MS) {
                AppStrings.s(R.string.bst_stuck)
                    .ifEmpty { "起了个头就没下文（进程可能在起播途中被系统回收）" }
            } else {
                AppStrings.s(R.string.bst_restoring).ifEmpty { "正在恢复队列…" }
            }
        }
    }

    /** 「多久以前」。开机时间只精确到分钟就够，不需要秒。 */
    fun agoLabel(now: Long, at: Long): String {
        if (at <= 0L) return AppStrings.s(R.string.bst_ago_none).ifEmpty { "—" }
        val d = (now - at).coerceAtLeast(0L)
        return when {
            d < 60_000L ->
                AppStrings.s(R.string.bst_ago_just_now).ifEmpty { "刚刚" }
            d < 3_600_000L ->
                AppStrings.s(R.string.bst_ago_minutes, d / 60_000L)
                    .ifEmpty { "${d / 60_000L} 分钟前" }
            d < 86_400_000L ->
                AppStrings.s(R.string.bst_ago_hours, d / 3_600_000L)
                    .ifEmpty { "${d / 3_600_000L} 小时前" }
            else ->
                AppStrings.s(R.string.bst_ago_days, d / 86_400_000L)
                    .ifEmpty { "${d / 86_400_000L} 天前" }
        }
    }

    /** 超过这个时间还没结果，按「中断」讲 */
    const val STUCK_MS = 60_000L
}
