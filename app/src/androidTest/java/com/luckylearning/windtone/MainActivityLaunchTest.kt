package com.luckylearning.windtone

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器化冒烟：主界面能真正启动。
 *
 * 纯 JVM 的 Robolectric 测试覆盖不到「Activity → Application → AppContainer →
 * Room / PlaybackEngine / 四个 ViewModel」这条真实装配链；这里在模拟器/真机上
 * 启动一次 [MainActivity]，只要不崩、不立刻结束就算通过。
 *
 * 断言刻意很轻：不钉具体文案或控件，避免把界面改版变成测试失败。
 * 运行方式：
 *  - 联网：`./gradlew connectedDebugAndroidTest`
 *  - 离线（AGP 的 UTP 插件未缓存时）：`./gradlew assembleDebug assembleDebugAndroidTest`，
 *    安装两个 APK 后执行
 *    `adb shell am instrument -w com.luckylearning.windtone.debug.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    @Test
    fun launchesWithoutCrash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse("MainActivity 启动后不应立刻结束", activity.isFinishing)
            }
        } finally {
            scenario.close()
        }
    }
}
