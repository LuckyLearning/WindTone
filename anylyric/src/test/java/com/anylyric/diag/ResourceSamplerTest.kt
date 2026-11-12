package com.anylyric.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源采样的解析与换算测试。
 *
 * 这些函数读的是 `/proc` 与 `Debug` 的原始文本，真机上出问题只会表现为「数字不对」，
 * 很难当场看出是解析错了。把样例文本固定下来，就守住「CPU 占用不能算成 8 倍」这类坑。
 */
class ResourceSamplerTest {

    /** 进程名里带空格和括号 —— 真实进程名可能长这样，必须从最后一个 ')' 之后开始数。 */
    private val stat = "1234 (my app (beta)) S 1 1234 1234 0 -1 4194560 " +
        "100 200 0 0 350 150 0 0 20 0 18 0 12345 1000000 5000 18446744073709551615 " +
        "0 0 0 0 0 0 0 0 0 0 0 0 0 0"

    @Test
    fun parsesUtimeAndStime() {
        // utime = 350，stime = 150
        assertEquals(500L, ResourceSampler.parseCpuTicks(stat))
    }

    @Test
    fun rejectsBrokenStat() {
        assertEquals(-1L, ResourceSampler.parseCpuTicks(null))
        assertEquals(-1L, ResourceSampler.parseCpuTicks(""))
        assertEquals(-1L, ResourceSampler.parseCpuTicks("garbage"))
        assertEquals(-1L, ResourceSampler.parseCpuTicks("1 (x) S 1 2 3"))
    }

    /** 一个核跑满 = 100%。滴答按 USER_HZ=100 折算。 */
    @Test
    fun computesCpuPercent() {
        // 2 秒内用掉 100 个滴答 = 1 秒 CPU = 50%
        assertEquals(50, ResourceSampler.cpuPercent(deltaTicks = 100, deltaMs = 2000, hertz = 100))
        // 2 秒内用掉 200 个滴答 = 2 秒 CPU = 100%
        assertEquals(100, ResourceSampler.cpuPercent(deltaTicks = 200, deltaMs = 2000, hertz = 100))
        // 双核满载可以超过 100%
        assertEquals(200, ResourceSampler.cpuPercent(deltaTicks = 400, deltaMs = 2000, hertz = 100))
    }

    @Test
    fun cpuPercentRejectsInvalidInput() {
        assertEquals(-1, ResourceSampler.cpuPercent(-1, 1000, 100))
        assertEquals(-1, ResourceSampler.cpuPercent(100, 0, 100))
        assertEquals(-1, ResourceSampler.cpuPercent(100, 1000, 0))
        // 首次采样没有基准时长，必须是「未知」而不是 0
        assertEquals(-1, ResourceSampler.cpuPercent(0, -1, 100))
    }

    @Test
    fun parsesStatusValues() {
        val status = """
            Name:	com.anylyric
            State:	S (sleeping)
            Threads:	17
            VmRSS:	    12345 kB
            FDSize:	256
        """.trimIndent()
        assertEquals(17, ResourceSampler.parseStatusValue(status, "Threads:"))
        assertEquals(12345, ResourceSampler.parseStatusValue(status, "VmRSS:"))
        assertEquals(-1, ResourceSampler.parseStatusValue(status, "VmHWM:"))
        assertEquals(-1, ResourceSampler.parseStatusValue(null, "Threads:"))
    }

    /** 一行日志要能直接塞进报告：不换行、不超长。 */
    @Test
    fun sampleLineIsSingleLine() {
        val line = ResourceSampler.Sample(
            pssKb = 120 * 1024,
            javaUsedKb = 8 * 1024,
            javaMaxKb = 48 * 1024,
            nativeKb = 3 * 1024,
            cpuPercent = 7,
            threads = 17,
            fds = 42,
            gcCount = 5,
            uptimeSec = 90
        ).oneLine()
        assertTrue(line.contains("pss=120.0MB"))
        assertTrue(line.contains("cpu=7%"))
        assertTrue(line.contains("threads=17"))
        assertTrue(!line.contains('\n'))
    }

    /** 读不到的值显示 '?'，不能显示 -1 让人误以为是真实数据。 */
    @Test
    fun unknownValuesAreMarked() {
        val line = ResourceSampler.Sample(-1, 0, 0, 0, -1, 1, -1, -1, 0).oneLine()
        assertTrue(line.contains("cpu=?"))
        assertTrue(line.contains("gc=?"))
        assertTrue(line.contains("pss=?"))
    }
}
