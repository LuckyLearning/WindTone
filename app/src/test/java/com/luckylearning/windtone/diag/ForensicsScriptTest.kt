package com.luckylearning.windtone.diag

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.ui.AppStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 一键取证脚本口径（A1；i18n 第 25 批注释走 `fs_*`）：必须带上当前包名与版本，复制到电脑就能跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class ForensicsScriptTest {

    @Before
    fun setUp() {
        AppStrings.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `脚本含包名版本与关键 adb 命令`() {
        val text = ForensicsScript.build(
            packageName = "com.luckylearning.windtone",
            versionName = "1.2.6",
            versionCode = 116L,
        )
        assertTrue(text.contains("com.luckylearning.windtone"))
        assertTrue(text.contains("1.2.6"))
        assertTrue(text.contains("116"))
        assertTrue(text.contains("mapping-v1.2.6"))
        assertTrue(text.contains("adb logcat"))
        assertTrue(text.contains("dumpsys package"))
        assertTrue(text.contains("dumpsys activity services"))
        assertTrue(text.contains("run-as"))
        assertTrue(text.contains("一键取证") || text.contains("取证完成"))
    }

    @Test
    fun `短提示也点名 mapping 与包名`() {
        val hint = ForensicsScript.shortHint("com.luckylearning.windtone.debug", "9.9.9")
        assertTrue(hint.contains("com.luckylearning.windtone.debug"))
        assertTrue(hint.contains("mapping-v9.9.9"))
        assertFalse(hint.contains("token"))
    }
}
