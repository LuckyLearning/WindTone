package com.anylyric.diag

import android.os.Build
import com.anylyric.BuildConfig
import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.TitleTimeline

/**
 * 采集与报告。
 *
 * 零成本前提：所有调用点都写成 `if (Probe.enabled) Probe.write(...)`，
 * 关闭时既不分配 lambda，也不拼字符串。这里不做任何后台线程、不做落盘，
 * 缓冲上限固定条数（诊断文本很短，不需要按堆比例；上限本身就是硬边界）。
 */
object Probe {

    @Volatile
    var enabled: Boolean = false
        private set

    private const val MAX_LINES = 300

    private val log = ArrayDeque<String>()
    private var dropped = 0

    @Volatile
    var snapshot: ProbeSnapshot = ProbeSnapshot()

    @Volatile
    var metadataPreview: Map<String, String> = emptyMap()

    @Volatile
    var notificationFields: Map<String, String> = emptyMap()

    @Volatile
    var attempts: List<LyricResult> = emptyList()

    @Volatile
    var titleEvents: List<TitleTimeline.Event> = emptyList()

    @Volatile
    var hitSource: String? = null

    /** 最后一次真正交给 WindowManager 的文本。用于回答「取到歌词了为什么屏幕没有」。 */
    @Volatile
    var lastRender: String = "-"

    private val startedAt = System.currentTimeMillis()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clear()
    }

    @Synchronized
    fun write(segment: String, message: String) {
        if (!enabled) return
        if (log.size >= MAX_LINES) {
            log.removeFirst()
            dropped++
        }
        log.addLast("[$segment] ${stamp()} $message")
    }

    @Synchronized
    fun lines(): List<String> = log.toList()

    @Synchronized
    fun clear() {
        log.clear()
        dropped = 0
        // 计数器一起归零，否则「清空后」的第一分钟增量会算出负数
        Perf.reset()
    }

    /** 设备内可读的完整报告：九段事实 + 自动判因。 */
    fun buildReport(): String {
        val s = snapshot
        val diagnosis = DiagRules.evaluate(s)
        val sb = StringBuilder(4096)

        sb.appendLine("=== AnyLyric probe report ===")
        sb.appendLine("time=${System.currentTimeMillis()} uptime=${stamp()}")
        sb.appendLine("version=${BuildConfig.VERSION_NAME} api=${Build.VERSION.SDK_INT}")
        sb.appendLine("device=${Build.MANUFACTURER}/${Build.MODEL} brand=${Build.BRAND}")
        sb.appendLine("package=${BuildConfig.APPLICATION_ID}")
        sb.appendLine()

        sb.appendLine("[ENV]")
        sb.appendLine("  displayCount=${s.displayCount} fontScale=${s.fontScale}")
        sb.appendLine()

        sb.appendLine("[PERMISSION]")
        // degraded = 授权在、服务没绑，靠低频查询代偿；此时功能可用但不是最佳状态
        val degraded = s.listenerGranted && !s.listenerConnected
        sb.appendLine(
            "  listenerGranted=${s.listenerGranted} listenerConnected=${s.listenerConnected} " +
                "degraded=$degraded canDrawOverlays=${s.overlayGranted}"
        )
        sb.appendLine()

        sb.appendLine("[SESSION]")
        sb.appendLine("  sessions=${s.sessionCount} playing=${s.playingCount}")
        sb.appendLine("  chosen=${s.chosenPackage ?: "none"}")
        sb.appendLine("  title=${oneLine(s.title)} artist=${oneLine(s.artist)} duration=${s.durationMs}")
        sb.appendLine()

        sb.appendLine("[METADATA KEYS] count=${s.metadataKeys.size}")
        if (metadataPreview.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            metadataPreview.forEach { (k, v) -> sb.appendLine("  $k = $v") }
        }
        sb.appendLine()

        sb.appendLine("[NOTIFICATION FIELDS]")
        if (notificationFields.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            notificationFields.forEach { (k, v) -> sb.appendLine("  $k = $v") }
        }
        sb.appendLine()

        sb.appendLine("[TITLE TIMELINE] events=${titleEvents.size} pseudo=${s.pseudoTitle}")
        titleEvents.forEach { e -> sb.appendLine("  ${e.atMs} ${oneLine(e.title)}") }
        sb.appendLine()

        sb.appendLine("[SOURCE ATTEMPTS]")
        if (attempts.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            attempts.forEach { r ->
                when (r) {
                    is LyricResult.Hit -> sb.appendLine("  ${r.sourceId} HIT lines=${r.lines.size} cost=${r.costMs}ms")
                    is LyricResult.Miss -> sb.appendLine("  ${r.sourceId} MISS reason=${r.reason} cost=${r.costMs}ms")
                }
            }
        }
        sb.appendLine()

        sb.appendLine("[CLOCK] jumps=${s.positionJumpCount} timed=${s.timedLyrics} l1OnlyOnPause=${s.l1OnlyOnPause}")
        sb.appendLine("[WINDOW] attached=${s.viewAttached} measured=${s.viewMeasured} hiddenByRule=${s.hiddenByRule}")
        // 按应用隐藏紧跟窗口：两者合起来回答「窗口为什么什么都没画」。
        // 名字选对了却不隐藏，通常是 a11yConnected=false（勾了但服务没跑）或 foreground 报的不是那个包。
        sb.appendLine(
            "[HIDE] a11yConnected=${s.a11yConnected} enabled=${s.hideByAppEnabled} " +
                "hiddenApps=${s.hiddenAppCount}"
        )
        sb.appendLine(
            "  foreground=${s.foregroundPkg ?: "unknown"} inList=${s.foregroundHidden} " +
                "suppressed=${s.overlaySuppressed}"
        )
        sb.appendLine("[RENDER] $lastRender")
        sb.appendLine("[PROCESS] cachedLogLines=${lines().size} dropped=$dropped")
        sb.appendLine()

        // 资源占用放在窗口之后：先看「有没有在工作」，再看「工作花了多少」。
        // 车机上「歌词卡顿/发热」这类反馈，先排除是不是本进程在涨内存或空转。
        sb.appendLine("[RESOURCE]")
        sb.appendLine(ResourceSampler.sample().detail())
        sb.appendLine()

        // 工作量紧跟在资源后面：cpu 高但工作量低 = 单点开销；两者一起高 = 事件量放大。
        sb.appendLine("[WORK] ${Perf.oneLine()}")
        sb.appendLine()

        sb.appendLine("=== CONCLUSION ===")
        sb.appendLine("code=${diagnosis.code}")
        sb.appendLine("evidence=${diagnosis.evidence}")
        sb.appendLine()

        sb.appendLine("[LOG]")
        lines().forEach { sb.appendLine("  $it") }

        return sb.toString()
    }

    private fun stamp(): String {
        val delta = System.currentTimeMillis() - startedAt
        val sec = delta / 1000
        val ms = delta % 1000
        return "+${sec}s${ms.toString().padStart(3, '0')}"
    }

    /** 报告里不出现完整路径、不出现整段歌词：逐行截断到 40 字。 */
    private fun oneLine(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        val flat = value.replace('\n', ' ').trim()
        return if (flat.length <= 40) flat else flat.substring(0, 40) + "…"
    }
}
