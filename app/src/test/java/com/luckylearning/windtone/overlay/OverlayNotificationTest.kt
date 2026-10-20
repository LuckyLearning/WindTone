package com.luckylearning.windtone.overlay

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 前台服务通知的冒烟：通道能创建、通知能构建。
 *
 * 从 `LyricOverlayService` 抽出后，这块可以在 JVM 上验证；以前只能把服务拉起来看
 * 通知栏有没有那张常驻卡片。
 */
@RunWith(RobolectricTestRunner::class)
class OverlayNotificationTest {

    @Test
    fun buildCreatesChannelAndNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val notification = OverlayNotification.build(context)
        assertNotNull("通知必须能构建出来", notification)

        val nm = context.getSystemService(NotificationManager::class.java)
        assertNotNull("悬浮歌词通知通道必须已创建", nm.getNotificationChannel("lyric_overlay"))
    }
}
