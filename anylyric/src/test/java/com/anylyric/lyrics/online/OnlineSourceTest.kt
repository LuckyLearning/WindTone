package com.anylyric.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线源与顺序（0.2.27）。
 *
 * 这层的规则出错后果很具体：用户排好的顺序被悄悄改掉、旧配置让新源永远用不到、
 * 或者「上移」在第一条上把整个列表转了一圈。所以逐条钉住。
 */
class OnlineSourceTest {

    @Test
    fun `default order keeps qishui last`() {
        // 汽水匿名请求常年空体，排在前面只会白等两次超时。
        assertEquals(OnlineSource.QISHUI, OnlineSource.DEFAULT_ORDER.last())
        assertEquals(OnlineSource.DEFAULT_ORDER.size, OnlineSource.values().size)
    }

    @Test
    fun `of maps id and ignores blanks`() {
        assertEquals(OnlineSource.LRCLIB, OnlineSource.of("lrclib"))
        assertNull(OnlineSource.of(""))
        assertNull(OnlineSource.of(null))
        assertNull(OnlineSource.of("spotify"))
    }

    @Test
    fun `parse order keeps the user order`() {
        val parsed = OnlineSource.parseOrder("lrclib,netease,kugou,lyricsovh,qishui")
        assertEquals(
            listOf(
                OnlineSource.LRCLIB,
                OnlineSource.NETEASE,
                OnlineSource.KUGOU,
                OnlineSource.LYRICS_OVH,
                OnlineSource.QISHUI
            ),
            parsed
        )
    }

    @Test
    fun `parse order drops unknown ids`() {
        val parsed = OnlineSource.parseOrder("netease,gone-source,kugou")
        assertTrue(parsed.contains(OnlineSource.NETEASE))
        assertTrue(parsed.contains(OnlineSource.KUGOU))
        // 认不出的丢掉，其余源按出厂顺序补齐，列表长度仍等于枚举数。
        assertEquals(OnlineSource.values().size, parsed.size)
    }

    @Test
    fun `parse order appends sources missing from an old config`() {
        // 老配置（0.2.26 只有三家）升级后，新加的两家必须还能用到 —— 补在末尾。
        val parsed = OnlineSource.parseOrder("kugou,netease,qishui")
        assertEquals(listOf("kugou", "netease", "qishui", "lrclib", "lyricsovh"), parsed.map { it.id })
    }

    @Test
    fun `empty stored order falls back to default`() {
        assertEquals(OnlineSource.DEFAULT_ORDER, OnlineSource.parseOrder(null))
        assertEquals(OnlineSource.DEFAULT_ORDER, OnlineSource.parseOrder(""))
    }

    @Test
    fun `parse dedupes repeated ids`() {
        val parsed = OnlineSource.parseOrder("kugou,kugou,netease")
        assertEquals(parsed.size, parsed.distinct().size)
    }

    @Test
    fun `encode then parse round trips`() {
        val order = listOf(
            OnlineSource.LYRICS_OVH,
            OnlineSource.LRCLIB,
            OnlineSource.KUGOU,
            OnlineSource.QISHUI,
            OnlineSource.NETEASE
        )
        assertEquals(order, OnlineSource.parseOrder(OnlineSource.encodeOrder(order)))
    }

    @Test
    fun `move swaps neighbours`() {
        val order = OnlineSource.DEFAULT_ORDER
        val moved = OnlineSource.move(order, order[2], -1)
        assertEquals(order[1], moved[2])
        assertEquals(order[2], moved[1])

        val down = OnlineSource.move(order, order[1], 1)
        assertEquals(order[1], down[2])
    }

    @Test
    fun `move at edges is a no-op`() {
        val order = OnlineSource.DEFAULT_ORDER
        assertEquals(order, OnlineSource.move(order, order.first(), -1))
        assertEquals(order, OnlineSource.move(order, order.last(), 1))
    }
}
