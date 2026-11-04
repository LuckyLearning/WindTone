package com.luckylearning.windtone.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封面解码尺寸与缓存预算（v1.2.12）。
 *
 * 这一组测的是**纯函数**：解码倍率、采样倍数、精确缩放目标。它们决定了封面占多少内存，
 * 而「占多少内存」在真机上只能靠现象反推 —— 所以这里把口径钉死，改动前先看断言。
 *
 * 背景：旧实现是 `roundToPx() * 2`，等于按 4 倍面积解码；配合「只用 inSampleSize」
 * 的降采样，目标值一旦大于原图就完全不降，3000px 内嵌封面会按全尺寸解出来（18MB）。
 */
class CoverCacheTest {

    // ------------------------------------------------------------ 解码倍率

    @Test
    fun `列表缩略图按 1 比 1 解码`() {
        // 52dp 在 xxhdpi 上约 143px：显示多少解多少，不留余量
        assertEquals(143, CoverRepository.targetPxFor(143))
    }

    @Test
    fun `大图保留 25 倍余量`() {
        // 播放页封面 292dp ≈ 803px → 1004px（面积 1.56 倍），足够覆盖悬浮窗放大
        assertEquals(1004, CoverRepository.targetPxFor(803))
        assertEquals(640, CoverRepository.targetPxFor(512))
    }

    @Test
    fun `非法显示尺寸返回 0`() {
        assertEquals(0, CoverRepository.targetPxFor(0))
        assertEquals(0, CoverRepository.targetPxFor(-10))
    }

    // ------------------------------------------------------------ 缓存边长分桶

    @Test
    fun `缩略图相邻像素收成同一桶`() {
        // 列表缩略图密度/字体漂移：142/143/144 应收成 160，避免磁盘近重复 JPEG
        assertEquals(160, CoverRepository.cachePxFor(142))
        assertEquals(160, CoverRepository.cachePxFor(143))
        assertEquals(160, CoverRepository.cachePxFor(144))
        assertEquals(160, CoverRepository.cachePxFor(160))
        assertEquals(192, CoverRepository.cachePxFor(161))
    }

    @Test
    fun `大图用 64 步长分桶`() {
        // < LARGE_COVER_PX(512) 仍用 32 步：450 → 480
        assertEquals(480, CoverRepository.cachePxFor(450))
        // ≥ 512 用 64 步
        assertEquals(512, CoverRepository.cachePxFor(512))
        assertEquals(576, CoverRepository.cachePxFor(513))
        assertEquals(832, CoverRepository.cachePxFor(803)) // 播放页常见物理边长
    }

    @Test
    fun `非法目标像素返回 0`() {
        assertEquals(0, CoverRepository.cachePxFor(0))
        assertEquals(0, CoverRepository.cachePxFor(-8))
    }

    // ------------------------------------------------------------ 采样倍数

    @Test
    fun `采样后长边落在目标与两倍目标之间`() {
        // 3000px + 目标 1004：降两倍到 1500，再精确缩到 1004
        assertEquals(2, CoverRepository.sampleSizeFor(3000, 3000, 1004))
        // 6000px + 目标 1004：6000/4=1500 仍 ≥ 1004，再降一档到 750 以下
        assertEquals(4, CoverRepository.sampleSizeFor(6000, 4000, 1004))
        // 原图本来就比目标小：不降采样
        assertEquals(1, CoverRepository.sampleSizeFor(600, 600, 1004))
    }

    @Test
    fun `按长边判定 极端比例不会被跳过降采样`() {
        // 10000x200 的长条图：旧实现要求宽和高都 ≥ 2×目标才降档，
        // 200 永远不满足 → 一路不降 → 全尺寸解码。按长边判定后正常降档。
        assertEquals(8, CoverRepository.sampleSizeFor(10_000, 200, 1000))
    }

    @Test
    fun `旧倍率下的全尺寸解码路径已可复现`() {
        // 这条是「为什么必须改」的书面证据：
        // 同一个 3000px 原图，旧目标（803 × 2 = 1606）下 sample 仍是 1 → 解出 3000px ≈ 18MB；
        // 新目标（1004）下 sample 降到 2，再精确缩到 1004。
        assertEquals(1, CoverRepository.sampleSizeFor(3000, 3000, 1606))
        assertEquals(2, CoverRepository.sampleSizeFor(3000, 3000, 1004))
    }

    // ------------------------------------------------------------ 精确缩放

    @Test
    fun `精确缩放把长边对齐目标`() {
        assertEquals(1004 to 1004, CoverRepository.fitSize(1500, 1500, 1004))
        // 非正方形：短边等比，不能各自独立拉成正方形
        assertEquals(1000 to 500, CoverRepository.fitSize(2000, 1000, 1000))
    }

    @Test
    fun `小于目标的原图不放大`() {
        assertEquals(300 to 300, CoverRepository.fitSize(300, 300, 1004))
    }

    @Test
    fun `极端比例缩放后短边至少 1 像素`() {
        // 10000x10 → 缩到长边 1000 时短边会算出 1，不能是 0（Bitmap 不接受 0）
        assertEquals(1000 to 1, CoverRepository.fitSize(10_000, 10, 1000))
    }

    @Test
    fun `非法尺寸原样返回 不崩`() {
        assertEquals(0 to 0, CoverRepository.fitSize(0, 0, 100))
        assertEquals(100 to 100, CoverRepository.fitSize(100, 100, 0))
    }

    // ------------------------------------------------------------ 缓存预算

    @Test
    fun `缓存预算按堆比例 手机与车机都落在约定区间`() {
        val mb = 1024L * 1024L
        // 手机（256MB 堆）：位图 16MB、原始 2MB（2026-11-01：/16 夹 4~16；原始 /48 夹 1~2）
        assertEquals(16L, CoverRepository.bitmapCacheSizeFor(256 * mb) / mb)
        assertEquals(2L, CoverRepository.rawCacheSizeFor(256 * mb) / mb)
        // 车机（192MB 堆）：位图 24MB、原始 3MB。
        // 这一条是本次改造的回归点 —— 旧实现原始缓存是固定 16MB，占这台机器堆的 8%；
        // v1.2.41 又从 6MB 下调到 3MB（实测 68% 满、命中率仅 5%，占着堆却省不了解码）。
        assertEquals(12L, CoverRepository.bitmapCacheSizeFor(192 * mb) / mb)
        assertEquals(2L, CoverRepository.rawCacheSizeFor(192 * mb) / mb)
        // 低内存设备（64MB 堆）：位图 4MB（触底）、原始 1MB
        assertEquals(4L, CoverRepository.bitmapCacheSizeFor(64 * mb) / mb)
        assertEquals(1L, CoverRepository.rawCacheSizeFor(64 * mb) / mb)
    }

    @Test
    fun `原始缓存永远不超过位图缓存的一半`() {
        // 恒等关系：raw 是通知栏 / 取色的中转，不该和「真正要显示的东西」抢内存
        for (heapMb in longArrayOf(48, 64, 128, 192, 256, 512)) {
            val heap = heapMb * 1024 * 1024
            assertTrue(
                "heap=${heapMb}MB",
                CoverRepository.rawCacheSizeFor(heap) * 2 <= CoverRepository.bitmapCacheSizeFor(heap),
            )
        }
    }

    @Test
    fun `trim 两档都不抛异常`() {
        // 纯 JVM 下 android.util.LruCache 是 stub（unitTests.isReturnDefaultValues），
        // evictAll 的内部状态断言不出东西，所以这里只钉「调用不会炸」；
        // 真机上的实际占用由诊断包 [内存] 段的两行数字反映。
        CoverRepository.trim(CoverTrimLevel.UI_HIDDEN)
        CoverRepository.trim(CoverTrimLevel.RUNNING_LOW)
    }

    // -------------------------------------------- 通知栏封面（2026-10-31 新增）

    @Test
    fun `通知封面：空字节不出错`() {
        assertNull(CoverRepository.downscaleForNotification(ByteArray(0)))
    }

    @Test
    fun `通知封面：解不出尺寸且字节小则原样返回`() {
        // JVM stub / 非图：bounds 长边为 0；≤64KB 沿用原件，避免空转编解码
        val small = ByteArray(1000) { it.toByte() }
        assertSame(small, CoverRepository.downscaleForNotification(small))
    }

    @Test
    fun `通知封面：解不出尺寸且字节过大则丢弃`() {
        // 避免把不明大包塞进 MediaMetadata（旧逻辑会在 decodeScaled 失败后仍 return raw）
        val big = ByteArray(65 * 1024) { it.toByte() }
        assertNull(CoverRepository.downscaleForNotification(big))
    }

    @Test
    fun `小部件目标边长经 cachePxFor 仍为 128`() {
        assertEquals(128, CoverRepository.cachePxFor(CoverRepository.WIDGET_COVER_PX))
    }

    // ------------------------------ 快速下滑：等待者归零即取消（2026-11-04 新增）

    @Test
    fun `最后一个等待者离开即取消在途解码`() = runBlocking {
        val deferred = CompletableDeferred<Int>()
        val abandoned = AtomicInteger()
        val entry = CoverInFlight(deferred) { abandoned.incrementAndGet() }
        val waiter = launch { entry.awaitOrNull() }
        yield(); yield()
        waiter.cancelAndJoin()

        // 这就是「滚过去的行不再白解、白写盘」的书面证据
        assertTrue(deferred.isCancelled)
        assertEquals(1, abandoned.get())
    }

    @Test
    fun `还有别的等待者时 一个离开不拖死任务`() = runBlocking {
        val deferred = CompletableDeferred<Int>()
        val abandoned = AtomicInteger()
        val entry = CoverInFlight(deferred) { abandoned.incrementAndGet() }
        val row = launch { entry.awaitOrNull() }
        val miniPlayer = async { entry.awaitOrNull() }
        yield(); yield()
        row.cancelAndJoin()

        assertFalse(deferred.isCancelled)
        assertEquals(0, abandoned.get())
        deferred.complete(42)
        assertTrue(miniPlayer.await() == 42)
    }

    @Test
    fun `任务已完成时 等待者离开不再记放弃`() = runBlocking {
        val deferred = CompletableDeferred<Int>()
        val abandoned = AtomicInteger()
        val entry = CoverInFlight(deferred) { abandoned.incrementAndGet() }
        deferred.complete(7)

        assertTrue(entry.awaitOrNull() == 7)
        assertFalse(deferred.isCancelled)
        assertEquals(0, abandoned.get())
    }

    @Test
    fun `解码失败返回占位分支 且不误记放弃`() = runBlocking {
        val deferred = CompletableDeferred<Int>()
        val abandoned = AtomicInteger()
        val entry = CoverInFlight(deferred) { abandoned.incrementAndGet() }
        deferred.completeExceptionally(IllegalStateException("decode failed"))

        assertNull(entry.awaitOrNull())
        assertEquals(0, abandoned.get())
    }
}
