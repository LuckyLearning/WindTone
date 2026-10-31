package com.luckylearning.windtone.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.ui.AppStrings
import java.util.Locale

/**
 * 内存画像（诊断包 `[内存]` / `[Memory]` 段，v1.2.12；2026-10-31 扩写）。
 *
 * ## 为什么放进诊断包
 *
 * 车机上的内存问题只能靠用户回传诊断包来取数：这类设备不让 adb、也不方便录屏，
 * 而本段每一项都是「打开应用、点导出」就能拿到的，不需要任何额外操作。
 *
 * i18n 第 25 批：用户可见外壳进 `mp_*`。ProcStats 桶标签（如 `libc malloc…` /
 * `匿名：dalvik`）作机器可读归类键，本批不改。
 * **不改** SoftEq / 播放内核行为。
 */
object MemoryProbe {

    private const val MB = 1024L * 1024L

    /** 明细只报前几项：再长就没人看，也失去「一眼看出大头」的意义 */
    private const val TOP_BUCKETS = 6

    fun buildReport(context: Context, librarySongs: Int = 0): String = buildString {
        appendLine(AppStrings.s(R.string.mp_sec))

        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb = rt.maxMemory() / MB
        appendLine(
            AppStrings.s(R.string.mp_java_heap, usedMb, maxMb, percent(usedMb, maxMb)),
        )

        // getNativeHeapAllocatedSize 在 API 24 起可用（与本工程 minSdk 一致）
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / MB }.getOrDefault(-1L)
        val nativeText = if (nativeMb >= 0) {
            AppStrings.s(R.string.mp_mb_int, nativeMb)
        } else {
            AppStrings.s(R.string.mp_query_failed)
        }
        appendLine(AppStrings.s(R.string.mp_native_heap, nativeText))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        appendLine(
            AppStrings.s(
                R.string.mp_sys_avail,
                info.availMem / MB,
                yn(info.lowMemory),
            ),
        )

        appendProcess()
        appendNativeBuckets(nativeMb, usedMb)

        val s = CoverRepository.stats()
        appendLine(
            AppStrings.s(
                R.string.mp_cover_bitmap,
                mb(s.bitmapBytes),
                mb(s.bitmapMax.toLong()),
                s.bitmapHits,
                s.bitmapMisses,
                hitRate(s.bitmapHits, s.bitmapMisses),
            ),
        )
        appendLine(
            AppStrings.s(
                R.string.mp_cover_raw,
                mb(s.rawBytes),
                mb(s.rawMax.toLong()),
                s.rawHits,
                s.rawMisses,
                hitRate(s.rawHits, s.rawMisses),
            ),
        )
        appendLine(
            AppStrings.s(
                R.string.mp_cover_disk,
                mb(s.diskBytes),
                s.diskHits,
                s.diskMisses,
                hitRate(s.diskHits, s.diskMisses),
            ),
        )
        appendLine(
            AppStrings.s(
                R.string.mp_cover_decode,
                s.decodeFromDisk,
                s.decodeFromSource,
                mb(s.decodedBytes),
                mb(s.peakDecodedBytes.toLong()),
            ),
        )
        if (librarySongs > 0) appendLine(AppStrings.s(R.string.mp_library, librarySongs))

        // 位图账：这是判定「libc malloc 到底是不是位图」的唯一直接证据。
        // 弱引用跟踪，含「已 recycle 但还没被 GC」的位图，所以只会偏大不会漏报。
        val bm = BitmapMeter.stats()
        appendLine(
            AppStrings.s(
                R.string.mp_bitmap_live,
                mb(bm.liveBytes),
                mb(bm.peakLiveBytes),
                bm.created,
                mb(bm.createdBytes),
            ),
        )
        appendLine(AppStrings.s(R.string.mp_bitmap_read))
        appendLine(AppStrings.s(R.string.mp_budget))
    }

    /** 进程规模 + CPU 均值 + 线程热点 */
    private fun StringBuilder.appendProcess() {
        val proc = ProcStats.read()
        if (proc == null) {
            appendLine(AppStrings.s(R.string.mp_proc_fail))
        } else {
            appendLine(
                AppStrings.s(
                    R.string.mp_proc_line,
                    if (proc.threads >= 0) proc.threads.toString() else "?",
                    if (proc.fds >= 0) proc.fds.toString() else "?",
                    proc.vmRssKb / 1024,
                    proc.vmHwmKb / 1024,
                ),
            )
        }

        val reading = ProcStats.readCpu()
        val cpu = reading.cpu
        if (cpu == null) {
            // 2026-10-31：以前只写「读取失败」，等于把线索一起丢了。
            // 现在把具体环节带上，并明确说明这一段的缺失意味着什么。
            appendLine(
                AppStrings.s(
                    R.string.mp_cpu_fail,
                    reading.failReason ?: AppStrings.s(R.string.mp_reason_unknown),
                ),
            )
            appendLine(AppStrings.s(R.string.mp_cpu_fail_read))
            return
        }
        appendLine(
            AppStrings.s(
                R.string.mp_cpu_ok,
                oneDecimal(cpu.processPercent),
                duration(cpu.uptimeSec),
                cpu.basis,
            ),
        )
        cpu.top.forEach {
            appendLine(AppStrings.s(R.string.mp_cpu_top, oneDecimal(it.percent), it.name))
        }
    }

    /** native 明细：按映射归类，让「232MB 是谁」有答案 */
    private fun StringBuilder.appendNativeBuckets(nativeMb: Long, javaUsedMb: Long) {
        val smaps = ProcStats.readSmaps()
        if (smaps == null || smaps.buckets.isEmpty()) {
            appendLine(AppStrings.s(R.string.mp_native_detail_fail))
            return
        }
        appendLine(AppStrings.s(R.string.mp_native_detail_hdr))
        smaps.buckets.take(TOP_BUCKETS).forEach {
            appendLine(AppStrings.s(R.string.mp_bucket_line, it.label, mbFromKb(it.privateDirtyKb)))
        }
        val rest = smaps.buckets.drop(TOP_BUCKETS)
        if (rest.isNotEmpty()) {
            val restKb = rest.sumOf { it.privateDirtyKb }
            appendLine(AppStrings.s(R.string.mp_other_buckets, rest.size, mbFromKb(restKb)))
        }
        appendLine(
            AppStrings.s(
                R.string.mp_totals,
                mbFromKb(smaps.privateDirtyKb),
                mbFromKb(smaps.pssKb),
                mbFromKb(smaps.rssKb),
            ),
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
            AppStrings.s(
                R.string.mp_malloc_dist,
                malloc.size,
                mbFromKb(totalKb),
                mbFromKb(maxKb),
                over1Mb.size,
                share(over1Mb.sumOf { it.dirtyKb }, totalKb),
                over4Mb.size,
            ),
        )
        // 只列前 3 段：再多就是重复信息，报告要看的是「量级对不对」
        malloc.take(3).forEach {
            appendLine(AppStrings.s(R.string.mp_malloc_seg, mbFromKb(it.dirtyKb), it.name.take(48)))
        }
        appendLine(AppStrings.s(R.string.mp_malloc_read))
    }

    /**
     * 三个口径分开对账。
     *
     * v1.2.40 的判读写的是「明细 405MB 与 native 255MB 差太多…」—— **这是错的**：
     * 255MB 恰好等于 libc malloc；405MB 是全进程 Private_Dirty。
     * 现在的写法把三组关系各自说清。
     *
     * 桶标签匹配仍用 ProcStats 中文归类键（机器可读，本批不改）。
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

        appendLine(AppStrings.s(R.string.mp_reconcile_hdr))
        val nativePart = if (nativeMb >= 0) {
            "${nativeMb}MB ${agree(mallocMb, nativeMb)}"
        } else {
            AppStrings.s(R.string.mp_query_failed)
        }
        appendLine(AppStrings.s(R.string.mp_reconcile_native, mallocMb, nativePart))
        appendLine(
            AppStrings.s(
                R.string.mp_reconcile_art,
                artMb,
                javaUsedMb,
                agree(artMb, javaUsedMb),
            ),
        )
        appendLine(AppStrings.s(R.string.mp_reconcile_rest, restMb))
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
        return if (diff <= tolerant) {
            AppStrings.s(R.string.mp_agree_ok, diff)
        } else {
            AppStrings.s(R.string.mp_agree_bad, diff)
        }
    }

    private fun mb(bytes: Int): String = mb(bytes.toLong())

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    private fun mbFromKb(kb: Long): String = String.format(Locale.US, "%.1f MB", kb / 1024.0)

    private fun percent(used: Long, max: Long): String =
        if (max <= 0L) "—" else String.format(Locale.US, "%.0f%%", used * 100.0 / max)

    /** 命中率；总次数为 0 时给「—」而不是 0%，避免误读成「全都不命中」 */
    private fun hitRate(hits: Int, misses: Int): String = hitRate(hits.toLong(), misses.toLong())

    private fun hitRate(hits: Long, misses: Long): String {
        val total = hits + misses
        if (total <= 0L) return "—"
        return String.format(Locale.US, "%.0f%%", hits * 100.0 / total)
    }

    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun yn(v: Boolean) =
        AppStrings.s(if (v) R.string.dr_yes else R.string.dr_no)
}
