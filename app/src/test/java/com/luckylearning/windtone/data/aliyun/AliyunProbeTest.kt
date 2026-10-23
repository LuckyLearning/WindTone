package com.luckylearning.windtone.data.aliyun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放自检的**结论映射**（纯函数部分）。
 *
 * 自检存在的理由：播放器只会抛 `ERROR_CODE_IO_UNSPECIFIED`，这个码既可能是
 * 「直链 403 / 域名连不上」，也可能是「本地缓存坏了」。自检拿真实 HTTP 结果
 * 把范围缩到一层，用户截个图就能定性，不必再来回猜。
 */
class AliyunProbeTest {

    @Test
    fun okWhenPartialContentWithBytes() {
        val r = AliyunProbe.Result(httpCode = 206, bytes = 1024, elapsedMs = 180, host = "cdn.example.com")
        assertTrue(r.ok)
        assertTrue(r.summary().contains("直链可下载"))
        assertTrue(r.summary().contains("1024"))
    }

    @Test
    fun forbiddenIsNotOk() {
        val r = AliyunProbe.Result(httpCode = 403, bytes = 0, elapsedMs = 90, host = "cdn.example.com")
        assertFalse(r.ok)
        assertTrue(r.summary().contains("403"))
        assertTrue(r.summary().contains("权限"))
    }

    @Test
    fun connectFailureSaysNetwork() {
        val r = AliyunProbe.Result(httpCode = 0, bytes = 0, elapsedMs = 10_000, host = "cdn.example.com")
        assertFalse(r.ok)
        assertTrue(r.summary().contains("连不上直链服务器"))
        assertTrue("主机要出现在结论里，排障才知道打到哪", r.summary().contains("cdn.example.com"))
    }

    @Test
    fun successWithoutBytesIsNotOk() {
        // 200 但一个字节都没读到：等于打不开，不能算通过
        assertFalse(AliyunProbe.Result(httpCode = 200, bytes = 0, elapsedMs = 50, host = "h").ok)
    }

    @Test
    fun parseTotalBytesFromContentRange() {
        assertEquals(3_456_789L, AliyunProbe.parseTotalBytes("bytes 0-1023/3456789"))
        assertEquals(0L, AliyunProbe.parseTotalBytes("bytes 0-1023/*"))
        assertEquals(0L, AliyunProbe.parseTotalBytes(null))
        assertEquals(0L, AliyunProbe.parseTotalBytes("garbage"))
    }
}
