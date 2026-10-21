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

    // ---------------------------------------------------------------- 全屏高度

    @Test
    fun `全屏高度铺满屏宽并让开车机 Dock`() {
        // 1920×720 车机屏、Dock 让位 56dp@240dpi = 84px
        val f = OverlayPositioner.fullFrame(screenW = 1920, screenH = 720, bottomReserved = 84)
        assertEquals(1920, f.width)
        assertEquals(636, f.height)
    }

    @Test
    fun `没有 Dock 时全屏高度等于屏高`() {
        val f = OverlayPositioner.fullFrame(screenW = 1080, screenH = 2400, bottomReserved = 0)
        assertEquals(1080, f.width)
        assertEquals(2400, f.height)
    }

    @Test
    fun `让位不超过半屏`() {
        // dpi 改得很大 / 屏幕很矮时，让位若照单全收会把窗口压成一条缝。
        // 半屏是上限：再离谱也还留得下一半高度给歌词。
        val f = OverlayPositioner.fullFrame(screenW = 800, screenH = 400, bottomReserved = 1000)
        assertEquals(200, f.height)
    }

    @Test
    fun `高度至少一个像素`() {
        // height = 0 在部分 ROM 上等于「不显示」，而本模式的失败表现正是
        // 「开关开了、屏幕上什么都没有」——这种静默失败必须从几何上排除。
        val f = OverlayPositioner.fullFrame(screenW = 0, screenH = 0, bottomReserved = 0)
        assertEquals(1, f.width)
        assertEquals(1, f.height)
    }

    // ------------------------------------------------ 全屏宽度与左右移动（v1.1.20）

    @Test
    fun `全屏宽度按屏宽百分比缩窄`() {
        val f = OverlayPositioner.fullFrame(
            screenW = 1920,
            screenH = 720,
            bottomReserved = 0,
            widthPercent = 60,
            offsetPercent = 50,
        )
        assertEquals((1920 * 0.6f).toInt(), f.width)
        // 余量 1920 − 1152 = 768，居中 = 384
        assertEquals(384, f.x)
        assertEquals(0, f.y)
    }

    @Test
    fun `全屏靠左贴左沿`() {
        val f = OverlayPositioner.fullFrame(
            screenW = 1920, screenH = 720, bottomReserved = 0,
            widthPercent = 60, offsetPercent = 0,
        )
        assertEquals(0, f.x)
    }

    @Test
    fun `全屏靠右贴右沿且不越过屏幕`() {
        val f = OverlayPositioner.fullFrame(
            screenW = 1920, screenH = 720, bottomReserved = 0,
            widthPercent = 60, offsetPercent = 100,
        )
        assertEquals(1920 - f.width, f.x)
        assertEquals(1920, f.x + f.width)
    }

    @Test
    fun `全屏满宽时左右没有余量`() {
        // 与单条字幕 100% 宽时「只能上下拖」是同一个几何事实：
        // 此时滑块怎么调 x 都是 0，设置页必须把这件事说清楚，别再当 bug 排查。
        val f = OverlayPositioner.fullFrame(
            screenW = 1080, screenH = 2400, bottomReserved = 0,
            widthPercent = 100, offsetPercent = 100,
        )
        assertEquals(1080, f.width)
        assertEquals(0, f.x)
    }

    @Test
    fun `全屏宽度过窄时按下限夹取`() {
        // 10% 在 1080p 上只有 108px，11 行歌词会挤成一条竖线；下限 30%。
        val f = OverlayPositioner.fullFrame(
            screenW = 1000, screenH = 800, bottomReserved = 0,
            widthPercent = 10, offsetPercent = 50,
        )
        assertEquals(300, f.width)
        // 余量 700，居中 350
        assertEquals(350, f.x)
    }

    @Test
    fun `水平位置超界时夹到两端`() {
        val left = OverlayPositioner.fullFrame(
            screenW = 1000, screenH = 800, bottomReserved = 0,
            widthPercent = 50, offsetPercent = -40,
        )
        assertEquals(0, left.x)
        val right = OverlayPositioner.fullFrame(
            screenW = 1000, screenH = 800, bottomReserved = 0,
            widthPercent = 50, offsetPercent = 300,
        )
        assertEquals(500, right.x)
    }

    @Test
    fun `水平位置的文案把两端与中间说成人话`() {
        assertEquals("靠左", OverlayPositioner.offsetLabel(0))
        assertEquals("靠左", OverlayPositioner.offsetLabel(2))
        assertEquals("居中", OverlayPositioner.offsetLabel(50))
        assertEquals("靠右", OverlayPositioner.offsetLabel(100))
        assertEquals("偏左 30%", OverlayPositioner.offsetLabel(30))
        assertEquals("偏右 75%", OverlayPositioner.offsetLabel(75))
    }

    @Test
    fun `拖拽坐标逆运算回余量百分比，与 fullFrame 互逆`() {
        // 屏宽 1000、窗宽 500 → 余量 500；x=250 即居中 50%
        assertEquals(50, OverlayPositioner.offsetPercentFromX(250, 500, 0..100))
        // x=0 即最左 0%
        assertEquals(0, OverlayPositioner.offsetPercentFromX(0, 500, 0..100))
        // x=500 即最右 100%
        assertEquals(100, OverlayPositioner.offsetPercentFromX(500, 500, 0..100))
    }

    @Test
    fun `拖拽逆运算越界夹取到范围两端`() {
        assertEquals(0, OverlayPositioner.offsetPercentFromX(-999, 500, 0..100))
        assertEquals(100, OverlayPositioner.offsetPercentFromX(9999, 500, 0..100))
    }

    @Test
    fun `满宽不可横向拖动时返回范围起点，保持原偏好`() {
        // 余量 0（窗口宽度 = 屏宽）时 x 恒 0，不应把靠右偏好抹成居中
        assertEquals(0, OverlayPositioner.offsetPercentFromX(0, 0, 0..100))
    }
}
