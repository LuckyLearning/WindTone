package com.anylyric.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricOffsetStoreTest {

    @Test
    fun `key includes the player package`() {
        // 同一首歌在不同播放器上的进度偏差不同，键必须分开。
        val a = LyricOffsetStore.keyOf("com.kugou", "稻香", "周杰伦")
        val b = LyricOffsetStore.keyOf("com.tencent.qqmusic", "稻香", "周杰伦")
        assertNotEquals(a, b)
    }

    @Test
    fun `key normalizes title and artist`() {
        // 「稻香 - 周杰伦」与「稻香」是同一首，不该各记一条偏移。
        val a = LyricOffsetStore.keyOf("pkg", "稻香 - 周杰伦", "周杰伦")
        val b = LyricOffsetStore.keyOf("PKG", "稻香", "周杰伦")
        assertEquals(a, b)
    }

    @Test
    fun `key is empty when there is no title`() {
        assertEquals("", LyricOffsetStore.keyOf("pkg", "   ", "artist"))
        assertEquals("", LyricOffsetStore.keyOf("pkg", null, null))
    }

    @Test
    fun `clamp bounds the value and snaps to ten milliseconds`() {
        assertEquals(3000, LyricOffsetStore.clamp(9999))
        assertEquals(-3000, LyricOffsetStore.clamp(-9999))
        assertEquals(130, LyricOffsetStore.clamp(137))
        assertEquals(-130, LyricOffsetStore.clamp(-137))
        assertEquals(0, LyricOffsetStore.clamp(4))
    }

    @Test
    fun `put and decode round trip`() {
        var raw = LyricOffsetStore.put(null, "k1", 200)
        raw = LyricOffsetStore.put(raw, "k2", -400)
        val map = LyricOffsetStore.decode(raw)
        assertEquals(2, map.size)
        assertEquals(200, map["k1"])
        assertEquals(-400, map["k2"])
    }

    @Test
    fun `updating a key moves it to the end`() {
        var raw = LyricOffsetStore.put(null, "k1", 100, maxEntries = 10)
        raw = LyricOffsetStore.put(raw, "k2", 100, maxEntries = 10)
        raw = LyricOffsetStore.put(raw, "k1", 300, maxEntries = 10)
        val keys = LyricOffsetStore.decode(raw).keys.toList()
        assertEquals(listOf("k2", "k1"), keys)
    }

    @Test
    fun `put evicts the oldest entry over capacity`() {
        var raw = ""
        for (i in 1..5) raw = LyricOffsetStore.put(raw, "k$i", 100, maxEntries = 3)
        val map = LyricOffsetStore.decode(raw)
        assertEquals(3, map.size)
        assertFalse(map.containsKey("k1"))
        assertFalse(map.containsKey("k2"))
        assertTrue(map.containsKey("k5"))
    }

    @Test
    fun `remove drops only the named key`() {
        var raw = LyricOffsetStore.put(null, "k1", 100)
        raw = LyricOffsetStore.put(raw, "k2", 200)
        raw = LyricOffsetStore.remove(raw, "k1")
        val map = LyricOffsetStore.decode(raw)
        assertFalse(map.containsKey("k1"))
        assertEquals(200, map["k2"])
    }

    @Test
    fun `label shows sign and one decimal`() {
        assertEquals("0.0s", LyricOffsetStore.label(0))
        assertEquals("+0.4s", LyricOffsetStore.label(400))
        assertEquals("-0.2s", LyricOffsetStore.label(-200))
        assertEquals("+1.0s", LyricOffsetStore.label(1000))
        assertEquals("-3.0s", LyricOffsetStore.label(-3000))
    }

    @Test
    fun `decode tolerates junk and null`() {
        assertTrue(LyricOffsetStore.decode(null).isEmpty())
        assertTrue(LyricOffsetStore.decode("").isEmpty())
        assertTrue(LyricOffsetStore.decode("garbage-without-separators").isEmpty())
    }

    @Test
    fun `zero value is kept rather than dropped`() {
        // 「调回 0」与「从没调过」在语义上不同：前者是用户明确选择跟随默认之外的一次归零动作。
        val raw = LyricOffsetStore.put(null, "k1", 0)
        assertTrue(LyricOffsetStore.decode(raw).containsKey("k1"))
    }
}
