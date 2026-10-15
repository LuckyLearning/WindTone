package com.luckylearning.windtone.overlay

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 悬浮歌词窗口摆放的边界测试。
 *
 * 这一块的两次真机事故都是「窗口跑到屏幕外 → 自检全绿但桌面上什么都没有」，
 * 所以重点钉死：**夹取必须扣掉窗口自身尺寸**，以及**居左 / 居中两档的横向来源唯一**。
 */
class OverlayPositionerTest {

    /** 典型车机屏：1920×720，窗口 600×90 */
    private fun params(
        align: Int = OverlayPositioner.ALIGN_CENTER,
        savedY: Int = OverlayPositioner.UNSET_Y,
        screenW: Int = 1920,
        screenH: Int = 720,
        windowW: Int = 600,
        windowH: Int = 90,
        defaultY: Int = 88,
        leftMargin: Int = 24,
    ) = OverlayPositioner.Params(
        align = align,
        savedY = savedY,
        screenW = screenW,
        screenH = screenH,
        windowW = windowW,
        windowH = windowH,
        defaultY = defaultY,
        leftMargin = leftMargin,
    )

    // ---------------------------------------------------------------- 居中

    @Test
    fun `居中用 CENTER_HORIZONTAL 且 x 归零`() {
        val p = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_CENTER))
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, p.gravity)
        // x 是「相对屏幕中心的偏移」，必须归 0 才是真居中；
        // 早期版本注释写居中、实际用 TOP|START + x=0，视觉上贴在左上角。
        assertEquals(0, p.x)
    }

    @Test
    fun `居中时窗口宽度不参与横向计算`() {
        // 窗口很宽也不影响：居中由 gravity 算，不靠 x。
        val narrow = OverlayPositioner.place(params(align = 0, windowW = 10))
        val wide = OverlayPositioner.place(params(align = 0, windowW = 1900))
        assertEquals(narrow.x, wide.x)
        assertEquals(0, wide.x)
    }

    @Test
    fun `未知对齐值按居中处理`() {
        // 防旧数据 / 手改 Prefs：越界值不能变成「居左」这种用户没选过的状态。
        val p = OverlayPositioner.place(params(align = 99))
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, p.gravity)
        assertEquals(0, p.x)
    }

    // ---------------------------------------------------------------- 居左

    @Test
    fun `居左用 START 且 x 等于左边距`() {
        val p = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_LEFT))
        assertEquals(Gravity.TOP or Gravity.START, p.gravity)
        assertEquals(24, p.x)
    }

    @Test
    fun `居左时左边距被窗口宽度夹住`() {
        // 窗口比屏幕还宽（大字号 + 窄屏极端组合）：maxX 归 0，不能出现负的 x。
        val p = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_LEFT, screenW = 500, windowW = 600, leftMargin = 24))
        assertEquals(0, p.x)
    }

    // ---------------------------------------------------------------- 纵向

    @Test
    fun `未拖过时用默认高度`() {
        val p = OverlayPositioner.place(params(savedY = OverlayPositioner.UNSET_Y, defaultY = 88))
        assertEquals(88, p.y)
    }

    @Test
    fun `拖过时沿用拖动坐标`() {
        val p = OverlayPositioner.place(params(savedY = 300, defaultY = 88))
        assertEquals(300, p.y)
    }

    @Test
    fun `纵向超出底部时夹到 屏幕高 减 窗口高`() {
        // 只按屏幕高度夹的话 y = 720 会让窗口整个落在屏幕下方之外，
        // 用户看到的就是「什么都没有」而自检全绿 —— 这是踩过的坑。
        val p = OverlayPositioner.place(params(savedY = 100000))
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

    // ---------------------------------------------------------------- 未测量

    @Test
    fun `窗口尚未测量时不夹取`() {
        // 挂载瞬间 window 宽高都是 0。此时 maxX/maxY 退化成屏幕尺寸，夹取不生效——
        // 这正是想要的：此刻夹取没有意义，布局回调里还会再摆一次。
        val p = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_LEFT, windowW = 0, windowH = 0, savedY = 50))
        assertEquals(24, p.x)
        assertEquals(50, p.y)
    }

    @Test
    fun `默认高度本身也不会被推到屏幕外`() {
        // 状态栏很高 + 窗口很高时，默认值也应被夹住，而不是无条件使用。
        val p = OverlayPositioner.place(params(defaultY = 700, screenH = 720, windowH = 300))
        assertEquals(720 - 300, p.y)
    }

    @Test
    fun `对齐方式不影响纵向`() {
        // 横向归设置、纵向归拖动 —— 两档下同一个 savedY 必须得到同一个 y。
        val left = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_LEFT, savedY = 250))
        val center = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_CENTER, savedY = 250))
        assertEquals(left.y, center.y)
        assertEquals(250, left.y)
    }

    @Test
    fun `两档对齐的 gravity 水平方向不同`() {
        val left = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_LEFT)).gravity
        val center = OverlayPositioner.place(params(align = OverlayPositioner.ALIGN_CENTER)).gravity
        // 注意：HORIZONTAL_GRAVITY_MASK 会连同 RELATIVE_LAYOUT_DIRECTION 位一起剥掉，
        // 所以 START 掩完正好等于 LEFT(=3)。要确认用的真是 START（而不是 LEFT），
        // 得单独看那个相对布局位 —— 否则阿拉伯语等 RTL 环境下「居左」会变成居右。
        assertEquals(Gravity.LEFT, left and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(Gravity.CENTER_HORIZONTAL, center and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(Gravity.RELATIVE_LAYOUT_DIRECTION, left and Gravity.RELATIVE_LAYOUT_DIRECTION)
        assertEquals(0, center and Gravity.RELATIVE_LAYOUT_DIRECTION)
        // 纵向都贴顶：位置只由 y 决定，不指望 gravity
        assertEquals(Gravity.TOP, left and Gravity.VERTICAL_GRAVITY_MASK)
        assertEquals(Gravity.TOP, center and Gravity.VERTICAL_GRAVITY_MASK)
    }
}
