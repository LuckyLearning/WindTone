package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudUrlMemoTest {

    @Test
    fun `记了就能取回，没记是空`() {
        assertNull(CloudUrlMemo.peek("aliyun:ay=a/b"))
        CloudUrlMemo.remember("aliyun:ay=a/b", "https://cdn/x?sig=1#ay=a/b")
        assertEquals("https://cdn/x?sig=1#ay=a/b", CloudUrlMemo.peek("aliyun:ay=a/b"))
    }

    @Test
    fun `同键覆盖旧值`() {
        CloudUrlMemo.remember("k", "旧直链")
        CloudUrlMemo.remember("k", "新直链")
        assertEquals("新直链", CloudUrlMemo.peek("k"))
    }

    @Test
    fun `超过容量淘汰最久未用的`() {
        // 容量上限在实现里是私有常量，这里只验证「写入很多条后最早的被淘汰、最近的还在」
        for (i in 0 until 400) CloudUrlMemo.remember("k$i", "u$i")
        assertNull("最老的一批应被淘汰", CloudUrlMemo.peek("k0"))
        assertEquals("u399", CloudUrlMemo.peek("k399"))
    }
}
