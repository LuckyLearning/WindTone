package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPrefsPolicyTest {

    @Test
    fun retainAndCap_dropsKeysNotInKeep() {
        val map = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
        assertTrue(MetadataPrefsPolicy.retainAndCap(map, setOf("a", "c")))
        assertEquals(mapOf("a" to 1, "c" to 3), map)
    }

    @Test
    fun retainAndCap_noopWhenAlreadyAligned() {
        val map = linkedMapOf("a" to 1)
        assertFalse(MetadataPrefsPolicy.retainAndCap(map, setOf("a", "b")))
        assertEquals(mapOf("a" to 1), map)
    }

    @Test
    fun retainAndCap_clearsWhenKeepEmpty() {
        val map = linkedMapOf("a" to 1, "b" to 2)
        assertTrue(MetadataPrefsPolicy.retainAndCap(map, emptySet()))
        assertTrue(map.isEmpty())
    }

    @Test
    fun retainAndCap_hardCapsAboveMaxEntries() {
        val n = MetadataPrefsPolicy.MAX_ENTRIES + 3
        val map = (1..n).associateTo(linkedMapOf()) { i ->
            "k%05d".format(i) to i
        }
        val keep = map.keys.toSet()
        assertTrue(MetadataPrefsPolicy.retainAndCap(map, keep))
        assertEquals(MetadataPrefsPolicy.MAX_ENTRIES, map.size)
        // 字典序截断：丢掉最后若干键
        assertFalse(map.containsKey("k%05d".format(n)))
        assertTrue(map.containsKey("k%05d".format(1)))
    }
}
