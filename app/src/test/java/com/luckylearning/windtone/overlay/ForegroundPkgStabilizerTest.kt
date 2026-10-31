package com.luckylearning.windtone.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 前台包名滞回（v1.2.43）：钉死「闪一下不改判定、真停留才提交」。
 *
 * 用例直接对应车机日志：avc ↔ 地图/听风 短切时悬浮窗不应狂闪；
 * 真打开名单应用超过稳定窗口后仍须隐藏。
 */
class ForegroundPkgStabilizerTest {

    private var now = 0L
    private lateinit var stab: ForegroundPkgStabilizer

    @Before
    fun setUp() {
        now = 0L
        stab = ForegroundPkgStabilizer(stableMs = 450L, clock = { now })
    }

    @Test
    fun `首次上报未满窗口不提交`() {
        assertNull(stab.onRaw("com.map"))
        now = 449L
        assertNull(stab.tick())
    }

    @Test
    fun `稳定满窗口后提交`() {
        stab.onRaw("com.map")
        now = 450L
        assertEquals("com.map", stab.tick())
    }

    @Test
    fun `短时闪到名单包再回去不改稳定包`() {
        stab.onRaw("com.map")
        now = 450L
        assertEquals("com.map", stab.tick())

        // 闪一下 avc（隐藏名单常见包）再回地图
        now = 500L
        assertEquals("com.map", stab.onRaw("com.byd.avc"))
        now = 550L
        assertEquals("com.map", stab.onRaw("com.map"))
        now = 1000L
        assertEquals("com.map", stab.tick())
    }

    @Test
    fun `真切到名单包并停留后提交`() {
        stab.onRaw("com.map")
        now = 450L
        stab.tick()

        now = 500L
        assertEquals("com.map", stab.onRaw("com.byd.avc"))
        now = 949L
        assertEquals("com.map", stab.tick())
        now = 950L
        assertEquals("com.byd.avc", stab.tick())
    }

    @Test
    fun `从名单包短闪到普通应用再回去仍保持名单包`() {
        stab.onRaw("com.byd.avc")
        now = 450L
        assertEquals("com.byd.avc", stab.tick())

        now = 500L
        assertEquals("com.byd.avc", stab.onRaw("com.map"))
        now = 520L
        assertEquals("com.byd.avc", stab.onRaw("com.byd.avc"))
        now = 900L
        assertEquals("com.byd.avc", stab.tick())
    }

    @Test
    fun `gesture 与 sr 瞬切同样被滤掉`() {
        stab.onRaw("com.map")
        now = 450L
        stab.tick()

        now = 460L
        stab.onRaw("com.byd.gesture")
        now = 480L
        stab.onRaw("com.byd.sr")
        now = 500L
        assertEquals("com.map", stab.onRaw("com.map"))
        now = 1000L
        assertEquals("com.map", stab.tick())
    }

    @Test
    fun `空包名当作未知候选，满窗口后可清掉稳定包`() {
        stab.onRaw("com.map")
        now = 450L
        stab.tick()

        now = 500L
        assertEquals("com.map", stab.onRaw(null))
        now = 950L
        assertNull(stab.tick())
    }

    @Test
    fun `reset 清空状态`() {
        stab.onRaw("com.map")
        now = 450L
        stab.tick()
        stab.reset()
        assertNull(stab.stablePkg())
        assertNull(stab.onRaw("com.other"))
    }

    @Test
    fun `默认稳定窗口是 450ms`() {
        assertEquals(450L, ForegroundPkgStabilizer.STABLE_MS)
    }
}
