package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 启动阶段追踪的判据（v1.1.14 加）。
 *
 * 这些用例存在的意义：车机上「打开全黑」有两种成因 —— 进程在 Application 里就挂了，
 * 和进程活着但没画出第一帧。**只有后者会被 [BootTrace.lastIncompleteBoot] 报出来**，
 * 而诊断页会不会出现完全取决于这个判据。判错了，整条取证链就断了（用户连诊断页都看不到），
 * 所以这里把「出没出首帧」钉死。
 */
@RunWith(RobolectricTestRunner::class)
class BootTraceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 复用同一份 filesDir：两个 trace 文件都清掉，否则用例相互污染
        CrashLog.clear(context)
        bootDir().listFiles()?.forEach { it.delete() }
    }

    private fun bootDir() = java.io.File(context.filesDir, "boot")

    @Test
    fun firstInstallHasNoPreviousRecord() {
        assertNull(BootTrace.lastIncompleteBoot(context))
    }

    @Test
    fun bootWithoutFirstFrameIsReported() {
        // 第一轮：走到了 setContent，但一帧都没画出去（车机黑屏就是这个形状）
        BootTrace.begin(context)
        BootTrace.mark(context, BootTrace.STAGE_ACTIVITY)
        BootTrace.mark(context, BootTrace.STAGE_CONTENT)

        // 第二轮启动：上一轮应当被判定为「不完整」
        BootTrace.begin(context)
        val record = BootTrace.lastIncompleteBoot(context)
        assertNotNull("没画成首帧的那一轮必须能被报出来，否则诊断页永远不出现", record)
        assertFalse(record!!.reachedFrame)
        assertEquals(BootTrace.STAGE_CONTENT, record.lastStage)
    }

    @Test
    fun bootThatReachedFirstFrameIsNotReported() {
        BootTrace.begin(context)
        BootTrace.mark(context, BootTrace.STAGE_ACTIVITY)
        BootTrace.markFrame(context)

        BootTrace.begin(context)
        assertNull("正常启动过的一轮不该让诊断页再弹出来", BootTrace.lastIncompleteBoot(context))
        assertTrue(BootTrace.previous(context)!!.reachedFrame)
    }

    @Test
    fun failureLinesAreRecordedAndKeptOutOfStageFlow() {
        BootTrace.begin(context)
        BootTrace.fail(context, "cover", IllegalStateException("boom"))

        BootTrace.begin(context)
        val record = BootTrace.previous(context)!!
        assertEquals(1, record.failures.size)
        assertTrue("失败行要能看出是哪一步挂的", record.failures.single().stage.contains("cover"))
        assertTrue(record.failures.single().note!!.contains("boom"))
        // 失败本身不该被算成「出过首帧」
        assertFalse(record.reachedFrame)
        // 同时要落一份到诊断目录，用户能在设置里导出
        assertTrue(CrashLog.files(context).any { it.name.startsWith("diag-") })
    }

    @Test
    fun notesAreFoldedToKeepOneLinePerStage() {
        BootTrace.begin(context)
        BootTrace.mark(context, "custom", "第一行\n第二行\t带制表符")

        BootTrace.begin(context)
        val line = BootTrace.previous(context)!!.lines.single { it.stage == "custom" }
        assertFalse("备注里的换行会把阶段表撑成多行，必须折掉", line.note!!.contains('\n'))
        assertTrue(line.note!!.contains("第一行"))
        assertTrue(line.note!!.contains("第二行"))
    }

    @Test
    fun parseSkipsGarbageLines() {
        val lines = BootTrace.parse(
            """
            12	app:onCreate

            乱码行没有制表符
            x	bad-elapsed
            34	activity:onCreate	备注
            """.trimIndent()
        )
        assertEquals(2, lines.size)
        assertEquals(12L, lines[0].elapsedMs)
        assertNull(lines[0].note)
        assertEquals("activity:onCreate", lines[1].stage)
        assertEquals("备注", lines[1].note)
    }

    @Test
    fun stayingOnTheDiagPageIsNotTreatedAsABrokenBoot() {
        // 用户看到诊断页但没点「进入应用」就退出了：同样没有 frame，但这不是故障，
        // 否则他不点进去，每次启动都会被这张页挡一次，永远出不来。
        BootTrace.begin(context)
        BootTrace.mark(context, BootTrace.STAGE_ACTIVITY)
        BootTrace.mark(context, BootTrace.STAGE_DIAG_SHOWN)

        BootTrace.begin(context)
        assertNull(BootTrace.lastIncompleteBoot(context))
    }

    @Test
    fun pressingEnterFromTheDiagPageKeepsTheSignal() {
        // 点过「进入应用」却没出首帧 —— 这正是「原生页能显示、Compose 画不出来」的形状，
        // 信号必须保留下来。
        BootTrace.begin(context)
        BootTrace.mark(context, BootTrace.STAGE_DIAG_SHOWN)
        BootTrace.mark(context, BootTrace.STAGE_CONTENT)

        BootTrace.begin(context)
        assertNotNull(BootTrace.lastIncompleteBoot(context))
    }

    @Test
    fun summaryDistinguishesStoppingOnTheDiagPageFromNotPainting() {
        val stayed = BootRecord(
            listOf(
                BootLine(0, BootTrace.STAGE_APP),
                BootLine(90, BootTrace.STAGE_DIAG_SHOWN),
            )
        )
        val text = BootTrace.summary(stayed)
        // 停在诊断页 ≠ 画不出来，两者的处置完全不同，文案不能混
        assertTrue(text.contains("停在诊断页"))
        assertFalse(text.contains("没有出首帧"))
    }

    @Test
    fun summaryStatesWhetherFirstFrameHappened() {
        val record = BootRecord(
            listOf(
                BootLine(0, BootTrace.STAGE_APP),
                BootLine(30, BootTrace.STAGE_ACTIVITY),
            )
        )
        val text = BootTrace.summary(record)
        assertTrue("诊断页第一句就要回答「上次画出来没有」", text.contains("没有出首帧"))
        assertTrue(text.contains(BootTrace.STAGE_ACTIVITY))
    }
}
