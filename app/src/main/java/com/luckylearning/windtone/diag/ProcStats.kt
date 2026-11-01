package com.luckylearning.windtone.diag

import android.os.SystemClock
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

    /**
     * 进程规模。
     *
     * - [fds]：`/proc/self/fd` 打开数（读不到为 -1）
     * - [fdLimit]：`/proc/self/limits` 的 Max open files **软**上限（读不到为 -1）
     * - [fdSize]：`status` 的 FDSize（内核 FD **表容量**，可大于打开数；勿与 [fds] 混淆）
     */
    data class Proc(
        val threads: Int,
        val fds: Int,
        val vmRssKb: Long,
        val vmHwmKb: Long,
        val fdLimit: Int = -1,
        val fdSize: Int = -1,
    )

    /** 单线程 CPU 占比（相对「进程启动至今」这段墙钟时间） */
    data class ThreadCpu(val name: String, val percent: Double)

    /** 进程级 CPU：平均占比 + 前几个热点线程 */
    data class Cpu(
        val processPercent: Double,
        val top: List<ThreadCpu>,
        /** 分母依据（正常是 /proc/uptime；兜底时写明会低估，见 [readCpu]） */
        val basis: String = "/proc/uptime",
        /** 进程已存活秒数。分母就是它 —— 报告里写出来，「跑多久之后涨成这样」才说得清 */
        val uptimeSec: Double = 0.0,
    )

    /** 一类内存映射的占用。三项都给：Private_Dirty 看「自己吃掉的」，Pss 看「公平分摊」 */
    data class MemBucket(
        val label: String,
        val privateDirtyKb: Long,
        val rssKb: Long,
        val pssKb: Long,
    )

    /**
     * 一条内存映射（段）的占用。
     *
     * 四值一起给，才能分清「真占用」与「只是保留了地址空间」：
     *  - `Size` 是这段映射的**虚拟**大小，申请了就占，未必落物理页；
     *  - `Rss` 是**驻留**页，已经映射到物理内存的部分；
     *  - `Private_Dirty` 是其中**自己独占且写过**的部分 —— 泄漏/常驻看它；
     *  - `Pss` 是按共享比例分摊后的口径，看「公平占了多少」。
     *
     * 分配器（jemalloc / Scudo）在 free 之后常常只 `madvise` 不 `munmap`：
     * 这时 `Size` 仍然很大，但 `Rss` / `Private_Dirty` 会明显小于它。
     * 只看 Private_Dirty 的旧版埋点分不出这两者，也就会把「已释放但未归还」
     * 误判成「还活着的对象」。
     */
    data class Segment(
        val name: String,
        val dirtyKb: Long,
        val sizeKb: Long = 0L,
        val rssKb: Long = 0L,
        val pssKb: Long = 0L,
    )

    data class Smaps(
        val buckets: List<MemBucket>,
        /** 逐段明细，按 Private_Dirty 降序。段数可能上百，只在导出时算一次 */
        val segments: List<Segment> = emptyList(),
    ) {
        val privateDirtyKb: Long get() = buckets.sumOf { it.privateDirtyKb }
        val rssKb: Long get() = buckets.sumOf { it.rssKb }
        val pssKb: Long get() = buckets.sumOf { it.pssKb }

        /** 某归类的合计（前缀匹配，容忍标签里的括号说明） */
        fun totalOf(labelPrefix: String): Long =
            buckets.filter { it.label.startsWith(labelPrefix) }.sumOf { it.privateDirtyKb }
    }

    /**
     * CPU 采样结果。
     *
     * 能算出来就给 [cpu]，算不出来给 [failReason] —— **不再静默返回 null**：
     * v1.2.40 的诊断包只写「读取失败，平均占用不可得」，等于把排查线索一起丢了
     * （到底是 stat 不可读、解析不过、还是 uptime 被 SELinux 拦下，无从判断）。
     */
    data class CpuReading(val cpu: Cpu?, val failReason: String? = null)

    /** `/proc/self/stat` 里我们关心的三个字段（单位：时钟滴答） */
    data class StatTicks(val utime: Long, val stime: Long, val starttime: Long) {
        val total: Long get() = utime + stime
    }

    // ------------------------------------------------------------------ 读

    fun read(): Proc? = runCatching {
        val status = readText("/proc/self/status") ?: return null
        // list() 只在导出/崩溃快照时跑一次；含打开本目录瞬间多出的 1 个 fd，对照时按「约数」看即可
        val fds = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)
        val fdLimit = readText("/proc/self/limits")?.let { parseFdSoftLimit(it) } ?: -1
        parseStatus(status, fds = fds, fdLimit = fdLimit)
    }.getOrNull()

    /**
     * 进程与线程的 CPU 占比。
     *
     * 分母用「当前时刻 − 进程启动时刻」而不是「采样窗口」，所以得到的是
     * **进程启动至今的平均 CPU%**：播放中稳定在 1%~3% 属正常，若到 8% 以上
     * 说明有线程在空转。线程榜用来指出是哪个。
     *
     * ## 分母为什么要兜底（2026-10-31 修）
     *
     * 原先分母只有 `/proc/uptime` 一条路，读不到就整个 CPU 段作废。BYD DiLink5
     * （Android 12）实测就落在这个坑里：诊断包里只留一句「读取失败」，看不出
     * 是哪一环。现在：
     *
     *  1. 优先 `/proc/uptime` —— 与 `starttime` 同源，休眠时段不推进，口径最准；
     *  2. 读不到就用 `SystemClock.elapsedRealtime()` —— 同样以开机为原点，
     *     但**含深睡**，分母偏大 → CPU 百分比会被低估（报告里会写明）；
     *  3. 仍算不出就把失败环节带回去，不再返回一个没有信息量的 null。
     *
     * @param includeTopThreads false 时跳过扫 `/proc/self/task/` 下各 tid 的 `stat`
     *   （崩溃快照冷路径用，少一次 N 文件读）
     */
    fun readCpu(includeTopThreads: Boolean = true): CpuReading = runCatching {
        val selfText = readText("/proc/self/stat")
            ?: return@runCatching CpuReading(null, "/proc/self/stat 不可读（被 SELinux 或厂商策略拦下）")
        val self = parseStat(selfText)
            ?: return@runCatching CpuReading(null, "/proc/self/stat 解析失败（字段不足 20 个，内核格式差异）")
        val clk = clkTck()

        val uptime = readText("/proc/uptime")?.let { parseUptimeSec(it) }
        val elapsedSec: Double
        val basis: String
        if (uptime != null) {
            elapsedSec = uptime - self.starttime / clk
            basis = "/proc/uptime"
        } else {
            elapsedSec = SystemClock.elapsedRealtime() / 1000.0 - self.starttime / clk
            basis = "elapsedRealtime（/proc/uptime 不可读，含深睡，百分比偏低）"
        }
        if (elapsedSec <= 0.5) {
            return@runCatching CpuReading(null, "进程存活不足 1 秒，取不到平均值（刚启动就想看平均占用）")
        }

        val procPercent = self.total / clk / elapsedSec * 100.0

        val top = if (!includeTopThreads) {
            emptyList()
        } else {
            runCatching {
                File("/proc/self/task").list()?.mapNotNull { tid ->
                    val text = readText("/proc/self/task/$tid/stat") ?: return@mapNotNull null
                    val ticks = parseStat(text) ?: return@mapNotNull null
                    ThreadCpu(threadName(text), ticks.total / clk / elapsedSec * 100.0)
                }?.sortedByDescending { it.percent }?.take(TOP_THREADS)
            }.getOrNull().orEmpty()
        }

        CpuReading(Cpu(procPercent, top, basis, elapsedSec))
    }.getOrElse { e ->
        // 真异常（OOM / 权限）也带类型回去，不要只留一句「失败」
        CpuReading(null, "/proc 读取异常：${e.javaClass.simpleName}${e.message?.let { "（$it）" } ?: ""}")
    }

    /** 读 `/proc/self/smaps` 并归类；本机限制读取时返回 null（不影响诊断包其它段） */
    fun readSmaps(): Smaps? = runCatching {
        val file = File("/proc/self/smaps")
        if (!file.canRead()) return null
        val (buckets, segments) = file.useLines { parseSmapsDetailed(it) }
        if (buckets.isEmpty()) return null
        Smaps(buckets, segments)
    }.getOrNull()

    // ------------------------------------------------------------ 纯解析

    /**
     * `/proc/self/status` 解析。
     *
     * 只挑四行，不整体切分 —— 这个文件有 50 多行，逐行 `startsWith` 比建 map 便宜。
     */
    internal fun parseStatus(
        text: String,
        fds: Int = -1,
        fdLimit: Int = -1,
    ): Proc {
        var threads = -1
        var rss = 0L
        var hwm = 0L
        var fdSize = -1
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Threads:") -> threads = numberAfterColon(line).toInt()
                line.startsWith("VmRSS:") -> rss = numberAfterColon(line)
                line.startsWith("VmHWM:") -> hwm = numberAfterColon(line)
                line.startsWith("FDSize:") -> fdSize = numberAfterColon(line).toInt()
            }
        }
        return Proc(
            threads = threads.coerceAtLeast(-1),
            fds = fds,
            vmRssKb = rss,
            vmHwmKb = hwm,
            fdLimit = fdLimit,
            fdSize = fdSize,
        )
    }

    /**
     * `/proc/self/limits` → Max open files 软上限。
     *
     * 行形如：`Max open files            1024                 4096                 files`
     * 硬编码 1024 做告警会在厂商改软限时误报；对照长稳包必须带真实软限。
     */
    internal fun parseFdSoftLimit(text: String): Int {
        for (line in text.lineSequence()) {
            if (!line.startsWith("Max open files")) continue
            val parts = WHITESPACE.split(line.trim())
            if (parts.size < 4) return -1
            val soft = parts[3]
            if (soft.equals("unlimited", ignoreCase = true)) return Int.MAX_VALUE
            return soft.toIntOrNull() ?: -1
        }
        return -1
    }

    /** fd 是否逼近软上限（≥ 90%）；无软限时不告警 */
    fun fdNearLimit(proc: Proc): Boolean {
        if (proc.fds < 0 || proc.fdLimit <= 0) return false
        if (proc.fdLimit == Int.MAX_VALUE) return false
        return proc.fds * 10L >= proc.fdLimit * 9L
    }

    /** 线程数是否异常偏高（与崩溃快照同一口径，便于诊断包对照） */
    fun threadsElevated(proc: Proc): Boolean = proc.threads > THREAD_WARN

    /** 稳定键行：跨语言/版本 grep 对照用，字段名勿改 */
    fun stableKeyLine(proc: Proc): String =
        "proc_key	threads=${proc.threads}	fds=${proc.fds}	fd_limit=${proc.fdLimit}	" +
            "fd_size=${proc.fdSize}	vm_rss_kb=${proc.vmRssKb}	vm_hwm_kb=${proc.vmHwmKb}"

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
    internal fun parseSmaps(lines: Sequence<String>): List<MemBucket> = parseSmapsDetailed(lines).first

    /**
     * 同 [parseSmaps]，但同时保留**逐段**明细。
     *
     * 为什么需要逐段：`libc malloc` 合计 246MB 有两种完全不同的成因，处置方式也相反 ——
     *
     *  - **少数几个巨大段**（几 MB~几十 MB 一个）→ 大对象，如位图、解码器输出缓冲；
     *  - **海量小段**（几十~几百 KB 一个，成百上千条）→ malloc 碎片 / 池化不归还。
     *
     * 只看合计永远分不出来，看段数、最大段、>1MB 段占比就能一眼判断。
     */
    internal fun parseSmapsDetailed(lines: Sequence<String>): Pair<List<MemBucket>, List<Segment>> {
        val acc = LinkedHashMap<String, LongArray>()
        val segments = ArrayList<Segment>()

        fun flush(name: String?, size: Long, dirty: Long, rss: Long, pss: Long) {
            if (name == null) return
            val slot = acc.getOrPut(classify(name)) { LongArray(3) }
            slot[0] += dirty
            slot[1] += rss
            slot[2] += pss
            // 只留「写脏」的段：只读的文件映射（.so / apk）不参与「谁吃了我」的判断，
            // 全量记会把几百条零占用的条目也塞进列表
            if (dirty > 0) segments += Segment(name, dirty, size, rss, pss)
        }

        var name: String? = null
        var size = 0L
        var dirty = 0L
        var rss = 0L
        var pss = 0L

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            val headerName = headerMappingName(line)
            if (headerName != null) {
                flush(name, size, dirty, rss, pss)
                name = headerName
                size = 0L
                dirty = 0L
                rss = 0L
                pss = 0L
                continue
            }
            when {
                line.startsWith("Size:") -> size = kbValue(line)
                line.startsWith("Rss:") -> rss = kbValue(line)
                line.startsWith("Pss:") -> pss = kbValue(line)
                line.startsWith("Private_Dirty:") -> dirty = kbValue(line)
            }
        }
        flush(name, size, dirty, rss, pss)

        val buckets = acc.map { (label, v) -> MemBucket(label, v[0], v[1], v[2]) }
            .sortedByDescending { it.privateDirtyKb }
        return buckets to segments.sortedByDescending { it.dirtyKb }
    }

    /**
     * 映射名 → 归类标签。
     *
     * Android 8 起位图像素分配在 native（`calloc`），因此落在 `libc malloc`；
     * 图形缓冲通常在 `/dev/kgsl-*`（高通）或 `/dev/mali*`（ARM）/`gralloc` 具名映射。
     *
     * ⚠️ malloc 的映射名**随分配器实现而变**，必须都认：
     *  - Android ≤10（jemalloc / 旧 Scudo）：`[anon:libc_malloc]`
     *  - Android 11+（Scudo 默认）：`[anon:scudo:primary]` / `[anon:scudo:secondary]`
     *
     * 只认第一种的话，Android 11+ 机器上这几十上百 MB 会被折成「匿名：scudo:primary」
     * 散落在「其它 N 类」里，等于把最大的嫌疑对象藏起来。
     */
    internal fun classify(mapping: String): String {
        val name = mapping.trim()
        if (name.isEmpty()) return "匿名（无名字）"
        if (name.startsWith("[anon:libc_malloc]")) return MALLOC_LABEL
        if (name.startsWith("[anon:scudo:")) return MALLOC_LABEL
        if (name.startsWith("[anon:jemalloc]")) return MALLOC_LABEL
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

    /** 与 CrashLog 资源快照同一阈值：超过即提示怀疑线程泄漏 */
    const val THREAD_WARN = 200

    /** malloc 归类的统一标签（MemoryProbe 按它取合计，别在两处各写一遍字符串） */
    const val MALLOC_LABEL = "libc malloc（位图/解码缓冲）"

    private val WHITESPACE = Regex("\\s+")
}
