package com.luckylearning.windtone.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.luckylearning.windtone.data.CoverRepository
import java.util.Locale

/**
 * 内存画像（诊断包 `[内存]` 段，v1.2.12）。
 *
 * ## 为什么放进诊断包
 *
 * 车机上的内存问题只能靠用户回传诊断包来取数：这类设备不让 adb、也不方便录屏，
 * 而本段每一项都是「打开应用、点导出」就能拿到的，不需要任何额外操作。
 *
 * ## 读什么、为什么
 *
 *  - **Java 堆 used/max**：应用能直接分配的上限。`used` 含已分配未回收的对象，
 *    所以它比「真实占用」偏大，但对比两版之间的变化是有效的。
 *  - **native 堆**：`MediaMetadataRetriever`、解码器、SimpleCache 索引都在这里，
 *    封面反复解码时常表现为 native 涨而 Java 堆不动 —— 只看 Java 堆会漏判。
 *  - **系统可用 / 低内存标志**：区分「应用自己占多了」与「整机都在吃紧」。
 *  - **封面两级缓存占用与命中率**：本工程唯一可以主动收缩的常驻内存（见
 *    `CoverRepository` 类文档「内存预算」）。命中率低说明预算给小了或键设计有问题。
 */
object MemoryProbe {

    private const val MB = 1024L * 1024L

    fun buildReport(context: Context, librarySongs: Int = 0): String = buildString {
        appendLine("[内存]")

        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb = rt.maxMemory() / MB
        appendLine("Java 堆：$usedMb / $maxMb MB（${percent(usedMb, maxMb)}）")

        // getNativeHeapAllocatedSize 在 API 24 起可用（与本工程 minSdk 一致）
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / MB }.getOrDefault(-1L)
        appendLine("native 堆：${if (nativeMb >= 0) "$nativeMb MB" else "查询失败"}")

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        appendLine(
            "系统可用：${info.availMem / MB} MB" +
                "   低内存标志：${if (info.lowMemory) "是" else "否"}"
        )

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
        if (librarySongs > 0) appendLine("曲库：$librarySongs 首")
        appendLine("预算依据：位图 maxMemory/8（夹 8~32MB）· 原始 maxMemory/16（夹 2~6MB）")
    }

    private fun mb(bytes: Int): String = mb(bytes.toLong())

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    private fun percent(used: Long, max: Long): String =
        if (max <= 0L) "—" else String.format(Locale.US, "%.0f%%", used * 100.0 / max)

    /** 命中率；总次数为 0 时给「—」而不是 0%，避免误读成「全都不命中」 */
    private fun hitRate(hits: Int, misses: Int): String {
        val total = hits + misses
        if (total <= 0) return "—"
        return String.format(Locale.US, "%.0f%%", hits * 100.0 / total)
    }
}
