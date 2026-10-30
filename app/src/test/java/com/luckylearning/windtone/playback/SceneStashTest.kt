package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- 边界：切档还原基线 / 坏快照按无快照 / 负睡眠夹零 ----

    @Test
    fun `字段数过多或过少按无快照`() {
        val good = SceneStash.encode(sample())
        assertNull(SceneStash.decode(good + "\u001fextra"))
        val parts = good.split('\u001f')
        assertNull(SceneStash.decode(parts.dropLast(1).joinToString("\u001f")))
        assertNull(SceneStash.decode("   "))
        assertNull(SceneStash.decode("\t\n"))
    }

    @Test
    fun `数字字段坏了返回 null`() {
        val sep = '\u001f'
        fun pack(vararg f: String) = f.joinToString(sep.toString())
        // enabled / bass / virtual / loudness / speed / pitch / sleep
        assertNull(SceneStash.decode(pack("v1", "2", "p", "b", "0", "0", "0", "r", "s", "1.0", "0", "0")))
        assertNull(SceneStash.decode(pack("v1", "1", "p", "b", "x", "0", "0", "r", "s", "1.0", "0", "0")))
        assertNull(SceneStash.decode(pack("v1", "1", "p", "b", "0", "y", "0", "r", "s", "1.0", "0", "0")))
        assertNull(SceneStash.decode(pack("v1", "1", "p", "b", "0", "0", "z", "r", "s", "1.0", "0", "0")))
        assertNull(SceneStash.decode(pack("v1", "1", "p", "b", "0", "0", "0", "r", "s", "fast", "0", "0")))
        assertNull(SceneStash.decode(pack("v1", "1", "p", "b", "0", "0", "0", "r", "s", "1.0", "半", "0")))
        assertNull(SceneStash.decode(pack("v1", "1", "p", "b", "0", "0", "0", "r", "s", "1.0", "0", "ms")))
    }

    @Test
    fun `负睡眠剩余夹到零`() {
        val sep = '\u001f'
        val raw = listOf(
            "v1", "1", "flat", "0,0", "0", "0", "0", "off", "vocal", "1.0", "0", "-5",
        ).joinToString(sep.toString())
        val back = SceneStash.decode(raw)!!
        assertEquals(0L, back.sleepRemainingMs)
    }

    @Test
    fun `切档还原基线 编码不变可反复 decode`() {
        // 进第一档拍下的基线，档与档切换后仍应原样可还原
        val baseline = sample(
            enabled = false,
            presetId = "custom",
            bands = "-150,0,200",
            bass = 50,
            virtual = 0,
            loudness = -100,
            reverbId = "room_small",
            schemeId = "",
            speed = 0.8f,
            pitch = 3,
            sleep = 1_200_000L,
        )
        val raw = SceneStash.encode(baseline)
        repeat(3) {
            val back = SceneStash.decode(raw)!!
            assertFalse(back.enabled)
            assertEquals("custom", back.presetId)
            assertEquals("-150,0,200", back.bandsCsv)
            assertEquals(50, back.bass)
            assertEquals(0, back.virtual)
            assertEquals(-100, back.loudness)
            assertEquals("room_small", back.reverbId)
            assertEquals("", back.schemeId)
            assertEquals(0.8f, back.speed, 0.0001f)
            assertEquals(3, back.pitchSemitones)
            assertEquals(1_200_000L, back.sleepRemainingMs)
        }
        // 模拟「若误重拍」会得到不同串：同一 Snapshot 再 encode 应稳定相等
        assertEquals(raw, SceneStash.encode(baseline))
        assertEquals(raw, SceneStash.encode(SceneStash.decode(raw)!!))
    }

    @Test
    fun `极端速度与变调往返`() {
        val slow = SceneStash.decode(SceneStash.encode(sample(speed = 0.5f, pitch = -12, sleep = Long.MAX_VALUE)))!!
        assertEquals(0.5f, slow.speed, 0.0001f)
        assertEquals(-12, slow.pitchSemitones)
        assertEquals(Long.MAX_VALUE, slow.sleepRemainingMs)
        val fast = SceneStash.decode(SceneStash.encode(sample(speed = 2.0f, pitch = 12)))!!
        assertEquals(2.0f, fast.speed, 0.0001f)
        assertEquals(12, fast.pitchSemitones)
    }

    @Test
    fun `坏快照与好快照可区分 退出时按无快照保住现状`() {
        assertNull(SceneStash.decode("corrupt"))
        assertNull(SceneStash.decode("v2\u001f1\u001f\u001f\u001f0\u001f0\u001f0\u001f\u001f\u001f1.0\u001f0\u001f0"))
        val good = SceneStash.encode(sample())
        assertNotEquals(null, SceneStash.decode(good))
        // 截断版坏掉后不得误解析成部分字段
        assertNull(SceneStash.decode(good.take(10)))
    }
}
