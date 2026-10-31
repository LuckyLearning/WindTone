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
 *  - 明细里 `libc malloc` 远大于「封面位图缓存」→ 有位图没进缓存（在用 / 泄漏）。
 *  - 明细合计 ≈ `native 堆` → 该数字可信，按明细去查。
 *  - 明细合计 ≪ `native 堆` → 本机 `getNativeHeapAllocatedSize` 不可信，以明细为准。
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
        appendNativeBuckets(nativeMb)

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
        appendLine("预算依据：位图 maxMemory/8（夹 8~32MB）· 原始 maxMemory/16（夹 2~6MB）")
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

        val cpu = ProcStats.readCpu()
        if (cpu == null) {
            appendLine("CPU：/proc/self/stat 读取失败，平均占用不可得")
            return
        }
        appendLine(
            "CPU：平均 ${oneDecimal(cpu.processPercent)}%（进程启动至今）" +
                "   判读：播放中 1~3% 属正常，≥8% 说明有线程空转",
        )
        cpu.top.forEach {
            appendLine("  · ${oneDecimal(it.percent)}%  ${it.name}")
        }
    }

    /** native 明细：按映射归类，让「232MB 是谁」有答案 */
    private fun StringBuilder.appendNativeBuckets(nativeMb: Long) {
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
        appendLine("  " + nativeVerdict(nativeMb, smaps))
    }

    /**
     * 把「native 堆」与明细合计对质，直接给出结论行。
     *
     * 这一行是给「下一次看诊断包的人」用的：省掉重新推一遍的时间。
     */
    private fun nativeVerdict(nativeMb: Long, smaps: ProcStats.Smaps): String {
        if (nativeMb < 0) return "判读：native 总数查询失败，以明细为准"
        val detailMb = smaps.privateDirtyKb / 1024
        // 允许 30% 或 16MB 的偏差：两个口径的统计范围本就不完全重合（ndk 分配、映射对齐）
        val tolerant = maxOf(16L, nativeMb / 3)
        return if (kotlin.math.abs(detailMb - nativeMb) <= tolerant) {
            "判读：明细与 native 总数一致，按上面的分类去查（libc malloc = 位图/解码缓冲）"
        } else {
            "判读：明细（${detailMb}MB）与 native 总数（${nativeMb}MB）差异大，" +
                "本机 getNativeHeapAllocatedSize 不可信，以明细为准"
        }
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
