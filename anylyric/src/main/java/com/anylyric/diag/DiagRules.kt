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
    /** 手动锁定的播放器包名；null = 自动选择。与 [chosenPackage] 不同就说明锁定的那个没在跑。 */
    val pinnedPackage: String? = null,
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
    /**
     * 歌词窗的实际像素尺寸（0 = 还没量到）。
     *
     * 必须报出来：`[RESOURCE] graphics` 是 GPU 按窗口面积分配的 buffer，
     * 没有尺寸就无法判断「57MB 是因为窗口太大，还是因为反复 resize」。
     */
    val windowWidth: Int = 0,
    val windowHeight: Int = 0,
    /**
     * 进程启动以来窗口尺寸变化次数。
     *
     * 自适应宽度（默认）下，每换一句歌词长度不同 → 窗口 resize → Surface 重新分配 buffer。
     * 这个计数持续增长就说明「每句都在重新分配图形缓冲」，是 graphics 偏高的直接嫌疑。
     */
    val windowResizes: Int = 0,
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
    /** 系统设置里是否勾着无障碍服务。`false` + `a11yConnected=true` 不可能同时出现；
     *  `true` + `a11yConnected=false` = 勾着但服务被杀（等系统重连即可）。 */
    val a11yGranted: Boolean = false,
    /** 无障碍服务实际订阅的事件类型位掩码；32（`TYPE_WINDOW_STATE_CHANGED`）缺失 = 订阅没生效。 */
    val a11yEventTypes: Int = 0,
    val hideByAppEnabled: Boolean = false,
    val hiddenAppCount: Int = 0,
    val foregroundPkg: String? = null,
    val foregroundHidden: Boolean = false,
    val overlaySuppressed: Boolean = false,
    /** 让位是不是因为「本应用自己的界面在屏上」（设置页/诊断页），而不是名单命中。 */
    val hideByOwnUi: Boolean = false,

    /**
     * 车机直连通道（BYD 媒体中心 SDK）的状态。
     *
     * `null` = 非 BYD 设备或没尝试；`registered(meta=N, state=yes)` = 已注册且在推数据。
     * 这一行回答的是「没有通知使用权时，还有没有别的东西在供数据」——
     * 0.2.21 及以前的报告里这个信息完全缺失，只能靠猜。
     */
    val externalSource: String? = null,

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

    /** 开「按应用隐藏」但无障碍服务没连：该功能完全不会生效，需要用户去开。 */
    A11Y_DISCONNECTED,

    /** 按应用隐藏生效中：这是用户自己设的，不是故障。 */
    HIDDEN_BY_APP,

    /** 手动锁定的播放器当前没有媒体会话：不是取不到歌词，是锁定的那个没在放。 */
    PINNED_NO_SESSION,

    /**
     * 没有「通知使用权」，但车机直连通道在工作。
     *
     * 这不是故障：部分车厂 ROM 把通知使用权授权页整页拦掉（DiLink5 →「多媒体不支持」），
     * 而 BYD 媒体中心的第三方 Binder 服务不需要任何权限就能给歌名 / 歌手 / 歌词。
     * 报成 PERMISSION_LISTENER 会让用户以为「没授权就等于不能用」，然后一直去修一个修不好的开关。
     */
    EXTERNAL_ONLY,
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
        // 直连通道已经拿到歌词、但悬浮窗没授权：窗口根本建不出来，先报这个 ——
        // 否则会给出一个绿色的「正在工作」，而用户屏上什么都没有。
        !s.listenerGranted && s.externalSource != null && s.hitSource != null &&
            !s.overlayGranted ->
            Diagnosis(DiagCode.PERMISSION_OVERLAY, "canDrawOverlays=false, external=${s.externalSource}")

        // 最先判：没有授权但直连通道已经拿到歌词 —— 这是「能用的状态」，不是待修的故障。
        !s.listenerGranted && s.externalSource != null && s.hitSource != null ->
            Diagnosis(
                DiagCode.EXTERNAL_ONLY,
                "listenerGranted=false, external=${s.externalSource}, hit=${s.hitSource}"
            )

        !s.listenerGranted ->
            Diagnosis(
                DiagCode.PERMISSION_LISTENER,
                if (s.externalSource == null) {
                    "listenerGranted=false"
                } else {
                    // 通道绑上了但没出歌词：报告里必须带上通道状态，否则无法区分
                    // 「车机没这个服务」与「有服务但没在推」。
                    "listenerGranted=false, external=${s.externalSource}, hit=none"
                }
            )

        !s.overlayGranted ->
            Diagnosis(DiagCode.PERMISSION_OVERLAY, "canDrawOverlays=false")

        // 已授权但服务未绑定：getActiveSessions 的权限边界是授权列表，L0/L1 与进度
        // 照常可用，缺的只有 L2 与推送事件。所以只有「连不上 且 也没拿到歌词」才算
        // 授权侧问题；已经拿到歌词就是降级可用，报故障反而是误伤。
        !s.listenerConnected && s.hitSource == null ->
            Diagnosis(DiagCode.LISTENER_UNBOUND, "granted=true, connected=false, hit=none")

        // 开了「按应用隐藏」但无障碍服务没连：名单一个都不会生效。
        // 放在歌词类问题之后、会话之前 —— 它不影响出不出歌词，只影响「该不该让位」。
        s.hideByAppEnabled && !s.a11yConnected ->
            Diagnosis(
                DiagCode.A11Y_DISCONNECTED,
                "hideByApp=true, granted=${s.a11yGranted}, hiddenApps=${s.hiddenAppCount}"
            )

        // 锁定了播放器、但它没有会话：这条要在「没有会话」之前判 ——
        // 车机上通常别的会话（导航）还在，用户看到的却是空窗，说明的必须是「锁定的那个没在放」。
        s.pinnedPackage != null && s.chosenPackage == null ->
            Diagnosis(
                DiagCode.PINNED_NO_SESSION,
                "pinned=${s.pinnedPackage}, sessions=${s.sessionCount}, " +
                    "playing=${s.playingCount}"
            )

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
        // 但「因为本应用自己的页面在屏上」不算 —— 诊断页自己就开着，
        // 这一条若照报，用户会看到「按应用隐藏生效中」，而名单其实没命中。
        s.overlaySuppressed && !s.hideByOwnUi ->
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
