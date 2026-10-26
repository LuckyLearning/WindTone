package com.luckylearning.windtone.diag

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 诊断包的版式与内容（v1.1.88）。
 *
 * 这份文本是**唯一**的回传载体：用户在车机上问「为什么」，我们只能靠它判断。
 * 所以这里锁三件事：
 *  1. 七个小节一个都不能少（少一节就少一条排查线索）；
 *  2. 空数据要给出可读的占位，而不是空白或 null；
 *  3. 不能把 URL / token 这类东西带出去（只报音源类型）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsReportTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun report(): String = DiagnosticsReport.build(
        DiagnosticsReport.collect(
            context = context,
            player = PlayerUiState(),
            library = LibraryUiState(),
        ),
    )

    @Test
    fun `七个小节齐全`() {
        val text = report()
        listOf("[环境]", "[权限]", "[播放]", "[悬浮窗]", "[曲库]", "=== 听风 · 前台识别排障报告 ===", "[事件日志")
            .forEach { assertTrue("缺少小节：$it", text.contains(it)) }
    }

    @Test
    fun `空数据给出占位而不是空白`() {
        val text = report()
        assertTrue(text.contains("曲目：（无）"))
        assertTrue(text.contains("音源：（无）"))
        assertTrue(text.contains("状态：（空）") || text.contains("状态：—") || text.contains("状态："))
    }

    @Test
    fun `不打印完整 URL，只报音源类型`() {
        val text = report()
        assertFalse("诊断包不应出现 http 链接", text.contains("http://"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    @Test
    fun `报告里有安装包签名一行`() {
        assertTrue(report().contains("安装包签名："))
    }

    @Test
    fun `事件日志为空时说明原因`() {
        DiagLog.clear()
        val text = report()
        assertTrue(text.contains("（空：进程刚启动"))
    }

    @Test
    fun `事件日志按时间顺序带标签`() {
        DiagLog.clear()
        DiagLog.log("FG", "前台 → com.a")
        DiagLog.log("OVERLAY", "可见性 隐藏（前台=com.a）")
        val text = report()
        assertTrue(text.contains("[FG] 前台 → com.a"))
        assertTrue(text.contains("[OVERLAY] 可见性 隐藏（前台=com.a）"))
        val fg = text.indexOf("[FG]")
        val overlay = text.indexOf("[OVERLAY]")
        assertTrue("旧事件应排在前面", fg < overlay)
    }

    @Test
    fun `含性能与播放质量两段`() {
        val text = report()
        assertTrue("缺性能段", text.contains("[性能]"))
        assertTrue("缺播放质量段", text.contains("[播放质量]"))
    }

    @Test
    fun `含一键取证与 mapping 版本`() {
        val text = report()
        assertTrue(text.contains("[一键取证]"))
        assertTrue(text.contains("adb"))
        assertTrue(text.contains("mapping-v") || text.contains("mapping："))
    }

}
