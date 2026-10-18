package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 开机广播的三种分支（v1.1.24）。
 *
 * 这里锁的是一条**用户不一定看得见、但一错就很打扰**的行为：
 * 「开机后台播放」开着时绝不能弹出全屏界面（车机上车就是导航 / 桌面，
 * 弹窗会直接挡住）；而两个都关时也不能偷偷起播。
 */
class BootActionTest {

    @Test
    fun `只开后台播放时只起服务不弹界面`() {
        assertEquals(
            BootAction.Kind.PLAY_IN_BACKGROUND,
            BootAction.decide(autoPlayOnBoot = true, carMode = false),
        )
        // 车载模式也开着时，同样是后台播放优先——否则用户会被自己的窗口挡住
        assertEquals(
            BootAction.Kind.PLAY_IN_BACKGROUND,
            BootAction.decide(autoPlayOnBoot = true, carMode = true),
        )
    }

    @Test
    fun `只开车载模式时保持老行为直接进界面`() {
        assertEquals(
            BootAction.Kind.OPEN_UI,
            BootAction.decide(autoPlayOnBoot = false, carMode = true),
        )
    }

    @Test
    fun `两个都关时什么都不做`() {
        assertEquals(
            BootAction.Kind.NONE,
            BootAction.decide(autoPlayOnBoot = false, carMode = false),
        )
    }
}
