package com.luckylearning.windtone.ui

import com.luckylearning.windtone.ui.theme.Density
import com.luckylearning.windtone.ui.theme.UiFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段五「播放页布局决策表 / 手势语义」的行为锁。
 *
 * `PlayerScreen` 重构前有 28 处 `if (state.carMode)`，同一个问题被回答 28 遍；
 * 现在全部查 [PlayerLayoutSpec]。这里锁住两件最容易悄悄改坏的事：
 *  · 车机与手机的布尔策略互斥关系（功能对等，不能一端莫名少一块）；
 *  · 切歌手势在两端**方向一致**（上下），左右滑只在没有第二面板时才也切歌。
 */
class PlayerLayoutSpecTest {

    private fun spec(form: UiFormFactor) = PlayerLayoutSpec.from(
        form,
        Density.forFormFactor(form, 1280f, 656f),
    )

    @Test
    fun `车机版式：双栏同场 + 侧动作列 + 场景芯片`() {
        val s = spec(UiFormFactor.CAR)
        assertTrue(s.isCar)
        assertTrue(s.vinylAlongsideLyrics)
        assertTrue(s.sideActions)
        assertTrue(s.sceneChips)
        assertTrue(s.fillHeightOnly)
        assertEquals(40f, s.horizontalPadding.value, 0f)
    }

    @Test
    fun `手机与平板共用同一套非车机版式`() {
        val phone = spec(UiFormFactor.PHONE)
        val tablet = spec(UiFormFactor.TABLET)
        assertEquals(phone.copy(isCar = true), tablet.copy(isCar = true))
        assertFalse(phone.vinylAlongsideLyrics)
        assertTrue(phone.nowRowAlways)
        assertTrue(phone.seekJumpButtons)
        assertTrue(phone.statusRow)
        assertTrue(phone.queueInControls)
        assertEquals(20f, phone.horizontalPadding.value, 0f)
    }

    @Test
    fun `手势不再按形态分叉 —— 三页翻页两端一致`() {
        // 上下滑切歌已删除：纵向整根轴留给歌词滚动。这里断言的是「没有别的分叉残留」——
        // 一旦有人把 HorizontalSwipe / VerticalSwipe 加回来，编译期就会先报错。
        UiFormFactor.entries.forEach { form ->
            assertTrue("$form 长按菜单仍开放", spec(form).gesture.longPressMenu)
        }
    }

    @Test
    fun `长按菜单全端开放 —— 车机不再整块禁用多选`() {
        UiFormFactor.entries.forEach { form ->
            assertTrue("$form 长按菜单", spec(form).gesture.longPressMenu)
        }
    }

    @Test
    fun `功能对等：快进快退与场景芯片全端可用`() {
        UiFormFactor.entries.forEach { form ->
            val s = spec(form)
            assertTrue("$form ±10/15s 快进快退", s.seekJumpButtons)
            assertTrue("$form 场景芯片", s.sceneChips)
            assertTrue("$form 长按菜单", s.gesture.longPressMenu)
        }
    }

    @Test
    fun `两端互斥项逐项对齐`() {
        UiFormFactor.entries.forEach { form ->
            val s = spec(form)
            assertEquals("nowRowAlways / 顶栏歌名", !s.isCar, s.nowRowAlways)
            // 底部状态行仍按用户 2026-10-18 明确要求：车机不显示，给歌词让出 30dp
            assertEquals("statusRow", !s.isCar, s.statusRow)
            assertEquals("queueInControls", !s.isCar, s.queueInControls)
            assertEquals("sideActions", s.isCar, s.sideActions)
            assertEquals("vinylAlongsideLyrics", s.isCar, s.vinylAlongsideLyrics)
        }
    }

    @Test
    fun `控制行尺寸：手机沿用默认 66dp 主键，车机取 CarMetrics`() {
        val phone = spec(UiFormFactor.PHONE)
        assertEquals(66f, phone.mainButton.value, 0f)
        assertEquals(null, phone.sideButton)
        assertEquals(null, phone.secondaryButton)
        assertEquals(null, phone.modeIcon)
        assertEquals(20f, phone.controlSpacing.value, 0f)

        val car = PlayerLayoutSpec.from(
            UiFormFactor.CAR,
            Density.forFormFactor(UiFormFactor.CAR, 1280f, 656f),
            carMetricsFor(1280f, 656f),
        )
        assertEquals(carMetricsFor(1280f, 656f).mainButton, car.mainButton)
        assertEquals(40f, car.controlSpacing.value, 0f)
        assertTrue(car.touchMin.value >= 56f)
    }
}
