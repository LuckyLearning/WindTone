package com.luckylearning.windtone.data

import android.content.Context
import android.os.Build
import com.luckylearning.windtone.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志。
 *
 * 本应用不申请网络权限，崩溃信息无法上报，因此落地到
 * `filesDir/crash/crash-<时间戳>.txt`，用户可在设置页查看 / 导出。
 * 只保留最近 [KEEP] 份，避免长期占用空间。
 */
object CrashLog {

    private const val DIR_NAME = "crash"
    private const val KEEP = 5
    private const val CRASH_PREFIX = "crash-"
    private const val SEEN_PREFS = "crash_seen"
    private const val KEY_SEEN_MTIME = "seen_mtime"

    /** 与 Release 资产 mapping-v<versionName>.txt 同名，供 retrace 对版 */
    fun mappingAssetName(): String = "mapping-v${BuildConfig.VERSION_NAME}"

    fun appVersionLine(): String =
        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}"

    /** 在 Application.onCreate 里安装全局兜底处理器 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            // 交回系统默认处理，保证崩溃行为（对话框/退出）不被改变
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 按时间倒序排列的崩溃文件。
     *
     * 刻意排除 [LOW_MEMORY_FILE]：那份是滚动覆盖的单份快照，既不是崩溃记录，
     * 也不该参与 [latest] 的判定、更不该被 [prune] 按份数淘汰掉。
     */
    fun files(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name != LOW_MEMORY_FILE }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun count(context: Context): Int = files(context).size

    /** 最近一次崩溃的全文 */
    fun latest(context: Context): String? =
        files(context).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    /**
     * 最近一次**还没给用户看过**的崩溃（没有就返回 null）。
     *
     * 为什么需要这个：真机上出现过「装上就黑屏、连系统崩溃对话框都没有」的故障
     * （v1.1.13，车机 DiLink）。那种情况下应用一启动就没了，用户进不到「设置 → 诊断」，
     * 日志等于白记。所以启动时先把上一次的崩溃**主动摆到屏幕上**，用户拍张照就能把
     * 栈发过来。只在有「上一轮没看过的崩溃」时才出现，正常启动完全不受影响。
     *
     * 与 [markSeen] 配合：看过一次就不再重复挡路。
     */
    fun latestUnseen(context: Context): String? {
        val newest = files(context).firstOrNull { it.name.startsWith(CRASH_PREFIX) } ?: return null
        val seen = seenMtime(context)
        if (newest.lastModified() <= seen) return null
        return runCatching { newest.readText() }.getOrNull()
    }

    /** 把当前最新的一份崩溃标记为「已看过」，下次启动不再弹 */
    fun markSeen(context: Context) {
        val newest = files(context).firstOrNull { it.name.startsWith(CRASH_PREFIX) } ?: return
        context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_SEEN_MTIME, newest.lastModified()).apply()
    }

    private fun seenMtime(context: Context): Long =
        context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SEEN_MTIME, 0L)

    /** 全部崩溃日志合并（供导出） */
    /**
     * 全部崩溃/诊断日志合并导出。
     *
     * 导出时自动附上 mapping 版本号（A2），方便对着 Release 资产 retrace：
     * `bash scripts/retrace.sh --from-log <本文件> [mapping目录]`
     */
    fun allText(context: Context): String = buildString {
        appendLine("=== WindTone 崩溃日志导出 ===")
        appendLine("应用：${appVersionLine()}")
        appendLine("包名：${context.packageName}")
        appendLine("mapping：${mappingAssetName()}")
        appendLine(
            "还原：bash scripts/retrace.sh --from-log <本文件> [含 ${mappingAssetName()}.txt 的目录]",
        )
        appendLine()
        val list = files(context)
        if (list.isEmpty()) {
            appendLine("（暂无崩溃记录）")
        } else {
            list.forEach { f ->
                appendLine("══════════ ${f.name} ══════════")
                appendLine(runCatching { f.readText() }.getOrDefault("(读取失败)"))
                appendLine()
            }
        }
        // 内存快照单独附在最后：OOM 之前系统必定先发过 onTrimMemory，
        // 这份「死前最后一张照片」常常比崩溃栈更能说明内存是谁吃掉的
        val lowMem = File(dir(context), LOW_MEMORY_FILE)
        if (lowMem.isFile) {
            appendLine("══════════ ${LOW_MEMORY_FILE}（最近一次内存吃紧快照）══════════")
            appendLine(runCatching { lowMem.readText() }.getOrDefault("(读取失败)"))
        }
    }

    fun clear(context: Context) {
        files(context).forEach { runCatching { it.delete() } }
        runCatching { File(dir(context), LOW_MEMORY_FILE).delete() }
    }

    /**
     * 记录一条**非致命**的诊断信息。
     *
     * 用途：被 try/catch 吞掉、用户完全收不到反馈的失败。典型如悬浮歌词
     * 起不来（`addView` 抛异常 / 前台服务被系统拒绝）—— 这类异常只进
     * logcat 等于没记，用户在设置页看不到，无法排查。
     * 落到与崩溃同目录的 `diag-*.txt`，共享 [KEEP] 份的清理上限。
     */
    fun record(context: Context, tag: String, message: String) {
        val file = File(dir(context), "diag-${stamp()}.txt")
        val body = buildString {
            appendLine(header())
            appendLine("标记：$tag")
            appendLine()
            appendLine(message)
        }
        runCatching { file.writeText(body) }
        prune(context)
    }

    private fun header(): String = buildString {
        appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("应用：${appVersionLine()}")
        appendLine("mapping：${mappingAssetName()}")
        appendLine("线程：${Thread.currentThread().name}")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun write(context: Context, thread: Thread, t: Throwable) {
        val file = File(dir(context), "crash-${stamp()}.txt")
        val body = buildString {
            appendLine(header())
            appendLine()
            appendLine(t.stackTraceToString())
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine()
                appendLine("--- Caused by ---")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
        }
        // 先落栈：它是第一证物，不能因为后面采集快照失败而一起丢掉
        runCatching { file.writeText(body) }
        // 再补一份临终资源快照。分两次写就是为了让这一步彻底独立。
        runCatching { file.appendText(resourceSnapshot(context)) }
        prune(context)
    }

    /**
     * 崩溃发生那一刻的资源占用（v1.2.40）。
     *
     * 为什么栈之外还要这个：车机上「长时间运行后崩溃」至少有三种死法，而它们的栈
     * 长得完全一样（都指向最后一个倒霉的分配点）：
     *   1. **内存** —— 堆见顶，OOM；
     *   2. **线程** —— 每线程默认 1MB 栈，几百个线程就能把虚拟内存撑爆；
     *   3. **文件描述符** —— 进程上限通常 1024，泄漏到头就是 `Too many open files`。
     * 没有这份快照，只能看着栈猜；有了它，线程数 / fd 数 / RSS / 堆水位一摆，
     * 是哪一种一眼就分得清。
     *
     * 全程 `runCatching`：OOM 时连 `StringBuilder` 都可能失败，但那时栈已经写好了，
     * 这里失败只是少一段辅助信息，不会把证物一起赔进去。
     */
    /**
     * 记一次「系统内存吃紧」的资源快照，滚动覆盖单个文件 [LOW_MEMORY_FILE]。
     *
     * 为什么不走 [record]：崩溃/诊断日志只留 [KEEP] 份，而内存紧张会被系统高频
     * 触发 —— 让它也进去会把真正的崩溃记录挤掉。单独一个文件、只留最后一次，
     * 既不占位也不丢东西。
     *
     * 价值在于时序：OOM 之前系统**必定**先发过 `onTrimMemory`，所以这份
     * 「死前最后一张照片」往往比崩溃栈更能说明是谁把内存吃掉的 —— 栈只指向
     * 最后那个倒霉的分配点，而快照给出的是累积结果。
     */
    fun recordLowMemory(context: Context, level: Int) {
        val file = File(dir(context), LOW_MEMORY_FILE)
        val body = buildString {
            appendLine(header())
            appendLine("触发档位：$level")
            append(resourceSnapshot(context))
        }
        runCatching { file.writeText(body) }
    }

    /** 内存快照的文件名（导出诊断包时一并带上） */
    const val LOW_MEMORY_FILE = "lowmem-latest.txt"

    private fun resourceSnapshot(context: Context): String = buildString {
        appendLine()
        appendLine("=== 资源快照（崩溃时） ===")
        val rt = Runtime.getRuntime()
        appendLine("Java 堆：${mb(rt.totalMemory() - rt.freeMemory())} / ${mb(rt.maxMemory())} MB")
        runCatching {
            appendLine("native 堆：${mb(android.os.Debug.getNativeHeapAllocatedSize())} MB")
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = am?.let { android.app.ActivityManager.MemoryInfo().also { m -> it.getMemoryInfo(m) } }
            if (mi != null) appendLine("系统可用：${mb(mi.availMem)} MB   低内存标志：${mi.lowMemory}")
        }
        runCatching {
            val proc = com.luckylearning.windtone.diag.ProcStats.read()
            if (proc != null) {
                appendLine("线程数：${proc.threads}   文件描述符：${proc.fds}")
                appendLine("VmRSS：${mb(proc.vmRssKb * 1024)} MB   峰值 VmHWM：${mb(proc.vmHwmKb * 1024)} MB")
                // 两条判据：fd 逼近 1024 上限、线程数异常 —— 这两个数字比栈更能说明死因
                if (proc.fds > 900) appendLine("⚠ fd 已逼近进程上限（1024），高度怀疑句柄泄漏")
                if (proc.threads > 200) appendLine("⚠ 线程数异常，高度怀疑线程泄漏")
            }
        }
        runCatching {
            val cpu = com.luckylearning.windtone.diag.ProcStats.readCpu()
            if (cpu != null) {
                appendLine("进程 CPU：${"%.1f".format(cpu.processPercent)}%")
                cpu.top.take(5).forEach { t ->
                    appendLine("  ${"%.1f".format(t.percent)}%  ${t.name}")
                }
            }
        }
        runCatching {
            val s = CoverRepository.stats()
            appendLine(
                "封面缓存：位图 ${mb(s.bitmapBytes.toLong())}/${mb(s.bitmapMax.toLong())} MB" +
                    "   原始 ${mb(s.rawBytes.toLong())}/${mb(s.rawMax.toLong())} MB" +
                    "   磁盘 ${mb(s.diskBytes)} MB",
            )
        }
        runCatching {
            appendLine("诊断日志条数：${com.luckylearning.windtone.diag.DiagLog.size()}")
        }
    }

    /** 字节 → MB，保留一位小数（快照里一律用 MB，便于一眼比较） */
    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1048576.0)

    private fun prune(context: Context) {
        files(context).drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
