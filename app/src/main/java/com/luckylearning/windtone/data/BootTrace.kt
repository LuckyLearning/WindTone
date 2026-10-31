package com.luckylearning.windtone.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 冷启动阶段追踪。
 *
 * 为什么需要它：车机（DiLink）上出现过「装上打开全黑、连系统崩溃对话框都没有」。
 * 这种表现至少有两种完全不同的成因，处理方式也完全相反：
 *
 *  1. `Application.onCreate` 里就抛异常 —— 进程被秒杀，`MainActivity` 根本没建起来。
 *     此时**任何界面技巧都救不回来**（崩溃页也来不及显示），必须把 Application 里的
 *     每一步拆开兜住。
 *  2. 界面建起来了但一帧都没画出去 —— 渲染 / GL 驱动问题。
 *     此时能看到「进程活着、Activity 活着，就是不出画面」。
 *
 * 光看「黑屏」分不出是哪一种。所以这里把每一步的耗时落盘，出首帧时补一行
 * [STAGE_FRAME]；下次启动时如果上一轮的记录里**没有** frame，就说明上次没画成。
 * 这个判据是纯数据，能被单测钉住，也是 [com.luckylearning.windtone.ui.BootDiagView]
 * 决定要不要弹诊断页的依据。
 *
 * 记录本身很便宜：冷启动只写十几行，滚动覆盖单个文件。
 *
 * v1.2.40：写盘方式从「每记一行就 append 一次」改成攒批。
 * 原实现每一条 `mark()` 都是一次 `open → write → close` 的同步主线程 I/O，
 * 一次冷启动十五行就是十五次；诊断包实测的 `onCreate → setContent` 那 2.45s
 * 里，这是唯一成规模且纯粹浪费的开销。现在只在三类时刻真正落盘：
 *   1. `app:onCreate` —— 进程确实起来了，这条不能丢；
 *   2. 任何 `fail:` 行 —— 出错时的上下文是最要紧的证物；
 *   3. 首帧 / 攒够 [FLUSH_THRESHOLD] 行。
 */
object BootTrace {

    private const val DIR_NAME = "boot"
    private const val CURRENT = "trace.txt"
    private const val PREVIOUS = "prev.txt"

    /** 攒够这么多行落一次盘（fail / 首帧 / 首行不受此限，见类注释） */
    private const val FLUSH_THRESHOLD = 12

    const val STAGE_APP = "app:onCreate"
    const val STAGE_ACTIVITY = "activity:onCreate"
    const val STAGE_CONTENT = "activity:setContent"
    const val STAGE_FRAME = "frame:first"

    /** 诊断页已经显示出来（这一行同时证明「窗口能画」） */
    const val STAGE_DIAG_SHOWN = "diag:shown"

    /** 失败行统一前缀，便于从记录里挑出来 */
    private const val FAIL_PREFIX = "fail:"

    /** 本进程的起点；`begin()` 时写入 */
    @Volatile
    private var startAt = 0L

    /** 待落盘的行；`mark()` 可能来自主线程以外的兜底路径，所以加锁 */
    private val lock = Any()
    private val pending = StringBuilder(512)
    private var pendingLines = 0

    /**
     * 哪些阶段必须立刻落盘，不能等攒够。
     *
     * 判据是「这一行本身是不是关键证物」：进程起来了、首帧画出来了、某一步失败了。
     * 抽成纯函数是为了单测能直接断言，不必真的去碰文件系统。
     */
    internal fun mustFlushImmediately(stage: String): Boolean =
        stage == STAGE_APP || stage == STAGE_FRAME || stage.startsWith(FAIL_PREFIX)

    /**
     * 在 `Application.onCreate` 的最开头调用。
     *
     * 把上一轮的文件改名成 prev.txt 保留下来 —— 上一轮的记录正是我们想看的证物，
     * 不能因为本次启动就冲掉。
     */
    fun begin(context: Context) {
        // 先把仍攒着的行落下去 —— 它们属于上一轮，一旦下面把 trace.txt 改名成
        // prev.txt，这些行就再也回不到该去的那一轮里了（表现为上一轮只剩第一行）。
        // 进程被复用时的残留同理：该留的留，不该留的由 rollover 自然覆盖。
        flush(context)
        val dir = dir(context)
        runCatching {
            File(dir, PREVIOUS).delete()
            val current = File(dir, CURRENT)
            if (current.exists()) current.renameTo(File(dir, PREVIOUS))
        }
        startAt = SystemClock.elapsedRealtime()
        mark(context, STAGE_APP)
    }

    /** 记一行阶段。`note` 里不能有换行（会破坏行格式），内部会折成 ` / `。 */
    fun mark(context: Context, stage: String, note: String? = null) {
        val elapsed = if (startAt == 0L) 0L else SystemClock.elapsedRealtime() - startAt
        val line = buildString {
            append(elapsed)
            append('\t')
            append(stage.replace('\n', ' '))
            if (!note.isNullOrBlank()) {
                append('\t')
                append(note.replace("\r", " ").replace('\n', ' ').replace('\t', ' '))
            }
            append('\n')
        }
        val force = mustFlushImmediately(stage)
        synchronized(lock) {
            pending.append(line)
            pendingLines++
            if (force || pendingLines >= FLUSH_THRESHOLD) flushLocked(context)
        }
    }

    /** 主动把攒着的行落盘（首帧之后、进后台之前用） */
    fun flush(context: Context) {
        synchronized(lock) { flushLocked(context) }
    }

    /** 调用方必须持有 [lock] */
    private fun flushLocked(context: Context) {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.setLength(0)
        pendingLines = 0
        runCatching { File(dir(context), CURRENT).appendText(text) }
    }

    /**
     * 记一条失败。
     *
     * 与 [mark] 分开是因为它同时要落一份到 [CrashLog] —— 用户能在「设置 → 诊断」里
     * 原样导出，不必依赖这个只留一轮的 trace 文件。
     */
    fun fail(context: Context, stage: String, error: Throwable) {
        val brief = error.javaClass.simpleName + ": " + (error.message ?: "")
        mark(context, FAIL_PREFIX + stage, brief)
        CrashLog.record(context, "boot:$stage", error.stackTraceToString())
    }

    /** 出首帧时调用一次 */
    fun markFrame(context: Context) {
        mark(context, STAGE_FRAME)
    }

    /** 上一轮的记录；没有（首次安装、或文件被杀）返回 null */
    fun previous(context: Context): BootRecord? {
        val file = File(dir(context), PREVIOUS)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return BootRecord(parse(text))
    }

    /**
     * 上一轮**没画出首帧**的记录；正常启动返回 null。
     *
     * 有一个例外要排除：上一轮停在诊断页上、用户自己没点「进入应用」。
     * 这种情况下同样没有 frame，但那不是故障 —— 若也报出来，用户只要不点进去，
     * 每次启动都会被这张页挡一次，永远出不来。判据落在「最后一行是不是 diag:shown」：
     * 只要他点过「进入应用」，最后一行就会被 [STAGE_CONTENT] 覆盖，信号不会丢。
     */
    fun lastIncompleteBoot(context: Context): BootRecord? =
        previous(context)?.takeIf { isIncomplete(it) }

    /**
     * 「上一轮没画出首帧」的判据，与 [lastIncompleteBoot] 共用同一份。
     *
     * 拆出来是为了让调用方只读一次文件就能同时拿到完整记录和这个判定 ——
     * 之前 MainActivity 是 `previous()` 和 `lastIncompleteBoot()` 各调一次，
     * 同一个 prev.txt 开了两遍流。
     */
    fun isIncomplete(record: BootRecord): Boolean = !record.reachedFrame && !record.stoppedAtDiag

    fun parse(text: String): List<BootLine> = text.lineSequence()
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split('\t')
            val ms = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val stage = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BootLine(ms, stage, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
        }
        .toList()

    /** 供诊断页 / 剪贴板使用的纯文本报告 */
    fun summary(record: BootRecord): String = buildString {
        appendLine(
            "上一轮启动：" + when {
                record.reachedFrame -> "出过首帧"
                record.stoppedAtDiag -> "停在诊断页（没点「进入应用」，所以没有首帧记录）"
                else -> "没有出首帧"
            }
        )
        appendLine("共 ${record.lines.size} 步，最后一步：${record.lastStage ?: "（空）"}")
        record.failures.forEach { appendLine("失败：${it.stage.removePrefix(FAIL_PREFIX)} — ${it.note ?: ""}") }
        appendLine("--- 阶段 ---")
        record.lines.forEach { line ->
            val note = line.note?.let { "  $it" } ?: ""
            appendLine("${line.elapsedMs}ms  ${line.stage}$note")
        }
    }.trimEnd()

    /** 设备信息（诊断页顶部那一段） */
    fun deviceInfo(context: Context): String = buildString {
        appendLine("应用：${appVersion(context)}")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("进程：${if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "含 64 位" else "仅 32 位"} / ${System.getProperty("os.arch")}")
        append("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("未知")

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
}

/** trace 里的一行 */
data class BootLine(val elapsedMs: Long, val stage: String, val note: String? = null)

/** 一轮冷启动的完整记录 */
data class BootRecord(val lines: List<BootLine>) {

    /** 有没有画出过第一帧 —— 判断「进程活着但没画面」的唯一依据 */
    val reachedFrame: Boolean get() = lines.any { it.stage == BootTrace.STAGE_FRAME }

    /** 上一轮停在诊断页上没往下走（用户自己没点「进入应用」），不算故障 */
    val stoppedAtDiag: Boolean get() = lastStage == BootTrace.STAGE_DIAG_SHOWN

    val lastStage: String? get() = lines.lastOrNull()?.stage

    val failures: List<BootLine> get() = lines.filter { it.stage.startsWith("fail:") }
}
