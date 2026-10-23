package com.luckylearning.windtone.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackFragmentTest {

    @Test
    fun `同一盘和文件编出稳定键，直链不参与`() {
        val fragment = TrackFragment.of("drive/1", "file:a")
        val parsed = TrackFragment.parse(fragment)
        assertEquals("drive/1" to "file:a", parsed)
        assertEquals("aliyun:$fragment", TrackFragment.cacheKey(fragment))
    }

    @Test
    fun `不是阿里云盘的 fragment 不算身份`() {
        assertNull(TrackFragment.parse("wt=1"))
        assertNull(TrackFragment.parse(null))
        assertNull(TrackFragment.cacheKey("wt=1"))
    }
}
