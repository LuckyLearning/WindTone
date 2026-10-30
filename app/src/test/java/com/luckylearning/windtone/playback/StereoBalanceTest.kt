package com.luckylearning.windtone.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #9 左右声道平衡纯函数 */
class StereoBalanceTest {

    @Test
    fun `居中两侧增益都是 1`() {
        assertEquals(1f, StereoBalance.leftGain(0))
        assertEquals(1f, StereoBalance.rightGain(0))
        assertTrue(StereoBalance.isCentered(0))
        assertEquals("居中", StereoBalance.valueText(0))
    }

    @Test
    fun `全左压掉右声道`() {
        assertEquals(1f, StereoBalance.leftGain(-100), 0f)
        assertEquals(0f, StereoBalance.rightGain(-100), 0f)
        assertEquals("偏左 100", StereoBalance.valueText(-100))
        assertFalse(StereoBalance.isCentered(-100))
    }

    @Test
    fun `全右压掉左声道`() {
        assertEquals(0f, StereoBalance.leftGain(100), 0f)
        assertEquals(1f, StereoBalance.rightGain(100), 0f)
        assertEquals("偏右 100", StereoBalance.valueText(100))
    }

    @Test
    fun `半偏右线性插值`() {
        assertEquals(0.5f, StereoBalance.leftGain(50), 1e-5f)
        assertEquals(1f, StereoBalance.rightGain(50), 1e-5f)
        assertEquals(1f, StereoBalance.leftGain(-50), 1e-5f)
        assertEquals(0.5f, StereoBalance.rightGain(-50), 1e-5f)
    }

    @Test
    fun `越界夹到正负 100`() {
        assertEquals(-100, StereoBalance.coerce(-999))
        assertEquals(100, StereoBalance.coerce(999))
        assertEquals(1f, StereoBalance.leftGain(-999), 0f)
        assertEquals(0f, StereoBalance.rightGain(-999), 0f)
    }

    @Test
    fun `混音系数是对角缩放`() {
        val c = StereoBalance.mixingCoefficients(40)
        assertArrayEquals(floatArrayOf(0.6f, 0f, 0f, 1f), c, 1e-5f)
    }
    @Test
    fun `近中心正负1与0`() {
        assertEquals(0.99f, StereoBalance.leftGain(1), 1e-5f)
        assertEquals(1f, StereoBalance.rightGain(1), 1e-5f)
        assertEquals(1f, StereoBalance.leftGain(-1), 1e-5f)
        assertEquals(0.99f, StereoBalance.rightGain(-1), 1e-5f)
        assertFalse(StereoBalance.isCentered(1))
        assertFalse(StereoBalance.isCentered(-1))
        assertTrue(StereoBalance.isCentered(0))
        assertEquals("偏右 1", StereoBalance.valueText(1))
        assertEquals("偏左 1", StereoBalance.valueText(-1))
        assertEquals("居中", StereoBalance.valueText(0))
    }

    @Test
    fun `混音系数居中与正负极限`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), StereoBalance.mixingCoefficients(0), 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), StereoBalance.mixingCoefficients(-100), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), StereoBalance.mixingCoefficients(100), 0f)
        assertArrayEquals(
            floatArrayOf(0.99f, 0f, 0f, 1f),
            StereoBalance.mixingCoefficients(1),
            1e-5f,
        )
    }
}
