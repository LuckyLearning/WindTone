package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 前台应用状态与「按应用隐藏」的判定（v1.1.29，设计见 docs/设计-悬浮窗按应用隐藏.md）。
 *
 * 这一组全是纯函数 / 纯内存对象，不碰 Android 框架 —— 检测通道（无障碍服务 /
 * UsageStats）的授权状态只能在真机上验，但「谁在前台、在不在名单里、要不要检测」
 * 这些判断必须在这里钉死。
 *
 * ⚠️ [ForegroundAppState] 是进程内单例，**用例之间必须清零**：JUnit 不保证
 * 执行顺序，上一个用例残留的包名会让下一个的「首次上报」断言反过来。
 */
class ForegroundAppStateTest {

    @Before
    fun setUp() {
        ForegroundAppState.reset()
    }

    // ---------------------------------------------------------------- 去重

    @Test
    fun `相同应用不重复上报`() {
        // 第一次上报（从「未知」到 com.a）是切换，要记
        assertTrue(ForegroundAppState.report("com.a"))
        // 同一个应用再次上报不是切换，不记
        assertFalse(ForegroundAppState.report("com.a"))
        assertTrue(ForegroundAppState.report("com.b"))
    }

    @Test
    fun `空包名被忽略`() {
        assertFalse(ForegroundAppState.report(null))
        assertFalse(ForegroundAppState.report(""))
        assertNull(ForegroundAppState.currentPkg.value)
    }

    @Test
    fun `最近切换按旧到新保留，最多 20 条`() {
        (1..25).forEach { ForegroundAppState.report("com.p$it") }
        val recent = ForegroundAppState.recent()
        assertEquals("只保留最近 20 条", 20, recent.size)
        assertEquals("com.p6", recent.first().pkg)
        assertEquals("com.p25", recent.last().pkg)
    }

    @Test
    fun `recentFlow 跟随切换更新，reset 后清空`() {
        ForegroundAppState.report("com.a")
        assertEquals(listOf("com.a"), ForegroundAppState.recentFlow.value.map { it.pkg })
        ForegroundAppState.reset()
        assertTrue(ForegroundAppState.recentFlow.value.isEmpty())
    }

    @Test
    fun `reset 同时清空最近切换`() {
        ForegroundAppState.report("com.a")
        assertEquals(1, ForegroundAppState.recent().size)
        ForegroundAppState.reset()
        assertTrue(ForegroundAppState.recent().isEmpty())
    }

    @Test
    fun `重复上报同一个应用不重复入队`() {
        ForegroundAppState.report("com.a")
        ForegroundAppState.report("com.a")
        ForegroundAppState.report("com.b")
        assertEquals(listOf("com.a", "com.b"), ForegroundAppState.recent().map { it.pkg })
    }

    @Test
    fun `reset 清空当前包名`() {
        ForegroundAppState.report("com.a")
        ForegroundAppState.reset()
        assertNull(ForegroundAppState.currentPkg.value)
    }

    // ---------------------------------------------------------------- 名单匹配

    @Test
    fun `空名单一律不隐藏`() {
        assertFalse(overlayHiddenForForeground("com.a", emptySet()))
        // 名单为空时连「当前是谁」都不必知道 —— 检测都不该启动
        assertFalse(foregroundDetectionNeeded(emptySet(), logEnabled = false))
    }

    @Test
    fun `命中与未命中`() {
        val hidden = setOf("com.byd.avm", "com.iqiyi.car")
        assertTrue(overlayHiddenForForeground("com.byd.avm", hidden))
        assertTrue(overlayHiddenForForeground("com.iqiyi.car", hidden))
        assertFalse(overlayHiddenForForeground("com.luckylearning.windtone", hidden))
    }

    @Test
    fun `不知道前台是谁时不隐藏`() {
        // 无权限放行：查不到前台应用 ≠ 命中名单，绝不能误伤
        assertFalse(overlayHiddenForForeground(null, setOf("com.byd.avm")))
    }

    @Test
    fun `两个理由任一成立都需要检测`() {
        assertTrue(foregroundDetectionNeeded(setOf("com.byd.avm"), logEnabled = false))
        assertTrue(foregroundDetectionNeeded(emptySet(), logEnabled = true))
        assertFalse(foregroundDetectionNeeded(emptySet(), logEnabled = false))
    }
}

/**
 * 前台应用滚动记录的纯函数部分（v1.1.29）。
 *
 * 文件读写要 Android 的 Context，放到仪器测试里不划算 —— 而这里钉的都是
 * 「一条记录长什么样、什么时候该记、留多少条」这些**格式与策略**，
 * 正是最容易在真机上以「历史复读 / 丢一半 / 坏行炸全局」形式翻车的地方。
 */
class ForegroundAppLogTest {

    private fun entry(time: Long, pkg: String, label: String? = null) =
        ForegroundAppLog.Entry(time, pkg, label)

    @Test
    fun `连续相同应用不记`() {
        assertTrue(ForegroundAppLog.shouldRecord(null, "com.a"))
        assertTrue(ForegroundAppLog.shouldRecord("com.b", "com.a"))
        assertFalse(ForegroundAppLog.shouldRecord("com.a", "com.a"))
        assertFalse(ForegroundAppLog.shouldRecord("com.a", ""))
    }

    @Test
    fun `ABAB 算两次切换都记`() {
        assertTrue(ForegroundAppLog.shouldRecord("com.a", "com.b"))
        assertTrue(ForegroundAppLog.shouldRecord("com.b", "com.a"))
    }

    @Test
    fun `滚动只保留最后三百条`() {
        val many = (1..350).map { entry(it.toLong(), "com.pkg$it") }
        val trimmed = ForegroundAppLog.trim(many)
        assertEquals(ForegroundAppLog.MAX_ENTRIES, trimmed.size)
        assertEquals(350L, trimmed.last().timeMs)
        assertEquals(51L, trimmed.first().timeMs)
    }

    @Test
    fun `不超过上限时原样返回`() {
        val few = listOf(entry(1, "com.a"), entry(2, "com.b"))
        assertEquals(few, ForegroundAppLog.trim(few))
    }

    @Test
    fun `行格式往返`() {
        val e = entry(1_700_000_000_000, "com.byd.avm", "全景影像")
        val back = ForegroundAppLog.parseLine(ForegroundAppLog.formatLine(e))
        assertEquals(e, back)
    }

    @Test
    fun `应用名缺失时只存包名`() {
        val line = ForegroundAppLog.formatLine(entry(1, "com.byd.avm", null))
        val back = ForegroundAppLog.parseLine(line)
        assertEquals("com.byd.avm", back?.pkg)
        assertNull(back?.label)
    }

    @Test
    fun `损坏行静默跳过`() {
        assertNull(ForegroundAppLog.parseLine(""))
        assertNull(ForegroundAppLog.parseLine("不是制表符分隔"))
        assertNull(ForegroundAppLog.parseLine("abc\tcom.a"))
        assertNull(ForegroundAppLog.parseLine("123\t"))
    }

    @Test
    fun `复制用文本一行一条带时间`() {
        val text = ForegroundAppLog.formatForCopy(
            listOf(entry(1_700_000_000_000, "com.byd.avm", "全景影像")),
        )
        assertTrue(text.contains("com.byd.avm"))
        assertTrue(text.contains("全景影像"))
        assertTrue(text.contains(":"))
        assertFalse(text.contains("\t"))
    }

    @Test
    fun `应用名解析不到时复制文本有标注`() {
        val text = ForegroundAppLog.formatForCopy(listOf(entry(1, "com.x", null)))
        assertTrue(text.contains("解析不到"))
    }

    @Test
    fun `输入法事件被忽略，普通应用不忽略`() {
        val ime = setOf("com.iflytek.inputmethod")
        assertTrue(shouldIgnoreForegroundEvent("com.iflytek.inputmethod", ime))
        assertTrue(!shouldIgnoreForegroundEvent("com.byd.avc", ime))
        // 拿不到输入法列表时不能误伤：一律不忽略
        assertTrue(!shouldIgnoreForegroundEvent("com.iflytek.inputmethod", emptySet()))
        assertTrue(!shouldIgnoreForegroundEvent(null, ime))
    }
}
