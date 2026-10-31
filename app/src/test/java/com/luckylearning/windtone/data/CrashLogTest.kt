package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「上次异常退出」那一页的取数口径（v1.1.13 加）。
 *
 * 背景：车机上出现过「装上就黑屏、连系统崩溃对话框都没有」，用户进不到设置页，
 * 日志写了也没人看得到。于是启动时把**上一轮没看过的**崩溃主动摆到屏幕上。
 * 这里钉住三件事：没有就不弹、崩过就弹、看过就不再弹（否则每次启动都被挡住）。
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 每个用例复用同一份 filesDir，先清干净
        CrashLog.clear(context)
        context.getSharedPreferences("crash_seen", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun writeCrash(name: String, body: String, mtime: Long): File =
        File(CrashLog.dir(context), name).apply {
            writeText(body)
            setLastModified(mtime)
        }

    @Test
    fun noCrashFileMeansNothingToShow() {
        assertNull(CrashLog.latestUnseen(context))
    }

    // -------------------------------------------------- 内存快照不干扰崩溃记录（v1.2.40）

    @Test
    fun lowMemorySnapshotIsNotCountedAsACrash() {
        // 快照是滚动覆盖的单份文件。若混进 files()，就会顶掉「最近一次崩溃」，
        // 启动时那张主动弹给用户的页拿到的就不是栈了。
        CrashLog.recordLowMemory(context, 15)
        writeCrash("crash-1.txt", "boom", 1_000L)

        assertEquals(1, CrashLog.files(context).size)
        assertEquals("crash-1.txt", CrashLog.files(context).first().name)
        assertEquals("boom", CrashLog.latest(context))
    }

    @Test
    fun lowMemorySnapshotSurvivesPruningButIsStillExported() {
        repeat(8) { writeCrash("crash-$it.txt", "b$it", it.toLong()) }
        CrashLog.recordLowMemory(context, 10)
        // prune 只按崩溃份数（KEEP=5）淘汰，不该顺手删掉快照
        CrashLog.record(context, "x", "y")

        assertTrue(File(CrashLog.dir(context), CrashLog.LOW_MEMORY_FILE).isFile)
        assertEquals(5, CrashLog.files(context).size)

        val text = CrashLog.allText(context)
        assertTrue(text.contains(CrashLog.LOW_MEMORY_FILE))
        assertTrue(text.contains("资源快照"))
    }

    @Test
    fun clearRemovesTheSnapshotToo() {
        CrashLog.recordLowMemory(context, 15)
        CrashLog.clear(context)
        assertEquals(0, CrashLog.dir(context).listFiles()?.size ?: 0)
    }

    @Test
    fun crashFileIsShownOnceThenNeverAgain() {
        writeCrash("crash-20261017-114100.txt", "java.lang.IllegalStateException: boom", 1_000L)

        val shown = CrashLog.latestUnseen(context)
        assertNotNull("崩过就必须摆到屏幕上，否则用户没有任何取证手段", shown)
        assertTrue(shown!!.contains("boom"))

        CrashLog.markSeen(context)
        assertNull("看过一次之后不能每次启动都挡路", CrashLog.latestUnseen(context))
    }

    @Test
    fun newerCrashShowsAgainAfterTheOldOneWasSeen() {
        writeCrash("crash-20261017-114100.txt", "first", 1_000L)
        CrashLog.markSeen(context)
        assertNull(CrashLog.latestUnseen(context))

        writeCrash("crash-20261017-120000.txt", "second", 2_000L)
        assertEquals("second", CrashLog.latestUnseen(context)?.trim())
    }

    @Test
    fun diagFilesDoNotTriggerTheCrashScreen() {
        // diag-* 是「非致命诊断」，不该被当成崩溃弹给用户看
        writeCrash("diag-20261017-114100.txt", "overlay failed", 1_000L)
        assertNull(CrashLog.latestUnseen(context))
    }

    @Test
    fun `导出头带 mapping 版本与应用版本`() {
        writeCrash("crash-20261026-120000.txt", "java.lang.IllegalStateException: boom", 3_000L)
        val text = CrashLog.allText(context)
        assertTrue(text.contains("mapping：") || text.contains("mapping:"))
        assertTrue(text.contains(CrashLog.mappingAssetName()))
        assertTrue(text.contains("retrace"))
        assertTrue(text.contains("boom"))
    }

    @Test
    fun `单条崩溃文件头也写 mapping`() {
        // 走 record 路径验证 header()
        CrashLog.record(context, "unit", "hello-stack")
        val file = CrashLog.files(context).first { it.name.startsWith("diag-") }
        val body = file.readText()
        assertTrue(body.contains(CrashLog.mappingAssetName()))
        assertTrue(body.contains("hello-stack"))
    }

}
