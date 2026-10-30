package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 高音快捷键和频段滑块是同一条曲线；低音快捷键只走 BassBoost 的步进。 */
class EqToneTest {

    private val five = listOf(60, 230, 910, 3600, 14000)

    @Test
    fun `五段机高音只动 4 kHz 以上`() {
        val levels = intArrayOf(100, 200, 0, -50, 0)
        val next = EqTone.applyTreble(levels, five, 450, -1500, 1500)
        assertEquals(100, next[0])
        assertEquals(200, next[1])
        assertEquals(0, next[2])
        assertEquals(-50, next[3])
        assertEquals(450, next[4])
        assertEquals(450, EqTone.readTrebleMb(next, five))
    }

    @Test
    fun `多段高频收成同一搁架，读数是平均值`() {
        val centers = listOf(1000, 4000, 8000, 16000)
        val levels = intArrayOf(20, 200, 400, 600)
        assertEquals(400, EqTone.readTrebleMb(levels, centers))
        val shelved = EqTone.applyTreble(levels, centers, 300, -1500, 1500)
        assertEquals(20, shelved[0])
        assertEquals(300, shelved[1])
        assertEquals(300, shelved[2])
        assertEquals(300, shelved[3])
        assertEquals(300, EqTone.readTrebleMb(shelved, centers))
    }

    @Test
    fun `没有 4 kHz 段时退到最后一段`() {
        val centers = listOf(100, 400, 1000)
        val next = EqTone.applyTreble(intArrayOf(1, 2, 3), centers, -200, -1500, 1500)
        assertEquals(1, next[0])
        assertEquals(2, next[1])
        assertEquals(-200, next[2])
    }

    @Test
    fun `低音步进夹在 BassBoost 量程里`() {
        assertEquals(400, EqTone.stepBass(300, EqTone.BASS_STEP))
        assertEquals(0, EqTone.stepBass(50, -EqTone.BASS_STEP))
        assertEquals(1000, EqTone.stepBass(950, EqTone.BASS_STEP))
    }

    @Test
    fun `高音步进夹在设备量程里`() {
        assertEquals(150, EqTone.stepTreble(0, EqTone.TREBLE_STEP_MB, -1500, 1500))
        assertEquals(1500, EqTone.stepTreble(1400, EqTone.TREBLE_STEP_MB, -1500, 1500))
        assertEquals(-1500, EqTone.stepTreble(-1400, -EqTone.TREBLE_STEP_MB, -1500, 1500))
    }
}
