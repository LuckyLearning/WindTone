package com.luckylearning.windtone.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProcStats] 的解析测试。
 *
 * 全部喂字符串，不碰真实 `/proc` —— 解析是纯函数，这也是把它拆成
 * `parseXxx(text)` 的唯一理由：车机上没法调试，只能靠单测把格式钉死。
 */
class ProcStatsTest {

    // ---------------------------------------------------------------- status

    @Test
    fun `status 取出线程数与内存`() {
        val text = """
            Name:	com.luckylearning.windtone
            State:	S (sleeping)
            Tgid:	1234
            VmPeak:	  612345 kB
            VmSize:	  598765 kB
            VmLck:	       0 kB
            VmHWM:	  524288 kB
            VmRSS:	  497664 kB
            Threads:	38
        """.trimIndent()

        val proc = ProcStats.parseStatus(text, fds = 214)

        assertEquals(38, proc.threads)
        assertEquals(214, proc.fds)
        assertEquals(497664L, proc.vmRssKb)
        assertEquals(524288L, proc.vmHwmKb)
    }

    @Test
    fun `status 缺字段时不抛异常且给零`() {
        val proc = ProcStats.parseStatus("Name:\tfoo\n", fds = -1)

        assertEquals(-1, proc.threads)
        assertEquals(-1, proc.fds)
        assertEquals(0L, proc.vmRssKb)
        assertEquals(0L, proc.vmHwmKb)
    }

    // ------------------------------------------------------------------ stat

    @Test
    fun `stat 按字节偏移取 utime stime starttime`() {
        // 字段位：pid (comm) state ppid … utime(14) stime(15) … starttime(22)
        val text = "1234 (windtone) S 1 1234 1234 0 -1 4194560 " +
            "12345 0 0 0 " +          // minflt cminflt majflt cmajflt
            "100 50 0 0 " +           // utime stime cutime cstime
            "20 0 38 0 " +            // priority nice num_threads itrealvalue
            "123456789 1000000000 50000"
        val stat = ProcStats.parseStat(text)

        assertNotNull(stat)
        assertEquals(100L, stat!!.utime)
        assertEquals(50L, stat.stime)
        assertEquals(123456789L, stat.starttime)
        assertEquals(150L, stat.total)
    }

    @Test
    fun `stat 进程名里带空格和括号也不错位`() {
        val text = "77 (Web Content) S 1 77 77 0 -1 4194560 " +
            "0 0 0 0 " +
            "7 3 0 0 " +
            "20 0 12 0 " +
            "999 0 0"

        val stat = ProcStats.parseStat(text)

        assertNotNull(stat)
        assertEquals(7L, stat!!.utime)
        assertEquals(3L, stat.stime)
        assertEquals(999L, stat.starttime)
        assertEquals("Web Content", ProcStats.threadName(text))
    }

    @Test
    fun `stat 字段不足时返回 null 而不是抛异常`() {
        assertNull(ProcStats.parseStat("1234 (x) S 1 2"))
        assertNull(ProcStats.parseStat("garbage"))
        assertNull(ProcStats.parseStat(""))
    }

    @Test
    fun `uptime 取第一个浮点`() {
        assertEquals(12345.67, ProcStats.parseUptimeSec("12345.67 45678.90\n")!!, 0.001)
        assertNull(ProcStats.parseUptimeSec("abc def"))
    }

    // ----------------------------------------------------------------- smaps

    @Test
    fun `smaps 同类映射合并并按 Private_Dirty 降序`() {
        val smaps = """
            7f0000000000-7f0000100000 rw-p 00000000 00:00 0                          [anon:libc_malloc]
            Size:               1024 kB
            Rss:                 768 kB
            Pss:                 700 kB
            Private_Clean:         0 kB
            Private_Dirty:       768 kB
            7f0000200000-7f0000300000 r-xp 00000000 fd:00 1234                       /system/lib64/libc.so
            Size:               4096 kB
            Rss:                 512 kB
            Pss:                 128 kB
            Private_Clean:       200 kB
            Private_Dirty:        16 kB
            7f0000400000-7f0000800000 rw-p 00000000 00:00 0                          [anon:libc_malloc]
            Size:               8192 kB
            Rss:                2048 kB
            Pss:                2000 kB
            Private_Clean:         0 kB
            Private_Dirty:      2048 kB
            7f1000000000-7f1000800000 rw-p 00000000 00:00 0
            Size:               8192 kB
            Rss:                  64 kB
            Pss:                  32 kB
            Private_Clean:         0 kB
            Private_Dirty:        64 kB
        """.trimIndent()

        val buckets = ProcStats.parseSmaps(smaps.lineSequence())

        assertEquals(3, buckets.size)
        // libc malloc 两条合并 = 768 + 2048
        assertEquals("libc malloc（位图/解码缓冲）", buckets[0].label)
        assertEquals(2816L, buckets[0].privateDirtyKb)
        assertEquals(2816L, buckets[0].rssKb)
        assertEquals(2700L, buckets[0].pssKb)
        // 无名字匿名映射排在原生库之前（64 > 16）
        assertEquals("匿名（无名字）", buckets[1].label)
        assertEquals(64L, buckets[1].privateDirtyKb)
        assertEquals("原生库（.so）", buckets[2].label)
        assertEquals(16L, buckets[2].privateDirtyKb)
    }

    @Test
    fun `smaps 合计等于各分类之和`() {
        val smaps = """
            7f0000000000-7f0000100000 rw-p 00000000 00:00 0                          [anon:libc_malloc]
            Rss:                 100 kB
            Pss:                  90 kB
            Private_Dirty:       100 kB
            7f0000200000-7f0000300000 rw-s 00000000 00:00 0                          /dev/kgsl-3d0
            Rss:                 200 kB
            Pss:                 180 kB
            Private_Dirty:       150 kB
        """.trimIndent()

        val s = ProcStats.Smaps(ProcStats.parseSmaps(smaps.lineSequence()))

        assertEquals(250L, s.privateDirtyKb)
        assertEquals(300L, s.rssKb)
        assertEquals(270L, s.pssKb)
    }

    @Test
    fun `smaps 空输入返回空表`() {
        assertTrue(ProcStats.parseSmaps(emptySequence<String>()).isEmpty())
        assertTrue(ProcStats.parseSmaps("garbage\nno header here".lineSequence()).isEmpty())
    }

    @Test
    fun `smaps 把数值行误判成头部的情况被排除`() {
        // "Size:" 之类不含 '-'，必须走数值分支而不是新开一条映射
        val smaps = """
            7f0000000000-7f0000100000 r--p 00000000 fd:00 42                         /system/fonts/NotoSansCJK.ttc
            Size:               1024 kB
            Rss:                 256 kB
            Pss:                 128 kB
            Private_Dirty:        32 kB
        """.trimIndent()

        val buckets = ProcStats.parseSmaps(smaps.lineSequence())

        assertEquals(1, buckets.size)
        assertEquals("字体", buckets[0].label)
        assertEquals(32L, buckets[0].privateDirtyKb)
    }

    // -------------------------------------------------------------- classify

    @Test
    fun `classify 覆盖车机上真实会出现的映射`() {
        assertEquals("libc malloc（位图/解码缓冲）", ProcStats.classify("[anon:libc_malloc]"))
        assertEquals("匿名：GfxDriver", ProcStats.classify("[anon:GfxDriver]"))
        assertEquals("匿名：gralloc", ProcStats.classify("[anon:gralloc]"))
        assertEquals("线程栈（[stack]）", ProcStats.classify("[stack:1234]"))
        assertEquals("线程栈（[stack]）", ProcStats.classify("[stack]"))
        assertEquals("GPU 驱动（kgsl）", ProcStats.classify("/dev/kgsl-3d0"))
        assertEquals("GPU 驱动（mali）", ProcStats.classify("/dev/mali0"))
        assertEquals("设备/共享映射（/dev）", ProcStats.classify("/dev/ashmem/foo (deleted)"))
        assertEquals("原生库（.so）", ProcStats.classify("/data/app/lib/arm64/libc++_shared.so"))
        assertEquals("ART 代码/数据", ProcStats.classify("/data/dalvik-cache/arm64/system@framework@boot.oat"))
        assertEquals("字体", ProcStats.classify("/system/fonts/NotoSansCJK-Regular.ttc"))
        assertEquals("文件映射（资源/媒体）", ProcStats.classify("/data/app/base.apk"))
        assertEquals("匿名（无名字）", ProcStats.classify(""))
        assertEquals("匿名（无名字）", ProcStats.classify("   "))
    }
}
