package com.luckylearning.windtone.overlay

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ForegroundAppLog] 的**落盘**契约（2026-10-31 重写后补）。
 *
 * 与同名那份纯函数测试（`ForegroundAppStateTest` 里的 `ForegroundAppLogTest`）
 * 分工不同：那份钉「一条记录长什么样、什么时候该记、留多少条」，这份钉
 * 「真的写进文件后还能不能正确读回」。
 *
 * 旧实现每次切换都要「读两遍全文件 + 全量重写」，改成「进程内去重 + 追加一行、
 * 攒够 MAX_ENTRIES 才截断重写」之后，对外契约必须不变：去重仍在、条数仍有界、
 * 顺序仍是时间正序。
 *
 * 写入走 IO 协程域，所以断言前要等一小段 —— 这不是竞态测试，只是给异步落盘留时间。
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundAppLogFileTest {

    private val ctx: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ForegroundAppLog.clear(ctx)
        settle()
    }

    private fun settle(ms: Long = 250) = runBlocking { delay(ms) }

    @Test
    fun `连续同一个包名只记一条`() {
        repeat(5) { ForegroundAppLog.append(ctx, "com.byd.avc", null) }
        settle()
        val entries = ForegroundAppLog.read(ctx)
        assertEquals(1, entries.size)
        assertEquals("com.byd.avc", entries[0].pkg)
    }

    @Test
    fun `A 到 B 到 A 算两次切换，都记`() {
        ForegroundAppLog.append(ctx, "com.a", null)
        ForegroundAppLog.append(ctx, "com.b", null)
        ForegroundAppLog.append(ctx, "com.a", null)
        settle()
        val pkgs = ForegroundAppLog.read(ctx).map { it.pkg }
        assertEquals(listOf("com.a", "com.b", "com.a"), pkgs)
    }

    @Test
    fun `条数有界且保留最近的一批`() {
        // 略微超过 MAX：必定至少触发一次截断重写，最终只应留下最近的一批。
        // 规模刻意不取 2×MAX —— 那是上千次异步追加，等它落盘会让测试很慢。
        val total = ForegroundAppLog.MAX_ENTRIES + 20
        repeat(total) { i ->
            // 同一个包名会被进程内去重挡掉，所以每次要插一个「别的包」才能真正记下
            ForegroundAppLog.append(ctx, "com.q$i", null)
            ForegroundAppLog.append(ctx, "com.p$i", null)
        }
        settle(1_500)
        val entries = ForegroundAppLog.read(ctx)
        assertTrue("条数应有界：${entries.size}", entries.size <= ForegroundAppLog.MAX_ENTRIES)
        assertEquals("com.p${total - 1}", entries.last().pkg)
    }

    @Test
    fun `clear 之后读不到任何记录`() {
        ForegroundAppLog.append(ctx, "com.x", null)
        settle()
        ForegroundAppLog.clear(ctx)
        settle()
        assertTrue(ForegroundAppLog.read(ctx).isEmpty())
    }

    @Test
    fun `包名解析不到时 label 为 null 也能写入`() {
        ForegroundAppLog.append(ctx, "com.definitely.not.installed", null)
        settle()
        val entries = ForegroundAppLog.read(ctx)
        assertEquals(1, entries.size)
        assertEquals("com.definitely.not.installed", entries[0].pkg)
    }
}
