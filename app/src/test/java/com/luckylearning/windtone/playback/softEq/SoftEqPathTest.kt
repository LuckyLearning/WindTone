package com.luckylearning.windtone.playback.softEq

import com.luckylearning.windtone.playback.AudioFx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E1：软 EQ 主路径门面可开关；DEVICE_REJECTED 挂钩不自动启用（Spike 闸关）。 */
class SoftEqPathTest {

    @Before
    fun setUp() {
        SoftEqPath.resetForTest()
    }

    @After
    fun tearDown() {
        SoftEqPath.resetForTest()
    }

    @Test
    fun `默认关闭`() {
        assertFalse(SoftEqPath.enabled)
        assertEquals(AudioFx.Unavailable.NONE, SoftEqPath.state.value.lastHardUnavailable)
        assertFalse(SoftEqSpike.ENABLED)
    }

    @Test
    fun `setEnabled 可开可关`() {
        SoftEqPath.setEnabled(true)
        assertTrue(SoftEqPath.enabled)
        SoftEqPath.setEnabled(false)
        assertFalse(SoftEqPath.enabled)
    }

    @Test
    fun `DEVICE_REJECTED 挂钩记录原因但不自动启用`() {
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.DEVICE_REJECTED)
        assertEquals(
            AudioFx.Unavailable.DEVICE_REJECTED,
            SoftEqPath.state.value.lastHardUnavailable,
        )
        // SoftEqSpike.ENABLED == false → E1 不自动开
        assertFalse(SoftEqPath.enabled)
    }

    @Test
    fun `SESSION_PENDING 挂钩不启用`() {
        SoftEqPath.onHardEqUnavailable(AudioFx.Unavailable.SESSION_PENDING)
        assertEquals(
            AudioFx.Unavailable.SESSION_PENDING,
            SoftEqPath.state.value.lastHardUnavailable,
        )
        assertFalse(SoftEqPath.enabled)
    }

    @Test
    fun `processor 单例稳定`() {
        val a = SoftEqPath.processor
        val b = SoftEqPath.processor
        assertTrue(a === b)
    }
}
