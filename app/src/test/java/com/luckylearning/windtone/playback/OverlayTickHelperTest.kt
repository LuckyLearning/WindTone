package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayTickHelper] 的纯决策函数单测。
 *
 * 这两段节流逻辑原本埋在 PlaybackEngine 的 ticker 里，连点 / 系统杀服务等边界很难覆盖；
 * 抽成纯函数后，5 秒落库与 30 秒悬浮窗自愈的判定都能直接在 JVM 单测里钉死。
 */
class OverlayTickHelperTest {

    @Test
    fun `进度落库：pos 为 0 不落库`() {
        assertFalse(OverlayTickHelper.shouldPersistPosition(0L, lastSavedAt = 0L, now = 10_000L, intervalMs = 5_000L))
    }

    @Test
    fun `进度落库：未到 5 秒节流不落库`() {
        // 上次落库 0ms，现在 4 秒，还没到 5 秒
        assertFalse(OverlayTickHelper.shouldPersistPosition(1_000L, lastSavedAt = 0L, now = 4_000L, intervalMs = 5_000L))
    }

    @Test
    fun `进度落库：恰好到 5 秒边界即落库`() {
        assertTrue(OverlayTickHelper.shouldPersistPosition(1_000L, lastSavedAt = 0L, now = 5_000L, intervalMs = 5_000L))
    }

    @Test
    fun `进度落库：超过 5 秒落库`() {
        assertTrue(OverlayTickHelper.shouldPersistPosition(2_000L, lastSavedAt = 1_000L, now = 7_000L, intervalMs = 5_000L))
    }

    @Test
    fun `悬浮窗自愈：开关关闭不拉起`() {
        assertFalse(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = false, serviceAlive = false,
                lastReviveAt = 0L, now = 60_000L, intervalMs = 30_000L,
            ),
        )
    }

    @Test
    fun `悬浮窗自愈：服务还活着不拉起`() {
        assertFalse(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = true, serviceAlive = true,
                lastReviveAt = 0L, now = 60_000L, intervalMs = 30_000L,
            ),
        )
    }

    @Test
    fun `悬浮窗自愈：未到 30 秒节流不拉起`() {
        assertFalse(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = true, serviceAlive = false,
                lastReviveAt = 0L, now = 20_000L, intervalMs = 30_000L,
            ),
        )
    }

    @Test
    fun `悬浮窗自愈：开关开且服务死且过了 30 秒才拉起`() {
        assertTrue(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = true, serviceAlive = false,
                lastReviveAt = 0L, now = 30_000L, intervalMs = 30_000L,
            ),
        )
        assertTrue(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = true, serviceAlive = false,
                lastReviveAt = 1_000L, now = 35_000L, intervalMs = 30_000L,
            ),
        )
    }

    @Test
    fun `悬浮窗自愈：被系统杀后又重新拉起，节流从新时刻计时`() {
        // 第一次 30 秒拉起；若 now 在 31 秒不该立刻再拉（lastReviveAt 已被更新到 30 秒处）
        val firstReviveAt = 30_000L
        assertFalse(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = true, serviceAlive = false,
                lastReviveAt = firstReviveAt, now = firstReviveAt + 1_000L, intervalMs = 30_000L,
            ),
        )
        // 再等 30 秒（距上次拉起满 30 秒）才允许再次拉起
        assertTrue(
            OverlayTickHelper.shouldReviveOverlay(
                overlayEnabled = true, serviceAlive = false,
                lastReviveAt = firstReviveAt, now = firstReviveAt + 30_000L, intervalMs = 30_000L,
            ),
        )
    }
}
