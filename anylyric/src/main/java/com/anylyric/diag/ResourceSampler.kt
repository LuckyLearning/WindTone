package com.anylyric.diag

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * 本进程的资源占用采样。
 *
 * 为什么自己采而不是让用户去翻系统的「应用信息」：车机上那个页面经常被裁掉，
 * 而且要来回切好几屏。这里的数字全部来自**本进程可读**的接口，不需要任何权限：
 *  · PSS / Java 堆 / 原生堆 —— `Debug` 与 `Runtime`，只看自己；
 *  · CPU —— `/proc/self/stat` 的 utime+stime，两次采样求差；
 *  · 线程数与 FD —— `/proc/self/status` 与 `/proc/self/fd`。
 *
 * 所有解析都写成**对字符串的纯函数**，因此在 JVM 单测里可以直接喂样例文本，
 * 不必依赖真机。
 */
object ResourceSampler {

    data class Sample(
        /** 整进程 PSS（KB）。ROM 裁掉 Debug.getMemoryInfo 时为 -1。 */
        val pssKb: Int,
        val javaUsedKb: Int,
        val javaMaxKb: Int,
        val nativeKb: Int,
        /** 相对上次采样的 CPU 占用（百分比）。首次采样或读不到 proc 时为 -1。 */
        val cpuPercent: Int,
        val threads: Int,
        val fds: Int,
        /** ART GC 累计次数，读不到时为 -1。 */
        val gcCount: Int,
        val uptimeSec: Long
    ) {
        /** 一行日志用：够短，能塞进日志缓冲还不刷屏。 */
        fun oneLine(): String = buildString(96) {
            append("pss=").append(mb(pssKb))
            append(" java=").append(mb(javaUsedKb)).append('/').append(mb(javaMaxKb))
            append(" native=").append(mb(nativeKb))
            append(" cpu=").append(if (cpuPercent < 0) "?" else "$cpuPercent%")
            append(" threads=").append(threads)
            append(" fd=").append(fds)
            append(" gc=").append(if (gcCount < 0) "?" else gcCount.toString())
            append(" uptime=").append(uptimeSec).append('s')
        }

        /** 报告用：多一行明细，便于对照是不是「内存涨了但 PSS 没涨」。 */
        fun detail(): String = buildString(128) {
            appendLine("  pss=${mb(pssKb)} java=${mb(javaUsedKb)}/${mb(javaMaxKb)} native=${mb(nativeKb)}")
            appendLine("  cpu=${if (cpuPercent < 0) "?" else "$cpuPercent%"} threads=$threads fd=$fds gc=${if (gcCount < 0) "?" else gcCount}")
            append("  uptime=${uptimeSec}s")
        }

        private fun mb(kb: Int): String =
            if (kb < 0) "?" else String.format(Locale.US, "%.1fMB", kb / 1024f)
    }

    private var lastTicks = -1L
    private var lastAtMs = -1L
    private val startedAt = SystemClock.elapsedRealtime()

    fun sample(): Sample {
        val now = SystemClock.elapsedRealtime()
        val ticks = readCpuTicks()
        val percent = cpuPercent(
            deltaTicks = if (lastTicks >= 0 && ticks >= 0) ticks - lastTicks else -1,
            deltaMs = if (lastAtMs >= 0) now - lastAtMs else -1,
            hertz = clockTicksPerSecond()
        )
        if (ticks >= 0) {
            lastTicks = ticks
            lastAtMs = now
        }

        val status = readText("/proc/self/status")
        val runtime = Runtime.getRuntime()
        val javaMax = (runtime.maxMemory() / 1024L).toInt()
        val javaUsed = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).toInt()

        return Sample(
            pssKb = readPssKb(),
            javaUsedKb = javaUsed,
            javaMaxKb = javaMax,
            nativeKb = (Debug.getNativeHeapAllocatedSize() / 1024L).toInt(),
            cpuPercent = percent,
            threads = parseStatusValue(status, "Threads:").takeIf { it > 0 }
                ?: Thread.activeCount(),
            fds = countFds(),
            gcCount = readGcCount(),
            uptimeSec = (SystemClock.elapsedRealtime() - maxOf(startedAt, processStartUptime())) / 1000
        )
    }

    // ---------------------------------------------------------------- 纯解析（可单测）

    /**
     * 从 `/proc/self/stat` 取出 utime+stime（单位：时钟滴答）。
     *
     * 第二个字段是带括号的进程名，里面可能有空格与括号，所以必须从**最后一个右括号**
     * 之后开始数：state 是第 3 个字段，utime/stime 是第 14/15 个，即之后第 11/12 个。
     */
    fun parseCpuTicks(stat: String?): Long {
        if (stat.isNullOrBlank()) return -1
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return -1
        val fields = stat.substring(close + 2).split(' ')
        if (fields.size < 13) return -1
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return -1
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return -1
        return utime + stime
    }

    /**
     * CPU 占用百分比。
     *
     * 用「本次与上次之间的滴答差 ÷ 经过时间」算，因此是**区间均值**而不是瞬时值 ——
     * 车载场景关心的是「这个应用到底在不在偷偷烧 CPU」，区间均值比瞬时抖动更有意义。
     */
    fun cpuPercent(deltaTicks: Long, deltaMs: Long, hertz: Long): Int {
        if (deltaTicks < 0 || deltaMs <= 0 || hertz <= 0) return -1
        val cpuMs = deltaTicks * 1000.0 / hertz
        return ((cpuMs * 100.0 / deltaMs).toInt()).coerceIn(0, 999)
    }

    /** 从 `/proc/self/status` 里取一个整数字段，例如 `Threads:` / `VmRSS:`。 */
    fun parseStatusValue(status: String?, key: String): Int {
        if (status.isNullOrBlank() || key.isEmpty()) return -1
        for (line in status.lineSequence()) {
            if (!line.startsWith(key)) continue
            val value = line.substring(key.length).trim().split(' ').firstOrNull()
            return value?.toIntOrNull() ?: return -1
        }
        return -1
    }

    // ---------------------------------------------------------------- 读取

    private fun readCpuTicks(): Long = parseCpuTicks(readText("/proc/self/stat"))

    /** USER_HZ 在 Android 上恒为 100；拿不到 sysconf 时退回这个值。 */
    private fun clockTicksPerSecond(): Long = 100L

    private fun readPssKb(): Int = try {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss
    } catch (_: Throwable) {
        -1
    }

    private fun readGcCount(): Int = try {
        Debug.getRuntimeStat("art.gc.gc-count")?.toIntOrNull() ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun countFds(): Int = try {
        File("/proc/self/fd").list()?.size ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun processStartUptime(): Long = try {
        Process.getStartUptimeMillis()
    } catch (_: Throwable) {
        0L
    }

    private fun readText(path: String): String? = try {
        File(path).readText()
    } catch (_: Throwable) {
        null
    }
}
