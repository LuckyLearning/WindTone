package com.luckylearning.windtone.diag

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 进程级原始指标（2026-10-31 新增）。
 *
 * ## 为什么有它
 *
 * 车机拿不到 adb（DiLink 从 2026 款起还收紧了），诊断包是唯一取数通道。而
 * `[内存]` 段原有两项对不上账：`Java 堆` 是「已分配 − 空闲」，含未回收垃圾；
 * `native 堆` 只有一个总数，说明不了「这 232MB 是谁」。
 *
 * 这里把 dumpsys 会看的那几项改成**应用自己读 `/proc/self`**：
 *
 *  - `status`      → 线程数 / 文件描述符数 / VmRSS / VmHWM；
 *  - `stat`        → 进程级 CPU 时间，配 `/proc/uptime` 算「启动至今平均 CPU%」；
 *  - `task/<tid>/stat` → 每个线程的 CPU 时间，算线程级占比（找空转线程）；
 *  - `smaps`       → 把占用按**内存映射**归类：libc malloc / GPU 驱动 / .so /
 *    ART / 线程栈 / 文件映射。位图、JPEG 解码缓冲、解码器输出都落在
 *    `libc malloc` 这一类里。
 *
 * ## 代价
 *
 * 只在生成诊断包时读一次，不驻留、不轮询。smaps 约 1~2 MB 文本、解析 20~50 ms，
 * 全部发生在用户点「导出」那一刻，与播放线程无关。**这是刻意的**：为了定位问题
 * 而加一个常驻采样器，等于用新的占用去查占用。
 *
 * ## 可测性
 *
 * 解析逻辑全部拆成「文本 → 数据」的纯函数（[parseStatus] / [parseStat] /
 * [parseUptimeSec] / [parseSmaps] / [classify]），JVM 单测直接喂字符串断言，
 * 不需要真机、不依赖 `/proc`。
 */
object ProcStats {

    /** 进程规模。`fds` 读不到时为 -1 */
    data class Proc(
        val threads: Int,
        val fds: Int,
        val vmRssKb: Long,
        val vmHwmKb: Long,
    )

    /** 单线程 CPU 占比（相对「进程启动至今」这段墙钟时间） */
    data class ThreadCpu(val name: String, val percent: Double)

    /** 进程级 CPU：平均占比 + 前几个热点线程 */
    data class Cpu(val processPercent: Double, val top: List<ThreadCpu>)

    /** 一类内存映射的占用。三项都给：Private_Dirty 看「自己吃掉的」，Pss 看「公平分摊」 */
    data class MemBucket(
        val label: String,
        val privateDirtyKb: Long,
        val rssKb: Long,
        val pssKb: Long,
    )

    data class Smaps(val buckets: List<MemBucket>) {
        val privateDirtyKb: Long get() = buckets.sumOf { it.privateDirtyKb }
        val rssKb: Long get() = buckets.sumOf { it.rssKb }
        val pssKb: Long get() = buckets.sumOf { it.pssKb }
    }

    /** `/proc/self/stat` 里我们关心的三个字段（单位：时钟滴答） */
    data class StatTicks(val utime: Long, val stime: Long, val starttime: Long) {
        val total: Long get() = utime + stime
    }

    // ------------------------------------------------------------------ 读

    fun read(): Proc? = runCatching {
        val status = readText("/proc/self/status") ?: return null
        val fds = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)
        parseStatus(status, fds)
    }.getOrNull()

    /**
     * 进程与线程的 CPU 占比。
     *
     * 分母用「当前时刻 − 进程启动时刻」而不是「采样窗口」，所以得到的是
     * **进程启动至今的平均 CPU%**：播放中稳定在 1%~3% 属正常，若到 8% 以上
     * 说明有线程在空转。线程榜用来指出是哪个。
     */
    fun readCpu(): Cpu? = runCatching {
        val selfText = readText("/proc/self/stat") ?: return null
        val self = parseStat(selfText) ?: return null
        val uptime = readText("/proc/uptime")?.let { parseUptimeSec(it) } ?: return null
        val clk = clkTck()
        val elapsedSec = uptime - self.starttime.toDouble() / clk
        if (elapsedSec <= 0.5) return null

        val procPercent = self.total / clk / elapsedSec * 100.0

        val top = runCatching {
            File("/proc/self/task").list()?.mapNotNull { tid ->
                val text = readText("/proc/self/task/$tid/stat") ?: return@mapNotNull null
                val ticks = parseStat(text) ?: return@mapNotNull null
                ThreadCpu(threadName(text), ticks.total / clk / elapsedSec * 100.0)
            }?.sortedByDescending { it.percent }?.take(TOP_THREADS)
        }.getOrNull().orEmpty()

        Cpu(procPercent, top)
    }.getOrNull()

    /** 读 `/proc/self/smaps` 并归类；本机限制读取时返回 null（不影响诊断包其它段） */
    fun readSmaps(): Smaps? = runCatching {
        val file = File("/proc/self/smaps")
        if (!file.canRead()) return null
        val buckets = file.useLines { parseSmaps(it) }
        if (buckets.isEmpty()) return null
        Smaps(buckets)
    }.getOrNull()

    // ------------------------------------------------------------ 纯解析

    /**
     * `/proc/self/status` 解析。
     *
     * 只挑四行，不整体切分 —— 这个文件有 50 多行，逐行 `startsWith` 比建 map 便宜。
     */
    internal fun parseStatus(text: String, fds: Int = -1): Proc {
        var threads = -1
        var rss = 0L
        var hwm = 0L
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Threads:") -> threads = numberAfterColon(line).toInt()
                line.startsWith("VmRSS:") -> rss = numberAfterColon(line)
                line.startsWith("VmHWM:") -> hwm = numberAfterColon(line)
            }
        }
        return Proc(threads.coerceAtLeast(-1), fds, rss, hwm)
    }

    /**
     * `/proc/[pid|tid]/stat` 解析。
     *
     * 坑在第二字段 `(comm)`：进程名里可以带空格甚至括号（如 `(Web Content)`），
     * 按空格切会错位。所以先取「第一个 `(` 到最后一个 `)`」作为名字，
     * 再对剩余部分切分 —— 剩余部分里 `state` 是第 3 字段，因此
     * `utime`(14) / `stime`(15) / `starttime`(22) 的偏移分别是 11 / 12 / 19。
     */
    internal fun parseStat(text: String): StatTicks? {
        val close = text.lastIndexOf(')')
        val open = text.indexOf('(')
        if (open < 0 || close <= open) return null
        val rest = text.substring(close + 1).trim().split(' ')
        // 需要覆盖到 starttime（偏移 19）
        if (rest.size < 20) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        val start = rest[19].toLongOrNull() ?: return null
        return StatTicks(utime, stime, start)
    }

    /** 线程名（`(comm)`），空则给占位 */
    internal fun threadName(text: String): String {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return "?"
        return text.substring(open + 1, close).ifBlank { "?" }
    }

    /** `/proc/uptime` = `"12345.67 45678.90"`，取第一个（秒） */
    internal fun parseUptimeSec(text: String): Double? =
        text.trim().substringBefore(' ').toDoubleOrNull()

    /**
     * `/proc/self/smaps` 按映射归类。
     *
     * 每个条目的头部形如
     * `7f8a000000-7f8a123000 rw-p 00000000 00:00 0    [anon:libc_malloc]`，
     * 后面跟 `Size/Rss/Pss/Private_Dirty/...` 若干行。这里只累加三个数值：
     * `Private_Dirty`（自己独占且已写脏 —— 泄漏看它）、`Rss`、`Pss`。
     *
     * 归类粒度刻意粗：目标是「是位图还是 GPU 还是 .so」，不是逐条映射列表。
     */
    internal fun parseSmaps(lines: Sequence<String>): List<MemBucket> {
        val acc = LinkedHashMap<String, LongArray>()

        fun flush(name: String?, dirty: Long, rss: Long, pss: Long) {
            if (name == null) return
            val slot = acc.getOrPut(classify(name)) { LongArray(3) }
            slot[0] += dirty
            slot[1] += rss
            slot[2] += pss
        }

        var name: String? = null
        var dirty = 0L
        var rss = 0L
        var pss = 0L

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            val headerName = headerMappingName(line)
            if (headerName != null) {
                flush(name, dirty, rss, pss)
                name = headerName
                dirty = 0L
                rss = 0L
                pss = 0L
                continue
            }
            when {
                line.startsWith("Rss:") -> rss = kbValue(line)
                line.startsWith("Pss:") -> pss = kbValue(line)
                line.startsWith("Private_Dirty:") -> dirty = kbValue(line)
            }
        }
        flush(name, dirty, rss, pss)

        return acc.map { (label, v) -> MemBucket(label, v[0], v[1], v[2]) }
            .sortedByDescending { it.privateDirtyKb }
    }

    /**
     * 映射名 → 归类标签。
     *
     * Android 8 起位图像素分配在 native（`calloc`），因此落在 `libc malloc`；
     * 图形缓冲通常在 `/dev/kgsl-*`（高通）或 `/dev/mali*`（ARM）/`gralloc` 具名映射。
     */
    internal fun classify(mapping: String): String {
        val name = mapping.trim()
        if (name.isEmpty()) return "匿名（无名字）"
        if (name.startsWith("[anon:libc_malloc]")) return "libc malloc（位图/解码缓冲）"
        if (name.startsWith("[anon:")) {
            val tag = name.removePrefix("[anon:").removeSuffix("]")
            return "匿名：" + tag.ifBlank { "?" }
        }
        if (name.startsWith("[stack")) return "线程栈（[stack]）"
        if (name.startsWith("/dev/kgsl")) return "GPU 驱动（kgsl）"
        if (name.startsWith("/dev/mali")) return "GPU 驱动（mali）"
        if (name.startsWith("/dev/")) return "设备/共享映射（/dev）"
        if (name.startsWith("/memfd")) return "匿名 memfd"
        val lower = name.lowercase()
        if (lower.endsWith(".so") || lower.contains(".so (deleted)")) return "原生库（.so）"
        if (lower.endsWith(".art") || lower.endsWith(".oat") ||
            lower.endsWith(".vdex") || lower.endsWith(".odex") || lower.endsWith(".dex")
        ) {
            return "ART 代码/数据"
        }
        if (lower.endsWith(".ttf") || lower.endsWith(".ttc") || lower.endsWith(".otf")) {
            return "字体"
        }
        if (name.startsWith("/")) return "文件映射（资源/媒体）"
        return "其它"
    }

    // -------------------------------------------------------------- 内部

    /** 头部行判定：首字段是 `起始-结束` 的十六进制地址，第二字段是 `rw-p` 形态的权限 */
    private fun headerMappingName(line: String): String? {
        val first = line.substringBefore(' ')
        if (!first.contains('-')) return null
        val dash = first.indexOf('-')
        if (dash <= 0) return null
        if (!first.substring(0, dash).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        if (!first.substring(dash + 1).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null

        val parts = line.split(WHITESPACE, limit = 6)
        if (parts.size < 5) return null
        // 权限字段形如 rw-p / r--s
        val perm = parts[1]
        if (perm.length != 4) return null
        return if (parts.size >= 6) parts[5] else ""
    }

    /** `VmRSS:      123456 kB` → 123456 */
    private fun numberAfterColon(line: String): Long =
        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L

    /** `Private_Dirty:      4096 kB` → 4096 */
    private fun kbValue(line: String): Long = numberAfterColon(line)

    private fun readText(path: String): String? = runCatching {
        val file = File(path)
        if (!file.canRead()) return null
        file.readText()
    }.getOrNull()

    /** 每秒时钟滴答数；Android 上恒为 100，取不到就按 100 兜底 */
    private fun clkTck(): Double = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK).toDouble()
    }.getOrDefault(100.0).takeIf { it > 0.0 } ?: 100.0

    /** 线程榜只报前几个：再长就没人看了，也失去「一眼看出谁在转」的意义 */
    private const val TOP_THREADS = 5

    private val WHITESPACE = Regex("\\s+")
}
