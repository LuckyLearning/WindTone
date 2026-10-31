package com.luckylearning.windtone.diag

import android.graphics.Bitmap
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 位图存活计量（2026-10-31 新增）。
 *
 * ## 为什么需要它
 *
 * 车机诊断包里 `libc malloc` 占了 246MB（Private_Dirty 的 61%），而封面位图缓存
 * 只有 0.3MB、本进程累计解码产出 0.3MB —— 数据互相矛盾。矛盾只有两种解释：
 *
 *  1. 有一批位图**活在 LruCache 之外**（被某个 Compose state / View tag / 静态引用
 *     持有，或压根没走缓存），即泄漏；
 *  2. 那 246MB **根本不是位图**（解码器缓冲、Skia 缓存、分配器碎片）。
 *
 * 光看 `libc malloc` 合计永远分不出这两者，所以直接给位图计数：
 * **存活字节与 malloc 合计一比，答案就出来了**。
 *
 * ## 怎么算「存活」
 *
 * 位图的像素在 native（Android 8+），但 `Bitmap` 对象本身是 Java 对象。因此用
 * 弱引用跟踪：对象被回收 ⇒ 像素已经没人引用 ⇒ 从存活里减掉。这样不需要在每个
 * 释放点手工记账（那必然漏），也不会因为 LruCache 淘汰而漏减。
 *
 * 代价：每创建一张位图多一个 `WeakReference` 与一次队列清理。位图创建频率是
 * 「每次换歌 / 每次开页面」量级，不在绘制热路径上。
 *
 * ## 口径提醒
 *
 * [Stats.liveBytes] 是**估算**：GC 未跑时已死位图仍会计入（偏大）。它是用来判
 * 「量级对不对」的，不是精确账。峰值 [Stats.peakLiveBytes] 才是泄漏判据。
 */
object BitmapMeter {

    /** 纯计数逻辑，抽出来便于 JVM 单测（`Bitmap` 在单测里是 stub，拿不到字节数） */
    internal class ByteCounter {
        var created: Long = 0L; private set
        var createdBytes: Long = 0L; private set
        var liveBytes: Long = 0L; private set
        var peakLiveBytes: Long = 0L; private set

        fun onCreate(bytes: Long) {
            created++
            createdBytes += bytes
            liveBytes += bytes
            if (liveBytes > peakLiveBytes) peakLiveBytes = liveBytes
        }

        fun onRelease(bytes: Long) {
            liveBytes -= bytes
            if (liveBytes < 0L) liveBytes = 0L // 重复回收也不要把账做成负数
        }

        fun reset() {
            created = 0L
            createdBytes = 0L
            liveBytes = 0L
            peakLiveBytes = 0L
        }
    }

    data class Stats(
        val created: Long,
        val createdBytes: Long,
        val liveBytes: Long,
        val peakLiveBytes: Long,
    )

    private val counter = ByteCounter()
    private val queue = ReferenceQueue<Bitmap>()
    /** 必须强引用住弱引用对象本身，否则它们先被回收，队列永远收不到通知 */
    private val pending = ConcurrentLinkedQueue<Reaped>()

    private class Reaped(bmp: Bitmap, q: ReferenceQueue<Bitmap>, val bytes: Long) :
        WeakReference<Bitmap>(bmp, q)

    /**
     * 登记一张刚创建的位图。**返回值就是入参**，方便原地改写：
     * `val bmp = BitmapMeter.track(BitmapFactory.decodeFile(...))`
     */
    fun track(bmp: Bitmap?): Bitmap? {
        if (bmp == null || bmp.isRecycled) return bmp
        val bytes = runCatching { bmp.allocationByteCount.toLong() }.getOrDefault(0L)
        if (bytes <= 0L) return bmp
        drain()
        counter.onCreate(bytes)
        pending += Reaped(bmp, queue, bytes)
        return bmp
    }

    fun stats(): Stats {
        drain()
        return Stats(counter.created, counter.createdBytes, counter.liveBytes, counter.peakLiveBytes)
    }

    /** 清空统计（换语言/换主题这类整屏重建之后调用，避免历史峰值干扰判断） */
    fun reset() {
        drain()
        counter.reset()
        pending.clear()
    }

    private fun drain() {
        while (true) {
            val r = queue.poll() as? Reaped ?: break
            counter.onRelease(r.bytes)
            pending.remove(r)
        }
    }
}
