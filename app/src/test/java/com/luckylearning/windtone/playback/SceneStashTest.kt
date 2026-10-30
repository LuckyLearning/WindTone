package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SceneStash] 编解码往返与容错。
 */
class SceneStashTest {

    private fun sample(
        enabled: Boolean = true,
        presetId: String = "bass_boost",
        bands: String = "100,0,-50",
        bass: Int = 400,
        virtual: Int = 100,
        loudness: Int = 200,
        reverbId: String = "off",
        schemeId: String = "vocal",
        speed: Float = 1.25f,
        pitch: Int = -2,
        sleep: Long = 90_000L,
    ) = SceneStash.Snapshot(
        enabled, presetId, bands, bass, virtual, loudness, reverbId, schemeId, speed, pitch, sleep,
    )

    @Test
    fun `往返一致`() {
        val snap = sample()
        val back = SceneStash.decode(SceneStash.encode(snap))!!
        assertTrue(back.enabled)
        assertEquals("bass_boost", back.presetId)
        assertEquals("100,0,-50", back.bandsCsv)
        assertEquals(400, back.bass)
        assertEquals(100, back.virtual)
        assertEquals(200, back.loudness)
        assertEquals("off", back.reverbId)
        assertEquals("vocal", back.schemeId)
        assertEquals(1.25f, back.speed, 0.0001f)
        assertEquals(-2, back.pitchSemitones)
        assertEquals(90_000L, back.sleepRemainingMs)
    }

    @Test
    fun `空 id 与零睡眠也能往返`() {
        val snap = sample(
            enabled = false, presetId = "", bands = "", bass = 0, virtual = 0,
            loudness = 0, reverbId = "", schemeId = "", speed = 1.0f, pitch = 0, sleep = 0L,
        )
        val back = SceneStash.decode(SceneStash.encode(snap))!!
        assertFalse(back.enabled)
        assertEquals("", back.presetId)
        assertEquals(0L, back.sleepRemainingMs)
    }

    @Test
    fun `用户预设 id 能进快照`() {
        val id = "u_m5k_7"
        val back = SceneStash.decode(SceneStash.encode(sample(presetId = id)))!!
        assertEquals(id, back.presetId)
    }

    @Test
    fun `坏格式返回 null`() {
        assertNull(SceneStash.decode(""))
        assertNull(SceneStash.decode("v1"))
        assertNull(SceneStash.decode("v0\u001f1\u001f\u001f\u001f0\u001f0\u001f0\u001f\u001f\u001f1.0\u001f0\u001f0"))
        assertNull(SceneStash.decode("v1\u001ftrue\u001f\u001f\u001f0\u001f0\u001f0\u001f\u001f\u001f1.0\u001f0\u001f0"))
    }
}
