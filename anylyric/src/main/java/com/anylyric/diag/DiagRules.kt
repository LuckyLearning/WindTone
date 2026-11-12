package com.anylyric.diag

/** 一次探测的事实快照。由 Android 层填充，判因规则只读它。 */
data class ProbeSnapshot(
    val listenerConnected: Boolean = false,
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
    val displayCount: Int = 1,
    val fontScale: Float = 1f
)

enum class DiagCode {
    PERMISSION_LISTENER,
    PERMISSION_OVERLAY,
    NO_SESSION,
    SESSION_INACTIVE,
    AVRCP_GATE,
    NO_CHANNEL,
    CLOCK_UNSTABLE,
    OVERLAY_NOT_ATTACHED,
    OVERLAY_NOT_MEASURED,
    HIDDEN_BY_RULE,
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
        !s.listenerConnected ->
            Diagnosis(DiagCode.PERMISSION_LISTENER, "listenerConnected=false")

        !s.overlayGranted ->
            Diagnosis(DiagCode.PERMISSION_OVERLAY, "canDrawOverlays=false")

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

        !s.viewAttached ->
            Diagnosis(DiagCode.OVERLAY_NOT_ATTACHED, "viewAttached=false")

        !s.viewMeasured ->
            Diagnosis(DiagCode.OVERLAY_NOT_MEASURED, "measured=false")

        s.hiddenByRule ->
            Diagnosis(DiagCode.HIDDEN_BY_RULE, "hiddenByRule=true")

        else ->
            Diagnosis(DiagCode.OK, "hit=${s.hitSource}")
    }
}
