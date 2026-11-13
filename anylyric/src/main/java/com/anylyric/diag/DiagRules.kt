package com.anylyric.diag

/** 一次探测的事实快照。由 Android 层填充，判因规则只读它。 */
data class ProbeSnapshot(
    val listenerConnected: Boolean = false,
    /**
     * 系统设置里是否勾选了通知使用权。
     *
     * 必须与 [listenerConnected] 分开：前者是「用户授权了」，后者是「系统此刻绑上了」。
     * 进程被回收后重新打开，前者为 true、后者为 false —— 那是空窗期，不是没授权。
     */
    val listenerGranted: Boolean = true,
    val overlayGranted: Boolean = false,
    val sessionCount: Int = 0,
    val playingCount: Int = 0,
    val chosenPackage: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0L,
    val metadataKeys: List<String> = emptyList(),
    val pseudoTitle: Boolean = false,
    val hitSource: String? = null,
    val l1OnlyOnPause: Boolean = false,
    val positionJumpCount: Int = 0,
    /** 当前歌词是否带时间轴。只有带时间轴时，进度回跳才会真的让歌词对不齐。 */
    val timedLyrics: Boolean = false,
    val viewAttached: Boolean = false,
    val viewMeasured: Boolean = false,
    val hiddenByRule: Boolean = false,

    /**
     * 按应用隐藏（0.2.11）。
     *
     * 这四个字段合起来能回答真机上最常见的那句「怎么不显示了」：
     *  · [foregroundPkg] —— 事件到底有没有来、来的是哪个包；
     *  · [foregroundHidden] —— 它是否命中名单（名字选对了没有）；
     *  · [overlaySuppressed] —— 悬浮窗此刻是不是正在让位；
     *  · [a11yConnected] —— 无障碍服务是否真的连着（勾了不等于在跑）。
     */
    val a11yConnected: Boolean = false,
    val hideByAppEnabled: Boolean = false,
    val hiddenAppCount: Int = 0,
    val foregroundPkg: String? = null,
    val foregroundHidden: Boolean = false,
    val overlaySuppressed: Boolean = false,

    val displayCount: Int = 1,
    val fontScale: Float = 1f
)

enum class DiagCode {
    PERMISSION_LISTENER,
    LISTENER_UNBOUND,
    PERMISSION_OVERLAY,
    NO_SESSION,
    SESSION_INACTIVE,
    AVRCP_GATE,
    NO_CHANNEL,
    CLOCK_UNSTABLE,
    OVERLAY_NOT_ATTACHED,
    OVERLAY_NOT_MEASURED,
    HIDDEN_BY_RULE,

    /** 按应用隐藏生效中：这是用户自己设的，不是故障。 */
    HIDDEN_BY_APP,
    OK
}

data class Diagnosis(val code: DiagCode, val evidence: String)

/**
 * 自动判因：按「权限 → 会话 → 通道 → 时钟 → 窗口」优先级取第一条命中。
 *
 * 两条最重要、也最容易被误报成 Bug 的结论：
 *  · [DiagCode.NO_SESSION] / [DiagCode.NO_CHANNEL] 是**播放器侧**的事实，不是本应用故障；
 *  · [DiagCode.AVRCP_GATE] 是发布端在 AVRCP 门控下丢更新，同样不是本应用能修的。
 */
object DiagRules {

    fun evaluate(s: ProbeSnapshot): Diagnosis = when {
        !s.listenerGranted ->
            Diagnosis(DiagCode.PERMISSION_LISTENER, "listenerGranted=false")

        !s.overlayGranted ->
            Diagnosis(DiagCode.PERMISSION_OVERLAY, "canDrawOverlays=false")

        // 已授权但服务未绑定：getActiveSessions 的权限边界是授权列表，L0/L1 与进度
        // 照常可用，缺的只有 L2 与推送事件。所以只有「连不上 且 也没拿到歌词」才算
        // 授权侧问题；已经拿到歌词就是降级可用，报故障反而是误伤。
        !s.listenerConnected && s.hitSource == null ->
            Diagnosis(DiagCode.LISTENER_UNBOUND, "granted=true, connected=false, hit=none")

        s.sessionCount == 0 ->
            Diagnosis(DiagCode.NO_SESSION, "sessions=0")

        s.playingCount == 0 ->
            Diagnosis(DiagCode.SESSION_INACTIVE, "sessions=${s.sessionCount}, playing=0")

        s.pseudoTitle && s.hitSource == "L1" && s.l1OnlyOnPause ->
            Diagnosis(DiagCode.AVRCP_GATE, "title updated only while paused")

        s.hitSource == null ->
            Diagnosis(DiagCode.NO_CHANNEL, "keys=${s.metadataKeys.size}, hit=none")

        // 只有「带时间轴的歌词」才受进度回跳影响；L1 逐行推送按最后一行定位，
        // 与 progress 无关，报 CLOCK_UNSTABLE 是误伤（真机第一版即如此）。
        s.positionJumpCount > 2 && s.timedLyrics ->
            Diagnosis(DiagCode.CLOCK_UNSTABLE, "jumps=${s.positionJumpCount}, timed=true")

        // 按应用隐藏：窗口还在（viewAttached 仍为 true），只是按用户设置不画。
        // 排在「窗口没挂上」之前 —— 这一条能解释「什么都没显示」，正是用户想问的。
        s.overlaySuppressed ->
            Diagnosis(
                DiagCode.HIDDEN_BY_APP,
                "suppressed=true, foreground=${s.foregroundPkg ?: "unknown"}, " +
                    "inList=${s.foregroundHidden}"
            )

        !s.viewAttached ->
            Diagnosis(DiagCode.OVERLAY_NOT_ATTACHED, "viewAttached=false")

        !s.viewMeasured ->
            Diagnosis(DiagCode.OVERLAY_NOT_MEASURED, "measured=false")

        s.hiddenByRule ->
            Diagnosis(DiagCode.HIDDEN_BY_RULE, "hiddenByRule=true")

        else ->
            Diagnosis(
                DiagCode.OK,
                if (s.listenerConnected) "hit=${s.hitSource}"
                else "hit=${s.hitSource}, degraded=true"
            )
    }
}
