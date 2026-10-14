package com.luckylearning.windtone.state

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放模式（顺序 / 列表循环 / 单曲循环 / 随机）单元测试（纯 JVM）。
 *
 * 这 4 档现在共用播放页上的同一个按钮轮换，轮换顺序和推导规则出错会直接影响播放行为，
 * 所以单独覆盖：轮换链条闭合、随机优先于循环、由 Media3 两个开关正确反推档位。
 */
class PlayModeTest {

    @Test
    fun cycleOrderIsSequentialRepeatAllRepeatOneShuffle() {
        assertEquals(PlayMode.REPEAT_ALL, PlayMode.SEQUENTIAL.next)
        assertEquals(PlayMode.REPEAT_ONE, PlayMode.REPEAT_ALL.next)
        assertEquals(PlayMode.SHUFFLE, PlayMode.REPEAT_ONE.next)
        // 链条必须闭合，否则点 4 次回不到原点、按钮会“卡住”
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.SHUFFLE.next)
    }

    @Test
    fun shuffleWinsOverRepeat() {
        assertEquals(PlayMode.SHUFFLE, playModeOf(Player.REPEAT_MODE_ALL, shuffle = true))
        assertEquals(PlayMode.SHUFFLE, playModeOf(Player.REPEAT_MODE_ONE, shuffle = true))
        assertEquals(PlayMode.SHUFFLE, playModeOf(Player.REPEAT_MODE_OFF, shuffle = true))
    }

    @Test
    fun derivesPlayModeFromMedia3Switches() {
        assertEquals(PlayMode.SEQUENTIAL, playModeOf(Player.REPEAT_MODE_OFF, shuffle = false))
        assertEquals(PlayMode.REPEAT_ALL, playModeOf(Player.REPEAT_MODE_ALL, shuffle = false))
        assertEquals(PlayMode.REPEAT_ONE, playModeOf(Player.REPEAT_MODE_ONE, shuffle = false))
    }

    @Test
    fun everyModeHasLabel() {
        PlayMode.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
