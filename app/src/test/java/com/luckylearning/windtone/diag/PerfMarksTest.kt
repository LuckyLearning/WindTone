package com.luckylearning.windtone.diag

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.BootTrace
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfMarksTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PerfMarks.resetForTest()
        // 清掉上一轮 boot 文件，避免串味
        BootTrace.begin(context)
    }

    @Test
    fun `未挂载时性能段如实说明`() {
        val text = PerfMarks.buildReport(context)
        assertTrue(text.contains("[性能]"))
        assertTrue(text.contains("悬浮窗挂载"))
        assertTrue(text.contains("尚未挂载") || text.contains("本进程尚未挂载"))
    }

    @Test
    fun `挂载成功记下耗时`() {
        PerfMarks.overlayMountBegin()
        Thread.sleep(5)
        PerfMarks.overlayMountEnd(ok = true)
        val text = PerfMarks.buildReport(context)
        assertTrue(text, text.contains("成功"))
        assertTrue(text, text.contains("ms"))
        assertTrue(PerfMarks.overlayMountCostMs() != null && PerfMarks.overlayMountCostMs()!! >= 0)
    }

    @Test
    fun `上一轮有首帧时冷启动段写出耗时`() {
        BootTrace.begin(context)
        BootTrace.mark(context, BootTrace.STAGE_ACTIVITY)
        BootTrace.markFrame(context)
        BootTrace.begin(context) // 归档上一轮
        val text = PerfMarks.buildReport(context)
        assertTrue(text, text.contains("已出首帧") || text.contains(BootTrace.STAGE_FRAME))
    }
}
