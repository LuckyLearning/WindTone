package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FadeRamp] 的形状契约：线性、夹取、边界（0 与区间外）。
 *
 * 淡入淡出是用户直接听得到的曲线 —— 形状错了（比如在起点跳变）就是咔哒声。
 * 这里逐点钉死，谁改曲线先红这里。float 断言一律带 delta（JUnit4 的
 * float 重载对 delta 类型敏感，字面量统一写 float）。
 */
class FadeRampTest {

    @Test
    fun `淡出在区间外恒为 1`() {
        assertEquals(1f, FadeRamp.fadeOutFactor(remainingMs = 10_000, fadeMs = 2_000), 0f)
        assertEquals(1f, FadeRamp.fadeOutFactor(remainingMs = 2_000, fadeMs = 2_000), 0f)
    }

    @Test
    fun `淡出在区间内线性下降`() {
        assertEquals(0.5f, FadeRamp.fadeOutFactor(remainingMs = 1_000, fadeMs = 2_000), 1e-6f)
        assertEquals(0.25f, FadeRamp.fadeOutFactor(remainingMs = 500, fadeMs = 2_000), 1e-6f)
    }

    @Test
    fun `淡出终点与越界为 0`() {
        assertEquals(0f, FadeRamp.fadeOutFactor(remainingMs = 0, fadeMs = 2_000), 0f)
        assertEquals(0f, FadeRamp.fadeOutFactor(remainingMs = -100, fadeMs = 2_000), 0f)
    }

    @Test
    fun `淡入在区间内线性上升`() {
        assertEquals(0.5f, FadeRamp.fadeInFactor(elapsedMs = 1_000, fadeMs = 2_000), 1e-6f)
        assertEquals(0.75f, FadeRamp.fadeInFactor(elapsedMs = 1_500, fadeMs = 2_000), 1e-6f)
    }

    @Test
    fun `淡入起点为 0 终点后恒为 1`() {
        assertEquals(0f, FadeRamp.fadeInFactor(elapsedMs = 0, fadeMs = 2_000), 0f)
        assertEquals(1f, FadeRamp.fadeInFactor(elapsedMs = 2_000, fadeMs = 2_000), 0f)
        assertEquals(1f, FadeRamp.fadeInFactor(elapsedMs = 99_999, fadeMs = 2_000), 0f)
    }

    @Test
    fun `时长非法时恒为 1（等于禁用）`() {
        assertEquals(1f, FadeRamp.fadeOutFactor(remainingMs = 500, fadeMs = 0), 0f)
        assertEquals(1f, FadeRamp.fadeInFactor(elapsedMs = 0, fadeMs = 0), 0f)
        assertEquals(1f, FadeRamp.fadeOutFactor(remainingMs = 500, fadeMs = -1), 0f)
    }

    @Test
    fun `淡入淡出是同一条曲线关于接缝的镜像`() {
        // 距接缝偏移 t：淡出侧（曲尾还剩 t）与淡入侧（新曲已播 t）因子必须相等 ——
        // fadeOut(t) == fadeIn(t) == t / fadeMs。这样「淡出到底 → 切歌 → 从 0 淡入」
        // 拼起来是一条连续的 V 形，接缝处无咔哒。t=0 不进组：fadeOut 在
        // remaining==fadeMs 处是区间边界（=1，还没开始淡），属「区间外恒 1」语义。
        val fadeMs = 2_000L
        for (t in listOf(500L, 1_000L, 1_500L)) {
            assertEquals(
                "偏移 ${t}ms 处两条曲线不是镜像",
                FadeRamp.fadeOutFactor(t, fadeMs),
                FadeRamp.fadeInFactor(t, fadeMs),
                1e-6f,
            )
        }
        // 接缝本身：淡出走完是 0，淡入起步也是 0
        assertEquals(0f, FadeRamp.fadeOutFactor(0, fadeMs), 0f)
        assertEquals(0f, FadeRamp.fadeInFactor(0, fadeMs), 0f)
    }
}
