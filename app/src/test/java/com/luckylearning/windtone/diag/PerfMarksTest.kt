package com.luckylearning.windtone.diag

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.ui.AppStrings
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class PerfMarksTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppStrings.init(context)
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

    /**
     * 2026-11-01：Application 每步的耗时也要进报告。
     *
     * 车机冷启动 12 秒那次，`app:onCreate @1ms → activity:onCreate @11534ms` 之间
     * 11.5 秒是空白 —— 而 `MusicApp.step()` 其实每一步都写了 `ok:<name>`，
     * 只是旧报告只挑四个固定 stage，把它们全丢了。没有这层分布，就没法判断
     * 是「我们哪一步慢」还是「系统加载类慢」。
     */
    @Test
    fun `上一轮的每步耗时写进性能段`() {
        BootTrace.begin(context)
        BootTrace.mark(context, "ok:crash-log")
        BootTrace.mark(context, "ok:cover")
        BootTrace.mark(context, "activity:attach")
        BootTrace.markFrame(context)
        BootTrace.begin(context) // 归档上一轮

        val text = PerfMarks.buildReport(context)

        assertTrue(text, text.contains("启动各步耗时"))
        assertTrue(text, text.contains("ok:crash-log"))
        assertTrue(text, text.contains("ok:cover"))
        // attach 这一行是判断「时间花在系统侧还是我们 onCreate」的分界线
        assertTrue(text, text.contains("activity:attach"))
    }
}
