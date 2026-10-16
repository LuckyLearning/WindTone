package com.luckylearning.windtone.overlay

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮歌词窗口摆放的边界测试。
 *
 * 这一块的两次真机事故都是「窗口跑到屏幕外 → 自检全绿但桌面上什么都没有」，
 * 所以重点钉死：**夹取必须扣掉窗口自身尺寸**，以及**未拖过时横向要落在正中**。
 * 2026-10-16 起窗口位置完全由拖动决定（横纵两轴），对齐设置只管窗内文字排版。
 */
class OverlayPositionerTest {

    /** 典型车机屏：1920×720，窗口 600×90 */
    private fun params(
        savedX: Int = OverlayPositioner.UNSET,
        savedY: Int = OverlayPositioner.UNSET,
        screenW: Int = 1920,
        screenH: Int = 720,
        windowW: Int = 600,
        windowH: Int = 90,
        defaultY: Int = 88,
    ) = OverlayPositioner.Params(
        savedX = savedX,
        savedY = savedY,
        screenW = screenW,
        screenH = screenH,
        windowW = windowW,
        windowH = windowH,
        defaultY = defaultY,
    )

    // ---------------------------------------------------------------- 横向

    @Test
    fun `未拖过时横向水平居中`() {
        // (1920 − 600) / 2 = 660。必须用「(屏宽 − 窗宽)/2」算默认值，
        // 而不是 gravity 的 CENTER_HORIZONTAL —— 后者的 x = 0 是「相对屏幕中心的
        // 偏移」，与拖动坐标系（x = 距左沿像素）不能共存，拖一下就会跳半屏。
        val p = OverlayPositioner.place(params())
        assertEquals(Gravity.TOP or Gravity.START, p.gravity)
        assertEquals(660, p.x)
    }

    @Test
    fun `拖过的横向坐标原样沿用`() {
        val p = OverlayPositioner.place(params(savedX = 123))
        assertEquals(123, p.x)
    }

    @Test
    fun `横向超出右沿时夹到 屏幕宽 减 窗口宽`() {
        // 只按屏幕宽度夹的话 x = 1920 会让窗口整个落在屏幕右侧之外，
        // 用户看到的就是「什么都没有」而自检全绿 —— 这是踩过的坑。
        val p = OverlayPositioner.place(params(savedX = 100_000))
        assertEquals(1920 - 600, p.x)
    }

    @Test
    fun `横向负值时夹到 0`() {
        val p = OverlayPositioner.place(params(savedX = -500))
        assertEquals(0, p.x)
    }

    @Test
    fun `窗口比屏幕还宽时横向归 0 而不是负数`() {
        val p = OverlayPositioner.place(params(savedX = 300, screenW = 500, windowW = 600))
        assertEquals(0, p.x)
    }

    @Test
    fun `窗口宽度等于屏幕宽度时横向锁死在 0`() {
        // 用户 2026-10-16 报「悬浮窗只能上下移动」的直接原因，值得单独钉一条：
        // 悬浮窗宽度默认曾是 100% → 窗口宽 = 屏宽 → 夹取上限 = 屏宽 − 窗宽 = 0
        // → 拖动的横向接线哪怕完全正确，x 也永远只能是 0。
        // 所以「宽度」这个设置与「能拖多远」是同一件事的两面，
        // SettingsDefaults.OVERLAY_WIDTH_PERCENT 默认走 80%。
        val p = OverlayPositioner.place(params(savedX = 400, screenW = 1080, windowW = 1080))
        assertEquals(0, p.x)
    }

    // ---------------------------------------------------------------- 纵向

    @Test
    fun `未拖过时用默认高度`() {
        val p = OverlayPositioner.place(params(defaultY = 88))
        assertEquals(88, p.y)
    }

    @Test
    fun `拖过时沿用拖动坐标`() {
        val p = OverlayPositioner.place(params(savedY = 300))
        assertEquals(300, p.y)
    }

    @Test
    fun `纵向超出底部时夹到 屏幕高 减 窗口高`() {
        val p = OverlayPositioner.place(params(savedY = 100_000))
        assertEquals(720 - 90, p.y)
    }

    @Test
    fun `纵向负值时夹到 0`() {
        val p = OverlayPositioner.place(params(savedY = -500))
        assertEquals(0, p.y)
    }

    @Test
    fun `窗口比屏幕还高时纵向归 0 而不是负数`() {
        val p = OverlayPositioner.place(params(savedY = 300, screenH = 100, windowH = 400))
        assertEquals(0, p.y)
    }

    @Test
    fun `默认高度本身也不会被推到屏幕外`() {
        // 状态栏很高 + 窗口很高时，默认值也应被夹住，而不是无条件使用。
        val p = OverlayPositioner.place(params(defaultY = 700, screenH = 720, windowH = 300))
        assertEquals(720 - 300, p.y)
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `gravity 恒为 TOP 加 START`() {
        // 位置完全由 x/y 决定，gravity 里不再带任何对齐位 ——
        // CENTER_HORIZONTAL 的 x 语义（相对屏幕中心的偏移）与拖动坐标系冲突。
        val p = OverlayPositioner.place(params(savedX = 10, savedY = 20))
        assertEquals(Gravity.TOP or Gravity.START, p.gravity)
        assertEquals(10, p.x)
        assertEquals(20, p.y)
    }

    @Test
    fun `窗口尚未测量时不夹取`() {
        // 挂载瞬间 window 宽高都是 0。此时 maxX/maxY 退化成屏幕尺寸，夹取不生效——
        // 这正是想要的：此刻夹取没有意义，布局回调里还会再摆一次。
        val p = OverlayPositioner.place(params(windowW = 0, windowH = 0, savedX = 50, savedY = 50))
        assertEquals(50, p.x)
        assertEquals(50, p.y)
    }

    @Test
    fun `UNSET_Y 旧常量与 UNSET 等值`() {
        assertEquals(OverlayPositioner.UNSET, OverlayPositioner.UNSET_Y)
    }
}
