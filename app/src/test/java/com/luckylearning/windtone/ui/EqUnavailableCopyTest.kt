package com.luckylearning.windtone.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.AudioFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「均衡器与音效」不可用时的文案（v1.1.22）。
 *
 * 锁的是一次真机误报：车机上正在放歌，弹层却说「播放器尚未启动」。
 * 两种不可用原因（没会话 / 设备不给效果器）必须说成两件事。
 * 设备拒绝时还要说清淡入淡出和音量平衡仍然能用。
 */
@RunWith(RobolectricTestRunner::class)
class EqUnavailableCopyTest {

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `设备拒绝建效果器时说设备不支持，并点明淡入淡出和音量平衡`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = AudioFx.EqState(
            available = false,
            unavailable = AudioFx.Unavailable.DEVICE_REJECTED,
            unavailableDetail = "UnsupportedOperationException: Effect library not loaded",
        )
        assertEquals(R.string.eq_unavailable_device, eqUnavailableRes(state))
        val copy = context.getString(R.string.eq_unavailable_device)
        assertTrue(copy, copy.contains("没有向应用开放音效接口"))
        assertTrue(copy, copy.contains("车辆自带的音效设置"))
        assertTrue(copy, copy.contains("淡入淡出"))
        assertTrue(copy, copy.contains("音量平衡"))
        assertTrue(copy, copy.contains("左右声道平衡"))
        assertFalse("不能说成播放器没启动：#$copy", copy.contains("尚未启动"))
        assertFalse(copy, copy.contains("开始播放"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `还没拿到会话时提示先播放`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = AudioFx.EqState(available = false, unavailable = AudioFx.Unavailable.SESSION_PENDING)
        assertEquals(R.string.eq_unavailable_pending, eqUnavailableRes(state))
        val copy = context.getString(eqUnavailableRes(state))
        assertTrue(copy, copy.contains("开始播放任意一首歌曲"))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `默认状态按未就绪处理`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = context.getString(eqUnavailableRes(AudioFx.EqState()))
        assertTrue(copy, copy.contains("开始播放任意一首歌曲"))
    }

    @Test
    fun `english device copy points at fade and volume balance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = context.getString(R.string.eq_unavailable_device)
        assertTrue(copy, copy.contains("Fade"))
        assertTrue(copy, copy.contains("volume balance"))
        assertTrue(copy, copy.contains("left/right balance"))
        assertFalse(copy, copy.contains("Start playing"))
    }
}
