package com.luckylearning.windtone.ui

import com.luckylearning.windtone.playback.AudioFx
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「均衡器与音效」不可用时的文案（v1.1.22）。
 *
 * 锁的是一次真机误报：车机上正在放歌，弹层却说「播放器尚未启动」。
 * 两种不可用原因（没会话 / 设备不给效果器）必须说成两件事，
 * 否则用户会被指去点播放键，怎么点都不会好。
 */
class EqUnavailableCopyTest {

    @Test
    fun `设备拒绝建效果器时说设备不支持，不说没播放`() {
        val copy = eqUnavailableCopy(
            AudioFx.EqState(
                available = false,
                unavailable = AudioFx.Unavailable.DEVICE_REJECTED,
                unavailableDetail = "UnsupportedOperationException: Effect library not loaded",
            ),
        )
        assertTrue(copy, copy.contains("没有向应用开放音效接口"))
        assertTrue(copy, copy.contains("车辆自带的音效设置"))
        assertFalse("不能说成播放器没启动：#$copy", copy.contains("尚未启动"))
        assertFalse(copy, copy.contains("开始播放"))
    }

    @Test
    fun `还没拿到会话时提示先播放`() {
        val copy = eqUnavailableCopy(
            AudioFx.EqState(available = false, unavailable = AudioFx.Unavailable.SESSION_PENDING),
        )
        assertTrue(copy, copy.contains("开始播放任意一首歌曲"))
    }

    @Test
    fun `默认状态按未就绪处理`() {
        // 服务刚起来、attach 还没跑过时 unavailable 是默认值 NONE，
        // 此时给「先播放」的提示是安全的方向（它确实还没开始播）
        val copy = eqUnavailableCopy(AudioFx.EqState())
        assertTrue(copy, copy.contains("开始播放任意一首歌曲"))
    }
}
