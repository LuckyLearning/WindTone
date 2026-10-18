package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开机自启的诊断文案（v1.1.27）。
 *
 * 这条链路只有真车能验，失败表现又全是「开机后什么都没发生」。这几种成因
 * 必须被这句话分开说清楚 —— 用户读到的结论直接决定他下一步去改什么：
 * 「从未收到广播」要去系统里开自启动权限，「未起播：曲库是空的」则不用动权限。
 * 所以文案本身是功能，不是装饰。
 */
class BootStatusTextTest {

    private fun snap(at: Long, action: String, ok: Boolean?, reason: String = "") =
        BootStatus.Snapshot(atMillis = at, action = action, ok = ok, reason = reason)

    @Test
    fun `没收到广播时说从未收到`() {
        val s = snap(0L, BootStatus.ACTION_NONE, null)
        assertEquals("还没有收到过开机广播", BootStatusText.resultLabel(s, now = 1_000L))
    }

    @Test
    fun `成功时带重试次数`() {
        val s = snap(100L, BootStatus.ACTION_AUTOPLAY, true, "第 2 次尝试成功")
        val label = BootStatusText.resultLabel(s, now = 200L)
        assertTrue(label, label.startsWith("已起播"))
        assertTrue("要能看出是重试才成功的", label.contains("第 2 次"))
    }

    @Test
    fun `失败时必须带原因而不是只写失败`() {
        val s = snap(100L, BootStatus.ACTION_AUTOPLAY, false, "曲库是空的")
        val label = BootStatusText.resultLabel(s, now = 200L)
        assertTrue(label, label.contains("未起播"))
        assertTrue("没有原因就没法排查：$label", label.contains("曲库是空的"))
    }

    @Test
    fun `失败但没写原因也要有兜底`() {
        val s = snap(100L, BootStatus.ACTION_AUTOPLAY, false)
        assertTrue(BootStatusText.resultLabel(s, now = 200L).contains("原因未知"))
    }

    @Test
    fun `刚收到广播还没结论时说正在恢复`() {
        val s = snap(1_000L, BootStatus.ACTION_AUTOPLAY, null)
        assertEquals("正在恢复队列…", BootStatusText.resultLabel(s, now = 1_500L))
    }

    @Test
    fun `超过一分钟还没结论按中断解释`() {
        val s = snap(1_000L, BootStatus.ACTION_AUTOPLAY, null)
        val label = BootStatusText.resultLabel(s, now = 1_000L + BootStatusText.STUCK_MS + 1)
        assertTrue(label, label.contains("被系统回收"))
    }

    @Test
    fun `决策文案分得清后台起播与打开界面`() {
        assertEquals(
            "后台起播（不打开界面）",
            BootStatusText.actionLabel(BootStatus.ACTION_AUTOPLAY),
        )
        assertEquals("打开主界面", BootStatusText.actionLabel(BootStatus.ACTION_OPEN_UI))
        assertEquals("什么都不做", BootStatusText.actionLabel(BootStatus.ACTION_NONE))
        // 未知值不能崩，也不能显示成空白
        assertEquals("什么都不做", BootStatusText.actionLabel("whatever"))
    }

    @Test
    fun `时间距今分档`() {
        assertEquals("—", BootStatusText.agoLabel(now = 1_000L, at = 0L))
        assertEquals("刚刚", BootStatusText.agoLabel(now = 30_000L, at = 1_000L))
        assertEquals("5 分钟前", BootStatusText.agoLabel(now = 1_000L + 5 * 60_000L, at = 1_000L))
        assertEquals("3 小时前", BootStatusText.agoLabel(now = 1_000L + 3 * 3_600_000L, at = 1_000L))
        assertEquals("2 天前", BootStatusText.agoLabel(now = 1_000L + 2 * 86_400_000L, at = 1_000L))
        // 时钟回拨（车机不少见）不能算出负数
        assertEquals("刚刚", BootStatusText.agoLabel(now = 1_000L, at = 10_000L))
    }
}
