package com.luckylearning.windtone.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.luckylearning.windtone.data.CoverRepository
import java.util.Locale

/**
 * 内存画像（诊断包 `[内存]` 段，v1.2.12；2026-10-31 扩写）。
 *
 * ## 为什么放进诊断包
 *
 * 车机上的内存问题只能靠用户回传诊断包来取数：这类设备不让 adb、也不方便录屏，
 * 而本段每一项都是「打开应用、点导出」就能拿到的，不需要任何额外操作。
 *
 * ## 读什么、为什么
 *
 *  - **Java 堆 used/max**：`used` 含已分配未回收的对象，比「存活集」偏大 ——
 *    只能用来比两版之间的变化，**不能当真实占用读**。
 *  - **native 堆**：`Debug.getNativeHeapAllocatedSize()` 给的是**真实分配**（不是保留），
 *    但它只有一个总数，说不出「是谁」。所以下面必须配 [ProcStats] 的映射归类。
 *  - **进程规模（线程 / fd / VmRSS / VmHWM）**：线程与 fd 是两类最典型的「慢性泄漏」，
 *    数量只增不减就说明有东西没释放；VmHWM 是历史峰值，用来判断「现在这数是不是刚涨上来的」。
 *  - **CPU 均值 + 线程热点**：分母取「进程启动至今」，所以是平均占用。播放中
 *    1%~3% 正常，8% 以上就是有线程在空转，榜单直接指出是谁。
 *  - **native 明细**：按内存映射归类。位图、JPEG 解码缓冲、解码器输出都落在
 *    `libc malloc`；图形缓冲在 `/dev/kgsl*` 或具名 anon 映射；代码在 `.so` / ART。
 *  - **封面两级缓存与解码计量**：本工程唯一可以主动收缩的常驻内存；解码计量
 *    则回答「是不是一直在解」。
 *
 * ## 判读口径（写在报告里，免得下次又从头推）
 *
 * 这里有**三个不同的分母**，混用就会得出错误结论（v1.2.40 的诊断包就踩过）：
 *
 *  - `getNativeHeapAllocatedSize` ↔ 明细里的 `libc malloc`：**这两个可比**，
 *    都是 native 分配器口径；
 *  - `Java 堆 used` ↔ 明细里的 `dalvik-*` 与 ART 映射：**这两个可比**；
 *  - `Private_Dirty 合计` = 上面两者 + /dev 共享映射 + GPU 驱动 + .so/字体。
 *    它**不是** native 堆，拿它去比 `getNativeHeapAllocatedSize` 必然「差异大」。
 *
 * 另外 `libc malloc` 要再看**段级分布**：少数几个大段 = 大对象没释放，
 * 海量小段 = 分配器碎片，两者处置相反。
 */
object MemoryProbe {

    private const val MB = 1024L * 1024L

    /** 明细只报前几项：再长就没人看，也失去「一眼看出大头」的意义 */
    private const val TOP_BUCKETS = 6

    fun buildReport(context: Context, librarySongs: Int = 0): String = buildString {
        appendLine("[内存]")

        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb = rt.maxMemory() / MB
        appendLine("Java 堆：$usedMb / $maxMb MB（${percent(usedMb, maxMb)}，含未回收对象，不等于存活集）")

        // getNativeHeapAllocatedSize 在 API 24 起可用（与本工程 minSdk 一致）
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / MB }.getOrDefault(-1L)
        appendLine("native 堆：${if (nativeMb >= 0) "$nativeMb MB" else "查询失败"}")

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        appendLine(
            "系统可用：${info.availMem / MB} MB" +
                "   低内存标志：${if (info.lowMemory) "是" else "否"}"
        )

        appendProcess()
        appendNativeBuckets(nativeMb, usedMb)

        val s = CoverRepository.stats()
        appendLine(
            "封面位图缓存：${mb(s.bitmapBytes)} / ${mb(s.bitmapMax.toLong())}" +
                "   命中 ${s.bitmapHits} / 未命中 ${s.bitmapMisses}（${hitRate(s.bitmapHits, s.bitmapMisses)}）"
        )
        appendLine(
            "封面原始缓存：${mb(s.rawBytes)} / ${mb(s.rawMax.toLong())}" +
                "   命中 ${s.rawHits} / 未命中 ${s.rawMisses}（${hitRate(s.rawHits, s.rawMisses)}）"
        )
        appendLine("封面磁盘缓存：${mb(s.diskBytes)}")
        appendLine(
            "封面解码：磁盘 ${s.decodeFromDisk} 次 · 原始 ${s.decodeFromSource} 次" +
                "   累计产出 ${mb(s.decodedBytes)}（吞吐，非常驻）   峰值单张 ${mb(s.peakDecodedBytes.toLong())}"
        )
        if (librarySongs > 0) appendLine("曲库：$librarySongs 首")

        // 位图账：这是判定「libc malloc 到底是不是位图」的唯一直接证据。
        // 弱引用跟踪，含「已 recycle 但还没被 GC」的位图，所以只会偏大不会漏报。
        val bm = BitmapMeter.stats()
        appendLine(
            "位图存活（本进程，弱引用跟踪）：当前 ${mb(bm.liveBytes)}" +
                "   峰值 ${mb(bm.peakLiveBytes)}" +
                "   累计创建 ${bm.created} 张 / ${mb(bm.createdBytes)}（吞吐，非常驻）"
        )
        appendLine("  判读：当前存活若与上方 libc malloc 同量级 → 那几十上百 MB 就是位图；" +
            "若远小于它 → 占用在别处（解码器缓冲 / Skia / 分配器碎片）")
        appendLine("预算依据：位图 maxMemory/8（夹 8~32MB）· 原始 maxMemory/32（夹 1.5~3MB）")
    }

    /** 进程规模 + CPU 均值 + 线程热点 */
    private fun StringBuilder.appendProcess() {
        val proc = ProcStats.read()
        if (proc == null) {
            appendLine("进程：/proc/self 读取失败（本机限制），线程与 fd 数量不可得")
        } else {
            appendLine(
                "进程：线程 ${if (proc.threads >= 0) proc.threads else "?"}" +
                    "   文件描述符 ${if (proc.fds >= 0) proc.fds else "?"}" +
                    "   VmRSS ${proc.vmRssKb / 1024} MB   历史峰值 ${proc.vmHwmKb / 1024} MB"
            )
        }

        val reading = ProcStats.readCpu()
        val cpu = reading.cpu
        if (cpu == null) {
            // 2026-10-31：以前只写「读取失败」，等于把线索一起丢了。
            // 现在把具体环节带上，并明确说明这一段的缺失意味着什么。
            appendLine("CPU：不可得 —— ${reading.failReason ?: "原因未知"}")
            appendLine("  判读：CPU 段失效时，卡顿/空转问题无法从诊断包判断；请把这行原文回传")
            return
        }
        appendLine(
            "CPU：平均 ${oneDecimal(cpu.processPercent)}%（进程存活 ${duration(cpu.uptimeSec)}，" +
                "分母 ${cpu.basis}）" +
                "   判读：播放中 1~3% 属正常，≥8% 说明有线程空转",
        )
        cpu.top.forEach {
            appendLine("  · ${oneDecimal(it.percent)}%  ${it.name}")
        }
    }

    /** native 明细：按映射归类，让「232MB 是谁」有答案 */
    private fun StringBuilder.appendNativeBuckets(nativeMb: Long, javaUsedMb: Long) {
        val smaps = ProcStats.readSmaps()
        if (smaps == null || smaps.buckets.isEmpty()) {
            appendLine("native 明细：/proc/self/smaps 不可读（本机限制），只能看总数")
            return
        }
        appendLine("native 明细（/proc/self/smaps，按 Private_Dirty 降序）：")
        smaps.buckets.take(TOP_BUCKETS).forEach {
            appendLine("  ${it.label}  ${mbFromKb(it.privateDirtyKb)}")
        }
        val rest = smaps.buckets.drop(TOP_BUCKETS)
        if (rest.isNotEmpty()) {
            val restKb = rest.sumOf { it.privateDirtyKb }
            appendLine("  其它 ${rest.size} 类   ${mbFromKb(restKb)}")
        }
        appendLine(
            "  合计 Private_Dirty ${mbFromKb(smaps.privateDirtyKb)}" +
                " · Pss ${mbFromKb(smaps.pssKb)} · Rss ${mbFromKb(smaps.rssKb)}"
        )
        appendMallocSegments(smaps)
        appendReconciliation(nativeMb, javaUsedMb, smaps)
    }

    /**
     * malloc 的**段级**分布 —— 合计 246MB 到底是「几个大块」还是「一堆碎片」。
     *
     * 这两者的处置完全相反：大块是业务对象（位图 / 解码缓冲）没释放，
     * 碎片是分配器行为（池化不归还），改代码收效甚微。
     * 只看合计永远分不出来，所以必须把段数与单段大小报出来。
     */
    private fun StringBuilder.appendMallocSegments(smaps: ProcStats.Smaps) {
        val malloc = smaps.segments.filter { ProcStats.classify(it.name) == ProcStats.MALLOC_LABEL }
        if (malloc.isEmpty()) return
        val totalKb = malloc.sumOf { it.dirtyKb }
        if (totalKb <= 0L) return

        val over1Mb = malloc.filter { it.dirtyKb >= 1024 }
        val over4Mb = malloc.filter { it.dirtyKb >= 4096 }
        val maxKb = malloc.maxOf { it.dirtyKb }
        appendLine(
            "  malloc 段分布：共 ${malloc.size} 段 · 合计 ${mbFromKb(totalKb)}" +
                " · 最大单段 ${mbFromKb(maxKb)}" +
                " · ≥1MB ${over1Mb.size} 段（占 ${share(over1Mb.sumOf { it.dirtyKb }, totalKb)}）" +
                " · ≥4MB ${over4Mb.size} 段"
        )
        // 只列前 3 段：再多就是重复信息，报告要看的是「量级对不对」
        malloc.take(3).forEach {
            appendLine("    · ${mbFromKb(it.dirtyKb)}  ${it.name.take(48)}")
        }
        appendLine("  判读：≥1MB 段占比高 = 少数大对象没释放（位图/解码缓冲）；段多而单段小 = 分配器碎片")
    }

    /**
     * 三个口径分开对账。
     *
     * v1.2.40 的判读写的是「明细 405MB 与 native 255MB 差太多，本机
     * getNativeHeapAllocatedSize 不可信」—— **这是错的**：255MB 恰好等于
     * libc malloc 的 246MB，两者本来就吻合；405MB 是全进程 Private_Dirty，
     * 多出来的是 ART 堆（122MB）+ /dev + GPU。拿分子比分母，才会「差异大」。
     *
     * 现在的写法把三组关系各自说清：对得上就说对得上，对不上才说不可信。
     */
    private fun StringBuilder.appendReconciliation(
        nativeMb: Long,
        javaUsedMb: Long,
        smaps: ProcStats.Smaps,
    ) {
        val mallocMb = smaps.totalOf(ProcStats.MALLOC_LABEL) / 1024
        val artMb = smaps.buckets.filter {
            it.label.startsWith("匿名：dalvik") || it.label.startsWith("ART")
        }.sumOf { it.privateDirtyKb } / 1024
        val restMb = smaps.privateDirtyKb / 1024 - mallocMb - artMb

        appendLine("  对账（三组口径各算各的，不可互相替代）：")
        val nativePart = if (nativeMb >= 0) "${nativeMb}MB ${agree(mallocMb, nativeMb)}" else "查询失败"
        appendLine("    · native malloc ${mallocMb}MB ↔ getNativeHeapAllocatedSize $nativePart")
        appendLine("    · ART 堆映射 ${artMb}MB ↔ Java 堆 used ${javaUsedMb}MB ${agree(artMb, javaUsedMb)}")
        appendLine("    · 其余 ${restMb}MB = /dev 共享映射、GPU 驱动、.so 与字体（不归任何一方）")
    }

    /** 秒 → `1h02m` / `12m30s`。长跑诊断里「跑了多久才涨成这样」比精确到秒更重要 */
    private fun duration(sec: Double): String {
        val total = sec.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh%02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm%02ds", m, s)
            else -> "${s}s"
        }
    }

    /** 占比；分母为 0 时给「—」，不要输出 NaN */
    private fun share(part: Long, total: Long): String =
        if (total <= 0L) "—" else String.format(Locale.US, "%.0f%%", part * 100.0 / total)

    /** 两口径是否吻合：容差取「16MB 或大者的 1/4」，够宽以免误报，也够窄能抓到真异常 */
    private fun agree(a: Long, b: Long): String {
        val diff = kotlin.math.abs(a - b)
        val tolerant = maxOf(16L, maxOf(a, b) / 4)
        return if (diff <= tolerant) "（一致，差 ${diff}MB）" else "（差 ${diff}MB，异常）"
    }

    private fun mb(bytes: Int): String = mb(bytes.toLong())

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    private fun mbFromKb(kb: Long): String = String.format(Locale.US, "%.1f MB", kb / 1024.0)

    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun percent(used: Long, max: Long): String =
        if (max <= 0L) "—" else String.format(Locale.US, "%.0f%%", used * 100.0 / max)

    /** 命中率；总次数为 0 时给「—」而不是 0%，避免误读成「全都不命中」 */
    private fun hitRate(hits: Int, misses: Int): String {
        val total = hits + misses
        if (total <= 0) return "—"
        return String.format(Locale.US, "%.0f%%", hits * 100.0 / total)
    }
}
