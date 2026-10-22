package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SoundStash] 编解码的往返与容错。
 *
 * 快照承载的是「用户手机侧的音效值」，decode 出错时的语义是「没快照」
 * （调用方保住现状），所以坏格式必须返回 null 而不是抛异常 ——
 * 这里把每种坏法都钉住。
 */
class SoundStashTest {

    @Test
    fun `往返编码解码一致`() {
        val stash = SoundStash.encode(
            enabled = true, presetId = "bass_boost", bandsCsv = "300,200,-100,50,0",
            bass = 700, virtual = 150, loudness = 300, reverbId = "hall_medium",
        )
        val snap = SoundStash.decode(stash)!!
        assertTrue(snap.enabled)
        assertEquals("bass_boost", snap.presetId)
        assertEquals("300,200,-100,50,0", snap.bandsCsv)
        assertEquals(700, snap.bass)
        assertEquals(150, snap.virtual)
        assertEquals(300, snap.loudness)
        assertEquals("hall_medium", snap.reverbId)
    }

    @Test
    fun `空 id 与空频段也能往返`() {
        val stash = SoundStash.encode(false, "", "", 0, 0, 0, "")
        val snap = SoundStash.decode(stash)!!
        assertFalse(snap.enabled)
        assertEquals("", snap.presetId)
        assertEquals("", snap.bandsCsv)
        assertEquals(0, snap.bass)
        assertEquals("", snap.reverbId)
    }

    @Test
    fun `负增益往返`() {
        val stash = SoundStash.encode(true, "", "", 0, 0, -800, "off")
        assertEquals(-800, SoundStash.decode(stash)!!.loudness)
    }

    @Test
    fun `字段数不对返回 null`() {
        assertNull(SoundStash.decode(""))
        assertNull(SoundStash.decode("1|flat"))
        assertNull(SoundStash.decode("1|flat||0|0|0|off|多余"))
    }

    @Test
    fun `enabled 位不是 0 或 1 返回 null`() {
        assertNull(SoundStash.decode("true|flat||0|0|0|off"))
        assertNull(SoundStash.decode("2|flat||0|0|0|off"))
    }

    @Test
    fun `数字字段坏了返回 null`() {
        assertNull(SoundStash.decode("1|flat||abc|0|0|off"))
        assertNull(SoundStash.decode("1|flat||0|0|0.5|off"))
    }
}
